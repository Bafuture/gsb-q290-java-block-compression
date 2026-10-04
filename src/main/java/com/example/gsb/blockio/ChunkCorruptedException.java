package com.example.gsb.blockio;

import java.io.IOException;

/**
 * Thrown when a chunk fails to decompress or its CRC32 checksum does not match.
 * The {@link #chunkIndex()} identifies the corrupt chunk so callers can locate it.
 */
public class ChunkCorruptedException extends IOException {

    private final int chunkIndex;

    public ChunkCorruptedException(int chunkIndex, String message) {
        super("Corrupt chunk " + chunkIndex + ": " + message);
        this.chunkIndex = chunkIndex;
    }

    public ChunkCorruptedException(int chunkIndex, String message, Throwable cause) {
        super("Corrupt chunk " + chunkIndex + ": " + message, cause);
        this.chunkIndex = chunkIndex;
    }

    public int chunkIndex() {
        return chunkIndex;
    }
}
