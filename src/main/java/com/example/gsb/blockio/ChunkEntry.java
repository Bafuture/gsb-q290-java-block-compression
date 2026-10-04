package com.example.gsb.blockio;

/**
 * Index entry describing one compressed chunk.
 *
 * @param compressedOffset byte offset of the chunk record inside the archive
 * @param compressedLength length of the compressed payload (chunk header excluded)
 * @param originalLength   length of the uncompressed chunk
 * @param crc32            CRC32 checksum of the uncompressed chunk bytes
 */
public record ChunkEntry(long compressedOffset, int compressedLength,
                         int originalLength, long crc32) {
}
