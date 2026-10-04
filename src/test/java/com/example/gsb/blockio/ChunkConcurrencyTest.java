package com.example.gsb.blockio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkConcurrencyTest {

    private static final int CHUNK = 1024;

    private Path archive;

    @AfterEach
    void deleteArchive() throws IOException {
        if (archive != null) {
            Files.deleteIfExists(archive);
        }
    }

    private byte[] writeArchive(int chunkCount) throws IOException {
        byte[] data = new byte[CHUNK * chunkCount];
        new Random(31).nextBytes(data);
        archive = Files.createTempFile("chunk-concurrent-", ".bin");
        try (ChunkArchiveWriter writer =
                     new ChunkArchiveWriter(Files.newOutputStream(archive), CHUNK)) {
            writer.write(data);
        }
        return data;
    }

    @Test
    void concurrentRequestsForSameChunkInflateItExactlyOnce() throws Exception {
        byte[] data = writeArchive(4);
        TestCountingCodec codec = new TestCountingCodec();
        int threads = 16;

        try (ChunkArchiveReader reader = ChunkArchiveReader.open(archive, codec)) {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<byte[]>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return reader.read(CHUNK + 7, 100);
                }));
            }
            ready.await();
            go.countDown();

            byte[] expected = new byte[100];
            System.arraycopy(data, CHUNK + 7, expected, 0, 100);
            for (Future<byte[]> future : futures) {
                assertThat(future.get(30, TimeUnit.SECONDS)).isEqualTo(expected);
            }
            pool.shutdown();
            assertThat(codec.decompressCalls).hasValue(1);
        }
    }

    @Test
    void manyThreadsReadingDifferentChunksAllGetCorrectData() throws Exception {
        int chunkCount = 32;
        byte[] data = writeArchive(chunkCount);
        TestCountingCodec codec = new TestCountingCodec();

        try (ChunkArchiveReader reader = ChunkArchiveReader.open(archive, codec)) {
            ExecutorService pool = Executors.newFixedThreadPool(8);
            List<Future<byte[]>> futures = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                int chunk = i % chunkCount;
                futures.add(pool.submit(() -> reader.read((long) chunk * CHUNK, CHUNK)));
            }
            for (int i = 0; i < futures.size(); i++) {
                int chunk = i % chunkCount;
                byte[] expected = new byte[CHUNK];
                System.arraycopy(data, chunk * CHUNK, expected, 0, CHUNK);
                assertThat(futures.get(i).get(30, TimeUnit.SECONDS)).isEqualTo(expected);
            }
            pool.shutdown();
            // Each distinct chunk is decompressed at most once across all threads.
            assertThat(codec.decompressCalls).hasValue(chunkCount);
        }
    }

    @Test
    void readsOfDifferentChunksProceedInParallelWithoutBlocking() throws Exception {
        int chunkCount = 8;
        writeArchive(chunkCount);
        int delayMillis = 100;
        DeflateCodec slowCodec = new DeflateCodec() {
            @Override
            public byte[] decompress(byte[] compressed, int expectedLength) throws IOException {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.decompress(compressed, expectedLength);
            }
        };

        try (ChunkArchiveReader reader = ChunkArchiveReader.open(archive, slowCodec)) {
            ExecutorService pool = Executors.newFixedThreadPool(chunkCount);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<byte[]>> futures = new ArrayList<>();
            for (int i = 0; i < chunkCount; i++) {
                int chunk = i;
                futures.add(pool.submit(() -> {
                    go.await();
                    return reader.read((long) chunk * CHUNK, CHUNK);
                }));
            }
            long start = System.nanoTime();
            go.countDown();
            for (Future<byte[]> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            pool.shutdown();

            // Sequential inflation would take >= chunkCount * delayMillis.
            assertThat(elapsedMillis)
                    .as("parallel reads of %d chunks", chunkCount)
                    .isLessThan((long) chunkCount * delayMillis / 2);
        }
    }
}
