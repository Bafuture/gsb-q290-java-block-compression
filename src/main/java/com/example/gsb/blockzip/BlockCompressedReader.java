package com.example.gsb.blockzip;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * 分块压缩文件的随机访问读取器。
 *
 * <p>打开时只读取文件头、索引区与文件尾，解压按需进行：
 * {@link #read(long, int)} 只解压与请求范围相交的块，绝不解压整个文件。
 * 解压结果按块缓存（LRU），每块解压后会用索引中的 CRC-32 校验，
 * 损坏时抛出携带块序号的 {@link CorruptBlockException}。
 *
 * <p>线程安全：底层使用 {@link FileChannel} 的位置无关读取，不同块的
 * 并发解压互不阻塞；同一块的并发请求通过每块一个 {@link CompletableFuture}
 * 去重，只解压一次，其余线程等待同一结果。
 */
public final class BlockCompressedReader implements AutoCloseable {

    public static final int DEFAULT_MAX_CACHED_BLOCKS = 64;

    private final FileChannel channel;
    private final int blockSize;
    private final long originalSize;
    private final List<BlockIndexEntry> index;
    private final Map<Integer, CompletableFuture<byte[]>> cache;

    /** 实际执行解压的次数（测试可见，用于验证“同一块只解压一次”）。 */
    final AtomicLong decompressionCount = new AtomicLong();

    public BlockCompressedReader(Path path) throws IOException {
        this(path, DEFAULT_MAX_CACHED_BLOCKS);
    }

    public BlockCompressedReader(Path path, int maxCachedBlocks) throws IOException {
        if (maxCachedBlocks <= 0) {
            throw new IllegalArgumentException("缓存块数必须为正数: " + maxCachedBlocks);
        }
        this.channel = FileChannel.open(path, StandardOpenOption.READ);
        try {
            long fileSize = channel.size();
            if (fileSize < BlockCompressedWriter.HEADER_SIZE + BlockCompressedWriter.TRAILER_SIZE) {
                throw new IOException("文件太小，不是有效的分块压缩文件: " + path);
            }

            ByteBuffer header = readExactly(0, BlockCompressedWriter.HEADER_SIZE);
            byte[] magic = new byte[BlockCompressedWriter.HEADER_MAGIC.length];
            header.get(magic);
            if (!java.util.Arrays.equals(magic, BlockCompressedWriter.HEADER_MAGIC)) {
                throw new IOException("魔数不匹配，不是分块压缩文件: " + path);
            }
            this.blockSize = header.getInt();

            ByteBuffer trailer = readExactly(fileSize - BlockCompressedWriter.TRAILER_SIZE,
                    BlockCompressedWriter.TRAILER_SIZE);
            long indexOffset = trailer.getLong();
            byte[] endMagic = new byte[BlockCompressedWriter.TRAILER_MAGIC.length];
            trailer.get(endMagic);
            if (!java.util.Arrays.equals(endMagic, BlockCompressedWriter.TRAILER_MAGIC)) {
                throw new IOException("文件尾魔数不匹配，文件可能不完整: " + path);
            }

            int indexLength = (int) (fileSize - BlockCompressedWriter.TRAILER_SIZE - indexOffset);
            ByteBuffer indexBuf = readExactly(indexOffset, indexLength);
            int blockCount = indexBuf.getInt();
            this.originalSize = indexBuf.getLong();
            if (indexBuf.remaining() != blockCount * BlockCompressedWriter.INDEX_ENTRY_SIZE) {
                throw new IOException("索引区长度与块数不符，文件可能损坏: " + path);
            }
            List<BlockIndexEntry> entries = new ArrayList<>(blockCount);
            for (int i = 0; i < blockCount; i++) {
                entries.add(new BlockIndexEntry(
                        indexBuf.getLong(), indexBuf.getInt(), indexBuf.getInt(), indexBuf.getLong()));
            }
            this.index = Collections.unmodifiableList(entries);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, CompletableFuture<byte[]>> eldest) {
                return size() > maxCachedBlocks;
            }
        });
    }

    public int blockSize() {
        return blockSize;
    }

    /** 原始（未压缩）数据总长度。 */
    public long originalSize() {
        return originalSize;
    }

    public int blockCount() {
        return index.size();
    }

    /** 完整索引（只读），按块序号排列。 */
    public List<BlockIndexEntry> index() {
        return index;
    }

    /**
     * 读取原始数据中 [offset, offset+length) 区间。只解压相交的块。
     * 跨块时自动拼接，边界不重复不遗漏。
     */
    public byte[] read(long offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset + length > originalSize) {
            throw new IndexOutOfBoundsException(
                    "请求区间 [" + offset + ", " + (offset + length) + ") 超出数据范围 [0, " + originalSize + ")");
        }
        if (length == 0) {
            return new byte[0];
        }
        int firstBlock = (int) (offset / blockSize);
        int lastBlock = (int) ((offset + length - 1) / blockSize);
        byte[] result = new byte[length];
        int pos = 0;
        for (int b = firstBlock; b <= lastBlock; b++) {
            byte[] block = block(b);
            int start = (b == firstBlock) ? (int) (offset % blockSize) : 0;
            int n = Math.min(block.length - start, length - pos);
            System.arraycopy(block, start, result, pos, n);
            pos += n;
        }
        return result;
    }

    /** 获取指定块的解压数据（带缓存与并发去重）。 */
    private byte[] block(int blockIndex) throws IOException {
        CompletableFuture<byte[]> mine = new CompletableFuture<>();
        CompletableFuture<byte[]> future = cache.putIfAbsent(blockIndex, mine);
        if (future == null) {
            future = mine;
            try {
                mine.complete(decompressBlock(blockIndex));
            } catch (Throwable t) {
                cache.remove(blockIndex, mine);
                mine.completeExceptionally(t);
            }
        }
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }
    }

    private byte[] decompressBlock(int blockIndex) throws IOException {
        decompressionCount.incrementAndGet();
        BlockIndexEntry entry = index.get(blockIndex);
        ByteBuffer compressed = readExactly(entry.compressedOffset(), entry.compressedLength());
        byte[] out = new byte[entry.originalLength()];
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(compressed);
            int written = inflater.inflate(out);
            if (written != out.length || !inflater.finished() || inflater.getRemaining() != 0) {
                throw new CorruptBlockException(blockIndex,
                        "DEFLATE 解压结果不完整（期望 " + out.length + " 字节，实际 " + written + " 字节）", null);
            }
        } catch (DataFormatException e) {
            throw new CorruptBlockException(blockIndex, "DEFLATE 数据无法解压", e);
        } finally {
            inflater.end();
        }
        CRC32 crc = new CRC32();
        crc.update(out);
        if (crc.getValue() != entry.crc32()) {
            throw new CorruptBlockException(blockIndex, entry.crc32(), crc.getValue());
        }
        return out;
    }

    private ByteBuffer readExactly(long position, int length) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(length);
        while (buf.hasRemaining()) {
            if (channel.read(buf, position + buf.position()) < 0) {
                throw new IOException("文件在偏移 " + (position + buf.position()) + " 处提前结束");
            }
        }
        buf.flip();
        return buf;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
