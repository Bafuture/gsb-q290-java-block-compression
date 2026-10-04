package com.example.gsb.blockio;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.CRC32;

/**
 * Random-access reader for archives produced by {@link ChunkArchiveWriter}.
 *
 * <p>Only the chunks overlapping a requested range are read from disk and
 * decompressed; the rest of the file is never touched. Reads are thread-safe:
 * positional {@link FileChannel} reads let different chunks be fetched and
 * inflated concurrently, and a per-chunk future guarantees that concurrent
 * requests for the same chunk decompress it exactly once. Decompressed chunks
 * are cached for the lifetime of the reader unless caching is disabled.
 */
public final class ChunkArchiveReader implements Closeable {

    private final FileChannel channel;
    private final ChunkIndex index;
    private final BlockCodec codec;
    private final Executor executor;
    private final ExecutorService ownedExecutor;
    private final boolean cacheChunks;
    private final ConcurrentHashMap<Integer, CompletableFuture<byte[]>> chunkFutures =
            new ConcurrentHashMap<>();
    private volatile boolean closed;

    public static ChunkArchiveReader open(Path path) throws IOException {
        ExecutorService pool = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "chunk-archive-inflater");
            thread.setDaemon(true);
            return thread;
        });
        return open(path, pool, new DeflateCodec(), true, pool);
    }

    public static ChunkArchiveReader open(Path path, Executor executor) throws IOException {
        return open(path, executor, new DeflateCodec(), true, null);
    }

    public static ChunkArchiveReader open(Path path, BlockCodec codec) throws IOException {
        return open(path, defaultExecutor(), codec, true, null);
    }

    public static ChunkArchiveReader open(Path path, Executor executor, BlockCodec codec)
            throws IOException {
        return open(path, executor, codec, true, null);
    }

    /**
     * @param cacheChunks when {@code false}, decompressed chunks are not retained;
     *                    concurrent requests for the same chunk still share one inflation
     */
    public static ChunkArchiveReader open(Path path, Executor executor, BlockCodec codec,
                                          boolean cacheChunks) throws IOException {
        return open(path, executor, codec, cacheChunks, null);
    }

    private static Executor defaultExecutor() {
        return Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "chunk-archive-inflater");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static ChunkArchiveReader open(Path path, Executor executor, BlockCodec codec,
                                           boolean cacheChunks, ExecutorService ownedExecutor)
            throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(codec, "codec");
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
        try {
            return new ChunkArchiveReader(channel, path, executor, codec, cacheChunks, ownedExecutor);
        } catch (IOException | RuntimeException e) {
            try {
                channel.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    private ChunkArchiveReader(FileChannel channel, Path path, Executor executor,
                               BlockCodec codec, boolean cacheChunks,
                               ExecutorService ownedExecutor) throws IOException {
        this.channel = channel;
        this.executor = executor;
        this.codec = codec;
        this.cacheChunks = cacheChunks;
        this.ownedExecutor = ownedExecutor;
        this.index = readIndex(channel, path, codec);
    }

    private static ChunkIndex readIndex(FileChannel channel, Path path, BlockCodec codec)
            throws IOException {
        long size = channel.size();
        if (size < ArchiveFormat.FOOTER_SIZE) {
            throw new IOException("Not a chunk archive (file too small): " + path);
        }
        ByteBuffer footer = ByteBuffer.allocate(ArchiveFormat.FOOTER_SIZE)
                .order(ByteOrder.BIG_ENDIAN);
        readFully(channel, footer, size - ArchiveFormat.FOOTER_SIZE);
        footer.flip();

        long indexOffset = footer.getLong();
        int chunkCount = footer.getInt();
        int chunkSize = footer.getInt();
        long originalSize = footer.getLong();
        int codecId = footer.getInt();
        int magic = footer.getInt();

        if (magic != ArchiveFormat.FOOTER_MAGIC) {
            throw new IOException("Not a chunk archive (bad footer magic): " + path);
        }
        if (codecId != codec.id()) {
            throw new IOException("Archive uses codec id " + codecId
                    + " but reader was given codec id " + codec.id());
        }
        if (chunkCount < 0 || chunkSize <= 0 || indexOffset < 0
                || indexOffset + (long) chunkCount * ArchiveFormat.INDEX_ENTRY_SIZE
                > size - ArchiveFormat.FOOTER_SIZE) {
            throw new IOException("Corrupt archive footer (index out of bounds): " + path);
        }

        ByteBuffer indexBuffer = ByteBuffer.allocate(chunkCount * ArchiveFormat.INDEX_ENTRY_SIZE)
                .order(ByteOrder.BIG_ENDIAN);
        readFully(channel, indexBuffer, indexOffset);
        indexBuffer.flip();

        List<ChunkEntry> entries = new ArrayList<>(chunkCount);
        for (int i = 0; i < chunkCount; i++) {
            entries.add(new ChunkEntry(indexBuffer.getLong(), indexBuffer.getInt(),
                    indexBuffer.getInt(), indexBuffer.getLong()));
        }
        return new ChunkIndex(chunkSize, originalSize, codecId, List.copyOf(entries));
    }

    /** Total number of uncompressed bytes in the archive. */
    public long originalSize() {
        return index.originalSize();
    }

    /** Configured chunk size. */
    public int chunkSize() {
        return index.chunkSize();
    }

    /** Number of chunks in the archive. */
    public int chunkCount() {
        return index.chunkCount();
    }

    /** The parsed index. */
    public ChunkIndex index() {
        return index;
    }

    /**
     * Reads {@code length} bytes starting at uncompressed {@code offset}.
     * Only the chunks overlapping the range are decompressed.
     *
     * @throws IndexOutOfBoundsException if the range leaves the archive
     * @throws ChunkCorruptedException   if an overlapping chunk fails its checksum
     */
    public byte[] read(long offset, int length) throws IOException {
        byte[] result = new byte[length];
        read(offset, result, 0, length);
        return result;
    }

    /**
     * Reads {@code length} bytes starting at uncompressed {@code offset} into
     * {@code dst} at {@code dstOffset}.
     */
    public void read(long offset, byte[] dst, int dstOffset, int length) throws IOException {
        Objects.requireNonNull(dst, "dst");
        Objects.checkFromIndexSize(dstOffset, length, dst.length);
        if (closed) {
            throw new IOException("Archive reader is closed");
        }
        long originalSize = index.originalSize();
        if (offset < 0 || length < 0 || offset > originalSize - length) {
            throw new IndexOutOfBoundsException("Range [" + offset + ", " + (offset + length)
                    + ") is outside archive of size " + originalSize);
        }
        if (length == 0) {
            return;
        }

        int chunkSize = index.chunkSize();
        long end = offset + length;
        int firstChunk = (int) (offset / chunkSize);
        int lastChunk = (int) ((end - 1) / chunkSize);

        List<CompletableFuture<byte[]>> futures = new ArrayList<>(lastChunk - firstChunk + 1);
        for (int chunk = firstChunk; chunk <= lastChunk; chunk++) {
            futures.add(chunkFuture(chunk));
        }

        int written = 0;
        for (int chunk = firstChunk; chunk <= lastChunk; chunk++) {
            byte[] data = join(futures.get(chunk - firstChunk));
            long chunkStart = (long) chunk * chunkSize;
            int from = (int) Math.max(0, offset - chunkStart);
            int to = (int) Math.min(data.length, end - chunkStart);
            System.arraycopy(data, from, dst, dstOffset + written, to - from);
            written += to - from;
        }
    }

    private CompletableFuture<byte[]> chunkFuture(int chunkIndex) {
        if (cacheChunks) {
            return chunkFutures.computeIfAbsent(chunkIndex, this::inflateAsync);
        }
        CompletableFuture<byte[]> future =
                chunkFutures.computeIfAbsent(chunkIndex, this::inflateAsync);
        future.whenComplete((result, error) -> {
            if (!cacheChunks) {
                chunkFutures.remove(chunkIndex, future);
            }
        });
        return future;
    }

    private CompletableFuture<byte[]> inflateAsync(int chunkIndex) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return inflateChunk(chunkIndex);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    private byte[] inflateChunk(int chunkIndex) throws IOException {
        ChunkEntry entry = index.entries().get(chunkIndex);
        try {
            ByteBuffer compressed = ByteBuffer.allocate(entry.compressedLength());
            readFully(channel, compressed,
                    entry.compressedOffset() + ArchiveFormat.CHUNK_HEADER_SIZE);
            byte[] data = codec.decompress(compressed.array(), entry.originalLength());

            CRC32 crc = new CRC32();
            crc.update(data);
            if (crc.getValue() != entry.crc32()) {
                throw new ChunkCorruptedException(chunkIndex,
                        "CRC32 mismatch (expected " + Long.toHexString(entry.crc32())
                                + ", got " + Long.toHexString(crc.getValue()) + ")");
            }
            return data;
        } catch (ChunkCorruptedException e) {
            throw e;
        } catch (IOException e) {
            throw new ChunkCorruptedException(chunkIndex,
                    "failed to read or decompress chunk", e);
        }
    }

    private static byte[] join(CompletableFuture<byte[]> future) throws IOException {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException(cause);
        }
    }

    private static void readFully(FileChannel channel, ByteBuffer dst, long position)
            throws IOException {
        while (dst.hasRemaining()) {
            int n = channel.read(dst, position);
            if (n < 0) {
                throw new EOFException("Unexpected end of archive at offset " + position);
            }
            position += n;
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            channel.close();
        } finally {
            if (ownedExecutor != null) {
                ownedExecutor.shutdown();
            }
        }
    }
}
