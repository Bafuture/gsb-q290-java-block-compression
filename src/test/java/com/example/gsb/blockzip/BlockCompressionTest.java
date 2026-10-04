package com.example.gsb.blockzip;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 需求 1、7：分块压缩、索引生成、流式写入后立即可读。 */
class BlockCompressionTest {

    @TempDir
    Path dir;

    @Test
    void splitsDataIntoFixedSizeBlocksAndBuildsIndex() throws Exception {
        int blockSize = 1024;
        byte[] data = TestData.semiCompressible(10_000, 42);
        Path file = dir.resolve("data.blkz");

        try (BlockCompressedWriter writer = new BlockCompressedWriter(file, blockSize)) {
            writer.write(data);
        }

        try (BlockCompressedReader reader = new BlockCompressedReader(file)) {
            assertThat(reader.blockSize()).isEqualTo(blockSize);
            assertThat(reader.originalSize()).isEqualTo(data.length);
            // ceil(10000 / 1024) = 10 块
            assertThat(reader.blockCount()).isEqualTo(10);

            List<BlockIndexEntry> index = reader.index();
            // 每块原始长度：前 9 块满块，最后一块为余数
            for (int i = 0; i < 9; i++) {
                assertThat(index.get(i).originalLength()).isEqualTo(blockSize);
            }
            assertThat(index.get(9).originalLength()).isEqualTo(10_000 - 9 * 1024);
            // 压缩偏移从头部之后开始，且严格衔接（偏移 = 前一块偏移 + 前一块压缩长度）
            assertThat(index.get(0).compressedOffset()).isEqualTo(BlockCompressedWriter.HEADER_SIZE);
            for (int i = 1; i < index.size(); i++) {
                assertThat(index.get(i).compressedOffset())
                        .isEqualTo(index.get(i - 1).compressedOffset() + index.get(i - 1).compressedLength());
            }
            // 半可压缩数据每块压缩后应明显变小
            for (BlockIndexEntry e : index) {
                assertThat(e.compressedLength()).isLessThan(e.originalLength());
            }
        }
    }

    @Test
    void compressesHighlyCompressibleDataToMuchSmallerFile() throws Exception {
        byte[] data = TestData.highlyCompressible(1 << 20);
        Path file = dir.resolve("dense.blkz");
        try (BlockCompressedWriter writer = new BlockCompressedWriter(file, 64 * 1024)) {
            writer.write(data);
        }
        assertThat(java.nio.file.Files.size(file)).isLessThan(data.length / 10);
    }

    @Test
    void streamsWritesOfArbitraryChunkSizesAndIsReadableImmediatelyAfterClose() throws Exception {
        byte[] data = TestData.semiCompressible(200_000, 7);
        Path file = dir.resolve("stream.blkz");

        // 以不规则的小块流式写入（含逐字节写入一段），模拟边产生边写
        BlockCompressedWriter writer = new BlockCompressedWriter(file, 4096);
        int pos = 0;
        int[] chunks = {1, 3, 100, 4097, 65536, 2, 8191};
        int ci = 0;
        while (pos < data.length) {
            int n = Math.min(chunks[ci++ % chunks.length], data.length - pos);
            if (n == 1) {
                writer.write(data[pos]);
            } else {
                writer.write(data, pos, n);
            }
            pos += n;
        }
        writer.close();

        // close 之后立即可随机读取
        try (BlockCompressedReader reader = new BlockCompressedReader(file)) {
            assertThat(reader.originalSize()).isEqualTo(data.length);
            assertThat(reader.read(0, data.length)).isEqualTo(data);
            assertThat(reader.read(123_456, 7_890))
                    .isEqualTo(java.util.Arrays.copyOfRange(data, 123_456, 123_456 + 7_890));
        }
    }

    @Test
    void supportsEmptyAndSingleByteFiles() throws Exception {
        Path empty = dir.resolve("empty.blkz");
        try (BlockCompressedWriter w = new BlockCompressedWriter(empty, 1024)) {
            // 不写任何数据
        }
        try (BlockCompressedReader reader = new BlockCompressedReader(empty)) {
            assertThat(reader.originalSize()).isZero();
            assertThat(reader.blockCount()).isZero();
            assertThat(reader.read(0, 0)).isEmpty();
        }

        Path one = dir.resolve("one.blkz");
        try (BlockCompressedWriter w = new BlockCompressedWriter(one, 1024)) {
            w.write(0xAB);
        }
        try (BlockCompressedReader reader = new BlockCompressedReader(one)) {
            assertThat(reader.originalSize()).isOne();
            assertThat(reader.blockCount()).isOne();
            assertThat(reader.read(0, 1)).containsExactly((byte) 0xAB);
        }
    }

    @Test
    void rejectsInvalidBlockSize() {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new BlockCompressedWriter(dir.resolve("x.blkz"), 0));
    }
}
