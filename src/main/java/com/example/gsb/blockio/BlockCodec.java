package com.example.gsb.blockio;

import java.io.IOException;

/**
 * A per-chunk compression algorithm. Implementations must be safe for use
 * from multiple threads (stateless instances are recommended).
 */
public interface BlockCodec {

    /** Numeric id stored in the archive footer. */
    int id();

    /** Compresses {@code length} bytes of {@code data} starting at {@code offset}. */
    byte[] compress(byte[] data, int offset, int length) throws IOException;

    /**
     * Decompresses a chunk, verifying that the output has exactly {@code expectedLength} bytes.
     *
     * @throws IOException if the payload cannot be decoded or has an unexpected length
     */
    byte[] decompress(byte[] compressed, int expectedLength) throws IOException;
}
