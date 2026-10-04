package com.example.gsb.blockio;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Streaming writer for a chunk-compressed archive.
 *
 * <p>Bytes handed to {@link #write(byte[], int, int)} are accumulated until the
 * configured chunk size is reached; every full chunk is compressed immediately
 * and an index entry (compressed offset/length + checksum) is appended. The
 * index and footer are flushed on {@link #close()}, after which the file can be
 * opened with {@link ChunkArchiveReader} for random access.
 */
public final class ChunkArchiveWriter implements Closeable {

    /** Default chunk size: 64 KiB. */
    public static final int DEFAULT_CHUNK_SIZE = 64 * 1024;

    private final DataOutputStream out;
    private final BlockCodec codec;
    private final int chunkSize;
    private final byte[] buffer;
    private final List<ChunkEntry> entries = new ArrayList<>();

    private int buffered;
    private long position;
    private long originalSize;
    private boolean closed;

    public ChunkArchiveWriter(OutputStream out) {
        this(out, DEFAULT_CHUNK_SIZE, new DeflateCodec());
    }

    public ChunkArchiveWriter(OutputStream out, int chunkSize) {
        this(out, chunkSize, new DeflateCodec());
    }

    public ChunkArchiveWriter(OutputStream out, int chunkSize, BlockCodec codec) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive, got " + chunkSize);
        }
        this.out = new DataOutputStream(new BufferedOutputStream(out,
                Math.min(Math.max(chunkSize, 4096), 1 << 20)));
        this.chunkSize = chunkSize;
        this.codec = Objects.requireNonNull(codec, "codec");
        this.buffer = new byte[chunkSize];
    }

    /** Number of uncompressed bytes written so far. */
    public long originalSize() {
        return originalSize;
    }

    /** Number of chunks already flushed. */
    public int chunkCount() {
        return entries.size();
    }

    /** Configured chunk size. */
    public int chunkSize() {
        return chunkSize;
    }

    /**
     * Snapshot of the index entries produced so far. Entries appear as soon as
     * the corresponding chunk is flushed, so the index grows while streaming.
     */
    public List<ChunkEntry> index() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public void write(int b) throws IOException {
        ensureOpen();
        buffer[buffered++] = (byte) b;
        originalSize++;
        if (buffered == chunkSize) {
            flushChunk();
        }
    }

    public void write(byte[] data) throws IOException {
        write(data, 0, data.length);
    }

    public void write(byte[] data, int offset, int length) throws IOException {
        ensureOpen();
        Objects.checkFromIndexSize(offset, length, data.length);
        originalSize += length;
        while (length > 0) {
            int n = Math.min(length, chunkSize - buffered);
            System.arraycopy(data, offset, buffer, buffered, n);
            buffered += n;
            offset += n;
            length -= n;
            if (buffered == chunkSize) {
                flushChunk();
            }
        }
    }

    private void flushChunk() throws IOException {
        if (buffered == 0) {
            return;
        }
        byte[] compressed = codec.compress(buffer, 0, buffered);

        CRC32 crc = new CRC32();
        crc.update(buffer, 0, buffered);
        long checksum = crc.getValue();

        long chunkOffset = position;
        out.writeInt(ArchiveFormat.CHUNK_MAGIC);
        out.writeInt(compressed.length);
        out.writeInt(buffered);
        out.writeLong(checksum);
        out.write(compressed);

        position += ArchiveFormat.CHUNK_HEADER_SIZE + (long) compressed.length;
        entries.add(new ChunkEntry(chunkOffset, compressed.length, buffered, checksum));
        buffered = 0;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        flushChunk();

        long indexOffset = position;
        for (ChunkEntry entry : entries) {
            out.writeLong(entry.compressedOffset());
            out.writeInt(entry.compressedLength());
            out.writeInt(entry.originalLength());
            out.writeLong(entry.crc32());
        }

        out.writeLong(indexOffset);
        out.writeInt(entries.size());
        out.writeInt(chunkSize);
        out.writeLong(originalSize);
        out.writeInt(codec.id());
        out.writeInt(ArchiveFormat.FOOTER_MAGIC);
        out.flush();
        out.close();
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Archive writer is already closed");
        }
    }
}
