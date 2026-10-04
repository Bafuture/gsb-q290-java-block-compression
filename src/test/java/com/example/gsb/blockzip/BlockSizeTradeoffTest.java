package com.example.gsb.blockzip;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.io.TempDir;

/**
 * 需求 6：块大小对压缩率与随机读取耗时的权衡。
 *
 * <p>对同一份半可压缩数据用不同块大小压缩，测量：
 * <ul>
 *   <li>压缩率（压缩后文件大小 / 原始大小）——块越大，DEFLATE 可利用的
 *       上下文越多，压缩率通常越好；</li>
 *   <li>随机读取耗时（固定 4 KiB 随机区间读取的平均耗时，缓存容量设为 1
 *       以强制每次真正解压）——块越大，每次读取需要解压的无关数据越多，
 *       耗时越长。</li>
 * </ul>
 * 结果以表格形式打印到标准输出，同时校验所有随机读取结果正确。
 */
class BlockSizeTradeoffTest {

    private static final int DATA_SIZE = 4 << 20; // 4 MiB
    private static final int READ_LENGTH = 4 << 10; // 4 KiB
    private static final int SAMPLES = 300;
    private static final int[] BLOCK_SIZES = {
            4 << 10, 16 << 10, 64 << 10, 256 << 10, 1 << 20};

    @TempDir
    Path dir;

    @Test
    void measuresCompressionRatioAndRandomReadLatencyPerBlockSize() throws Exception {
        byte[] data = TestData.semiCompressible(DATA_SIZE, 2024);
        Random random = new Random(7);
        int[][] requests = new int[SAMPLES][2];
        for (int i = 0; i < SAMPLES; i++) {
            int offset = random.nextInt(DATA_SIZE - READ_LENGTH);
            requests[i] = new int[]{offset, READ_LENGTH};
        }

        System.out.println();
        System.out.println("块大小权衡（数据 4 MiB 半可压缩文本，随机读 4 KiB × " + SAMPLES + " 次）");
        System.out.printf("%-12s %12s %12s %14s %16s%n",
                "块大小", "压缩后大小", "压缩率", "随机读均耗时", "解压块数/读");
        double previousRatio = Double.MAX_VALUE;
        double previousLatency = 0;
        for (int blockSize : BLOCK_SIZES) {
            Path file = dir.resolve("tradeoff-" + blockSize + ".blkz");
            try (BlockCompressedWriter writer = new BlockCompressedWriter(file, blockSize)) {
                writer.write(data);
            }
            long compressedSize = Files.size(file);
            double ratio = (double) compressedSize / DATA_SIZE;

            // 缓存容量 1：每次读取都真实解压，测出纯随机读耗时
            long latencyNanos;
            try (BlockCompressedReader reader = new BlockCompressedReader(file, 1)) {
                for (int[] req : requests) { // 预热 JIT
                    reader.read(req[0], req[1]);
                }
                long start = System.nanoTime();
                for (int[] req : requests) {
                    byte[] got = reader.read(req[0], req[1]);
                    assertThat(got).isEqualTo(
                            Arrays.copyOfRange(data, req[0], req[0] + req[1]));
                }
                latencyNanos = (System.nanoTime() - start) / SAMPLES;
            }
            double blocksPerRead = (double) READ_LENGTH / blockSize + 1;
            System.out.printf("%-12s %,12d %11.1f%% %12.1f µs %16.2f%n",
                    formatSize(blockSize), compressedSize, ratio * 100,
                    latencyNanos / 1000.0, blocksPerRead);

            // 趋势断言：块越大压缩率不显著变差；块越大随机读更慢（允许小样本噪声）
            assertThat(ratio).isLessThanOrEqualTo(previousRatio * 1.05);
            if (previousLatency > 0) {
                assertThat(latencyNanos).isGreaterThan((long) (previousLatency * 0.5));
            }
            previousRatio = ratio;
            previousLatency = latencyNanos;
        }
        System.out.println();
    }

    private static String formatSize(int bytes) {
        return bytes >= (1 << 20) ? (bytes >> 20) + " MiB" : (bytes >> 10) + " KiB";
    }
}
