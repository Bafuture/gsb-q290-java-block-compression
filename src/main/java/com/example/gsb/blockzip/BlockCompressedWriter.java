package com.example.gsb.blockzip;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * 分块压缩写入器（流式）。
 *
 * <p>数据按固定大小的块缓冲，每攒满一块立即用 DEFLATE 压缩并落盘，
 * 同时在内存中累积索引项（压缩偏移、压缩长度、原始长度、CRC-32）。
 * {@link #close()} 时把剩余不足一块的数据冲刷为最后一块，随后把索引
 * 与文件尾（含索引偏移）追加到文件末尾。close 之后文件即可被
 * {@link BlockCompressedReader} 随机读取。
 *
 * <p>文件布局：
 * <pre>
 *   [0..7]                魔数 "BLKZ0001"
 *   [8..11]               int 块大小
 *   ...                   各块的原始 DEFLATE 压缩数据，依次排列
 *   索引区                int 块数, long 原始总长度,
 *                         每块: long 压缩偏移, int 压缩长度, int 原始长度, long CRC-32
 *   文件尾                long 索引区偏移, 魔数 "BLKZEND1"
 * </pre>
 *
 * <p>非线程安全。
 */
public final class BlockCompressedWriter extends OutputStream {

    public static final int DEFAULT_BLOCK_SIZE = 64 * 1024;

    static final byte[] HEADER_MAGIC = {'B', 'L', 'K', 'Z', '0', '0', '0', '1'};
    static final byte[] TRAILER_MAGIC = {'B', 'L', 'K', 'Z', 'E', 'N', 'D', '1'};
    static final int HEADER_SIZE = HEADER_MAGIC.length + Integer.BYTES;
    static final int TRAILER_SIZE = Long.BYTES + TRAILER_MAGIC.length;
    /** 单个索引项的字节数：long + int + int + long。 */
    static final int INDEX_ENTRY_SIZE = Long.BYTES + Integer.BYTES + Integer.BYTES + Long.BYTES;

    private final FileChannel channel;
    private final int blockSize;
    private final byte[] blockBuffer;
    private final List<BlockIndexEntry> index = new ArrayList<>();

    private int buffered;
    private long compressedOffset = HEADER_SIZE;
    private long originalSize;
    private boolean closed;

    public BlockCompressedWriter(Path path) throws IOException {
        this(path, DEFAULT_BLOCK_SIZE);
    }

    public BlockCompressedWriter(Path path, int blockSize) throws IOException {
        if (blockSize <= 0) {
            throw new IllegalArgumentException("块大小必须为正数: " + blockSize);
        }
        this.blockSize = blockSize;
        this.blockBuffer = new byte[blockSize];
        this.channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
        header.put(HEADER_MAGIC);
        header.putInt(blockSize);
        header.flip();
        writeFully(header);
    }

    public int blockSize() {
        return blockSize;
    }

    /** 已写入的原始（未压缩）字节数。 */
    public long originalSize() {
        return originalSize;
    }

    /** 已冲刷的完整块数量（不含当前缓冲中的未满块）。 */
    public int flushedBlockCount() {
        return index.size();
    }

    @Override
    public void write(int b) throws IOException {
        ensureOpen();
        blockBuffer[buffered++] = (byte) b;
        originalSize++;
        if (buffered == blockSize) {
            flushBlock();
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        ensureOpen();
        if (off < 0 || len < 0 || off + len > b.length) {
            throw new IndexOutOfBoundsException();
        }
        originalSize += len;
        while (len > 0) {
            int n = Math.min(len, blockSize - buffered);
            System.arraycopy(b, off, blockBuffer, buffered, n);
            buffered += n;
            off += n;
            len -= n;
            if (buffered == blockSize) {
                flushBlock();
            }
        }
    }

    @Override
    public void flush() {
        // 块未满时不落盘；真正的冲刷发生在块满或 close() 时。
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (buffered > 0) {
                flushBlock();
            }
            writeIndexAndTrailer();
        } finally {
            channel.close();
        }
    }

    private void flushBlock() throws IOException {
        byte[] compressed = deflate(blockBuffer, buffered);
        CRC32 crc = new CRC32();
        crc.update(blockBuffer, 0, buffered);
        index.add(new BlockIndexEntry(compressedOffset, compressed.length, buffered, crc.getValue()));
        writeFully(ByteBuffer.wrap(compressed));
        compressedOffset += compressed.length;
        buffered = 0;
    }

    private void writeIndexAndTrailer() throws IOException {
        long indexOffset = compressedOffset;
        ByteBuffer buf = ByteBuffer.allocate(Integer.BYTES + Long.BYTES + index.size() * INDEX_ENTRY_SIZE);
        buf.putInt(index.size());
        buf.putLong(originalSize);
        for (BlockIndexEntry e : index) {
            buf.putLong(e.compressedOffset());
            buf.putInt(e.compressedLength());
            buf.putInt(e.originalLength());
            buf.putLong(e.crc32());
        }
        buf.flip();
        writeFully(buf);

        ByteBuffer trailer = ByteBuffer.allocate(TRAILER_SIZE);
        trailer.putLong(indexOffset);
        trailer.put(TRAILER_MAGIC);
        trailer.flip();
        writeFully(trailer);
    }

    private void writeFully(ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("写入器已关闭");
        }
    }

    private static byte[] deflate(byte[] data, int length) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try {
            deflater.setInput(data, 0, length);
            deflater.finish();
            byte[] out = new byte[length + 64];
            int total = 0;
            while (!deflater.finished()) {
                if (total == out.length) {
                    byte[] bigger = new byte[out.length * 2];
                    System.arraycopy(out, 0, bigger, 0, out.length);
                    out = bigger;
                }
                total += deflater.deflate(out, total, out.length - total);
            }
            byte[] result = new byte[total];
            System.arraycopy(out, 0, result, 0, total);
            return result;
        } finally {
            deflater.end();
        }
    }
}
