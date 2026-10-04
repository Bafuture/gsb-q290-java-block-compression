package com.example.gsb.blockzip;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 需求 5：并发读取——不同块互不阻塞，同一块只解压一次。 */
class ConcurrentReadTest {

    private static final int BLOCK_SIZE = 1024;
    private static final int DATA_SIZE = 300_000;

    @TempDir
    Path dir;

    private byte[] data;
    private Path file;

    @BeforeEach
    void setUp() throws Exception {
        data = TestData.semiCompressible(DATA_SIZE, 11);
        file = dir.resolve("data.blkz");
        try (BlockCompressedWriter writer = new BlockCompressedWriter(file, BLOCK_SIZE)) {
            writer.write(data);
        }
    }

    @Test
    void concurrentRandomReadsAreAllCorrect() throws Exception {
        int threads = 16;
        int readsPerThread = 200;
        // 缓存容量覆盖全部块：验证缓存存活期间每块至多解压一次
        try (BlockCompressedReader reader = new BlockCompressedReader(file, 1024)) {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                long seed = t;
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        Random random = new Random(seed);
                        for (int i = 0; i < readsPerThread; i++) {
                            int offset = random.nextInt(DATA_SIZE - 1);
                            int length = 1 + random.nextInt(Math.min(8 * BLOCK_SIZE, DATA_SIZE - offset));
                            byte[] expected = Arrays.copyOfRange(data, offset, offset + length);
                            byte[] actual = reader.read(offset, length);
                            if (!Arrays.equals(expected, actual)) {
                                throw new AssertionError("读取结果不一致: offset=" + offset + " length=" + length);
                            }
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
            pool.shutdown();
            // 解压次数不应超过块总数（每块至多解压一次）
            int totalBlocks = reader.blockCount();
            assertThat(reader.decompressionCount.get()).isLessThanOrEqualTo(totalBlocks);
        }
    }

    @Test
    void concurrentReadsOfSameBlockDecompressOnlyOnce() throws Exception {
        int threads = 32;
        // 缓存容量覆盖全部块：验证缓存存活期间每块至多解压一次
        try (BlockCompressedReader reader = new BlockCompressedReader(file, 1024)) {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<byte[]>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    // 所有线程同时读同一个块内的同一范围
                    return reader.read(7 * BLOCK_SIZE + 13, 500);
                }));
            }
            ready.await();
            go.countDown();
            byte[] expected = Arrays.copyOfRange(data, 7 * BLOCK_SIZE + 13, 7 * BLOCK_SIZE + 513);
            for (Future<byte[]> f : futures) {
                assertThat(f.get(30, TimeUnit.SECONDS)).isEqualTo(expected);
            }
            pool.shutdown();
            // 32 个线程同时请求同一块，只解压了一次
            assertThat(reader.decompressionCount.get()).isEqualTo(1);
        }
    }

    @Test
    void readsFromIndependentReadersDoNotInterfere() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            long seed = 1000 + t;
            futures.add(pool.submit(() -> {
                try (BlockCompressedReader reader = new BlockCompressedReader(file)) {
                    go.await();
                    Random random = new Random(seed);
                    for (int i = 0; i < 100; i++) {
                        int offset = random.nextInt(DATA_SIZE - 1);
                        int length = 1 + random.nextInt(DATA_SIZE - offset);
                        if (!Arrays.equals(reader.read(offset, length),
                                Arrays.copyOfRange(data, offset, offset + length))) {
                            throw new AssertionError("读取结果不一致");
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        go.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
    }
}
