package com.example.gsb.blockio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Produces the chunk-size tradeoff table (compression ratio vs. random-read
 * cost) and asserts the expected ordering between chunk sizes.
 */
class ChunkSizeTradeoffTest {

    @Test
    void smallerChunksRandomReadFasterButCompressWorse() throws IOException {
        byte[] data = compressiblePayload(2 * 1024 * 1024);
        int[] chunkSizes = {4 * 1024, 16 * 1024, 64 * 1024, 256 * 1024, 1024 * 1024};

        List<ChunkSizeTradeoff.Result> results =
                ChunkSizeTradeoff.evaluate(data, chunkSizes, 512, 100);

        System.out.println("Chunk size tradeoff (payload=2MiB, 100 random reads of 512B, cold cache):");
        results.forEach(r -> System.out.println("  " + r));

        ChunkSizeTradeoff.Result smallest = results.get(0);
        ChunkSizeTradeoff.Result largest = results.get(results.size() - 1);

        // Compression: bigger chunks see more redundancy -> smaller archives.
        assertThat(largest.compressedSize()).isLessThan(smallest.compressedSize());
        assertThat(largest.compressionRatio()).isLessThan(smallest.compressionRatio());

        // Random access: bigger chunks inflate far more bytes per small read.
        assertThat(smallest.avgBytesInflatedPerRead())
                .isLessThanOrEqualTo(2.0 * smallest.chunkSize());
        assertThat(largest.avgBytesInflatedPerRead())
                .isGreaterThanOrEqualTo(largest.chunkSize());
        assertThat(results.get(1).avgBytesInflatedPerRead())
                .isGreaterThan(smallest.avgBytesInflatedPerRead());

        // Index cost grows as chunks shrink.
        assertThat(smallest.indexBytes()).isGreaterThan(largest.indexBytes());
        assertThat(smallest.chunkCount()).isGreaterThan(largest.chunkCount());
    }

    private static byte[] compressiblePayload(int size) {
        String[] words = {"the", "quick", "brown", "fox", "jumps", "over", "lazy",
                "dog", "compression", "chunk", "random", "access", "archive"};
        StringBuilder builder = new StringBuilder(size + 64);
        Random random = new Random(7);
        while (builder.length() < size) {
            builder.append(words[random.nextInt(words.length)]).append(' ');
        }
        byte[] full = builder.toString().getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[size];
        System.arraycopy(full, 0, data, 0, size);
        return data;
    }
}
