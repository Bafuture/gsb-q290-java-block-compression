package com.example.gsb.blockio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Measures how the configured chunk size trades compression ratio against
 * random-access cost. For every candidate chunk size the same payload is
 * archived, then a number of fixed-length random reads are served with chunk
 * caching disabled, so every read pays the real inflation cost of the chunks
 * it touches.
 */
public final class ChunkSizeTradeoff {

    /** Outcome of benchmarking one chunk size. */
    public record Result(
            int chunkSize,
            long originalSize,
            long compressedSize,
            long indexBytes,
            int chunkCount,
            long writeNanos,
            long randomReadNanos,
            int randomReadCount,
            long totalBytesInflated) {

        /** compressedSize / originalSize; lower is better. */
        public double compressionRatio() {
            return originalSize == 0 ? 1.0 : (double) compressedSize / originalSize;
        }

        /** Average wall-clock latency of one random read, in microseconds. */
        public double avgReadMicros() {
            return randomReadCount == 0 ? 0.0 : randomReadNanos / 1_000.0 / randomReadCount;
        }

        /** Average number of bytes that had to be decompressed per random read. */
        public double avgBytesInflatedPerRead() {
            return randomReadCount == 0 ? 0.0 : (double) totalBytesInflated / randomReadCount;
        }

        @Override
        public String toString() {
            return String.format(
                    "chunkSize=%8d  ratio=%.4f  compressed=%10d  index=%7d  chunks=%6d"
                            + "  write=%6.1fms  avgRead=%8.1fus  avgInflated=%10.0fB",
                    chunkSize, compressionRatio(), compressedSize, indexBytes, chunkCount,
                    writeNanos / 1_000_000.0, avgReadMicros(), avgBytesInflatedPerRead());
        }
    }

    private ChunkSizeTradeoff() {
    }

    /**
     * Benchmarks {@code data} against every chunk size in {@code chunkSizes}.
     *
     * @param randomReadLength length of each random read request
     * @param randomReadCount  number of random reads per chunk size
     */
    public static List<Result> evaluate(byte[] data, int[] chunkSizes,
                                        int randomReadLength, int randomReadCount)
            throws IOException {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(chunkSizes, "chunkSizes");
        if (randomReadLength < 0 || randomReadLength > data.length) {
            throw new IllegalArgumentException("randomReadLength " + randomReadLength
                    + " out of range for payload of " + data.length + " bytes");
        }
        List<Result> results = new ArrayList<>();
        for (int chunkSize : chunkSizes) {
            results.add(evaluateOne(data, chunkSize, randomReadLength, randomReadCount));
        }
        return results;
    }

    private static Result evaluateOne(byte[] data, int chunkSize,
                                      int randomReadLength, int randomReadCount)
            throws IOException {
        Path temp = Files.createTempFile("chunk-size-tradeoff-", ".bin");
        try {
            long writeStart = System.nanoTime();
            try (ChunkArchiveWriter writer =
                         new ChunkArchiveWriter(Files.newOutputStream(temp), chunkSize)) {
                writer.write(data);
            }
            long writeNanos = System.nanoTime() - writeStart;
            long compressedSize = Files.size(temp);

            CountingDeflateCodec countingCodec = new CountingDeflateCodec();
            long readNanos = 0;
            int reads = 0;
            try (ChunkArchiveReader reader = ChunkArchiveReader.open(
                    temp, Runnable::run, countingCodec, false)) {
                long maxOffset = data.length - randomReadLength;
                Random random = new Random(42);
                for (int i = 0; i < randomReadCount; i++) {
                    long offset = maxOffset <= 0 ? 0 : nextLong(random, maxOffset + 1);
                    long start = System.nanoTime();
                    reader.read(offset, randomReadLength);
                    readNanos += System.nanoTime() - start;
                    reads++;
                }
                long indexBytes = (long) reader.chunkCount() * 24 + 32;
                return new Result(chunkSize, data.length, compressedSize, indexBytes,
                        reader.chunkCount(), writeNanos, readNanos, reads,
                        countingCodec.decompressedBytes.get());
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static long nextLong(Random random, long bound) {
        long candidate = random.nextLong();
        if (candidate == Long.MIN_VALUE) {
            candidate = 0;
        }
        return Math.floorMod(candidate, bound);
    }

    /** Deflate codec that counts how many uncompressed bytes it produced. */
    static final class CountingDeflateCodec extends DeflateCodec {
        final AtomicLong decompressedBytes = new AtomicLong();

        @Override
        public byte[] decompress(byte[] compressed, int expectedLength) throws IOException {
            byte[] output = super.decompress(compressed, expectedLength);
            decompressedBytes.addAndGet(output.length);
            return output;
        }
    }

    /** Small CLI: prints a tradeoff table for a synthetic compressible payload. */
    public static void main(String[] args) throws IOException {
        int size = args.length > 0 ? Integer.parseInt(args[0]) : 4 * 1024 * 1024;
        byte[] data = syntheticPayload(size);
        int[] chunkSizes = {4 * 1024, 16 * 1024, 64 * 1024, 256 * 1024, 1024 * 1024};
        List<Result> results = evaluate(data, chunkSizes, 4096, 200);
        System.out.println("payload=" + size + "B, random reads=200 x 4096B (cold cache)");
        results.forEach(System.out::println);
    }

    private static byte[] syntheticPayload(int size) {
        String[] words = {"alpha", "bravo", "charlie", "delta", "echo", "foxtrot",
                "compression", "random", "access", "chunk", "archive", "index"};
        StringBuilder builder = new StringBuilder(size + 64);
        Random random = new Random(7);
        while (builder.length() < size) {
            builder.append(words[random.nextInt(words.length)]).append(' ');
            if (random.nextInt(12) == 0) {
                builder.append(System.lineSeparator());
            }
        }
        byte[] full = builder.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] data = new byte[size];
        System.arraycopy(full, 0, data, 0, size);
        return data;
    }
}
