package com.example.gsb.blockio;

import java.util.List;

/**
 * The full archive index parsed from the footer and index section.
 *
 * @param chunkSize    configured (maximum) chunk size
 * @param originalSize total number of uncompressed bytes
 * @param codecId      codec id stored in the footer
 * @param entries      one entry per chunk, in chunk order
 */
public record ChunkIndex(int chunkSize, long originalSize, int codecId,
                         List<ChunkEntry> entries) {

    public int chunkCount() {
        return entries.size();
    }
}
