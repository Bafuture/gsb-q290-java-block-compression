package com.example.gsb.blockzip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 需求 2、3：随机访问只解压相关块；跨块读取正确拼接。 */
class RandomAccessTest {

    private static final int BLOCK_SIZE = 1024;
    private static final int DATA_SIZE = 100_000;

    @TempDir
    Path dir;

    private byte[] data;
    private Path file;

    @BeforeEach
    void setUp() throws Exception {
        data = TestData.semiCompressible(DATA_SIZE, 99);
        file = dir.resolve("data.blkz");
        try (BlockCompressedWriter writer = new BlockCompressedWriter(file, BLOCK_SIZE)) {
            writer.write(data);
        }
    }

    @Test
    void readsSingleBlockInteriorRange() throws Exception {
        try (BlockCompressedReader reader = new BlockCompressedReader(file)) {
            assertThat(reader.read(100, 500)).isEqualTo(Arrays.copyOfRange(data, 100, 600));
        }
    }

    @Test
    void readsAcrossBlockBoundaryWithoutDuplicationOrOmission() throws Exception {
        try (BlockCompressedReader reader = new BlockCompressedReader(file)) {
            // 跨越 1 个边界：块 0 末尾 4 字节 + 块 1 开头 4 字节
            assertThat(reader.read(BLOCK_SIZE - 4, 8))
                    .isEqualTo(Arrays.copyOfRange(data, BLOCK_SIZE - 4, BLOCK_SIZE + 4));
            // 跨越多个边界
            assertThat(reader.read(BLOCK_SIZE - 1, 3 * BLOCK_SIZE + 2))
                    .isEqualTo(Arrays.copyOfRange(data, BLOCK_SIZE - 1, 4 * BLOCK_SIZE + 1));
        }
    }

    @Test
    void readsWholeFileAndTailPartialBlock() throws Exception {
        try (BlockCompressedReader reader = new BlockCompressedReader(file)) {
            assertThat(reader.read(0, DATA_SIZE)).isEqualTo(data);
            int tailStart = DATA_SIZE - 100;
            assertThat(reader.read(tailStart, 100))
                    .isEqualTo(Arrays.copyOfRange(data, tailStart, DATA_SIZE));
        }
    }

    @Test
    void randomRangesMatchOriginalData() throws Exception {
        Random random = new Random(123);
        try (BlockCompressedReader reader = new BlockCompressedReader(file)) {
            for (int i = 0; i < 500; i++) {
                int offset = random.nextInt(DATA_SIZE);
                int length = random.nextInt(DATA_SIZE - offset + 1);
                assertThat(reader.read(offset, length))
                        .isEqualTo(Arrays.copyOfRange(data, offset, offset + length));
            }
        }
    }

    @Test
    void decompressesOnlyIntersectingBlocks() throws Exception {
        try (BlockCompressedReader reader = new BlockCompressedReader(file)) {
            // 读取完全落在块 5 内部的范围：只解压 1 个块，而不是全部 ~98 块
            reader.read(5 * BLOCK_SIZE + 10, 100);
            assertThat(reader.decompressionCount.get()).isEqualTo(1);

            // 范围 [10239, 12289) 覆盖块 9、10、11、12：再解压 4 个块
            reader.read(10 * BLOCK_SIZE - 1, 2 * BLOCK_SIZE + 2);
            assertThat(reader.decompressionCount.get()).isEqualTo(1 + 4);

            // 重复读取命中缓存，不再解压
            reader.read(5 * BLOCK_SIZE + 10, 100);
            assertThat(reader.decompressionCount.get()).isEqualTo(5);
        }
    }

    @Test
    void rejectsOutOfRangeRequests() throws Exception {
        try (BlockCompressedReader reader = new BlockCompressedReader(file)) {
            assertThatThrownBy(() -> reader.read(-1, 10)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> reader.read(0, -5)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> reader.read(DATA_SIZE - 10, 11))
                    .isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> reader.read(DATA_SIZE + 1, 0))
                    .isInstanceOf(IndexOutOfBoundsException.class);
        }
    }
}
