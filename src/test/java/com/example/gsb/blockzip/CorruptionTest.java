package com.example.gsb.blockzip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 需求 4：每块 CRC-32 校验和，损坏块可定位、报错明确。 */
class CorruptionTest {

    @TempDir
    Path dir;

    @Test
    void detectsCorruptBlockByChecksumAndIdentifiesBlockIndex() throws Exception {
        int blockSize = 512;
        byte[] data = TestData.semiCompressible(5_000, 3);
        Path file = dir.resolve("data.blkz");
        try (BlockCompressedWriter writer = new BlockCompressedWriter(file, blockSize)) {
            writer.write(data);
        }

        BlockIndexEntry victim;
        try (BlockCompressedReader reader = new BlockCompressedReader(file)) {
            victim = reader.index().get(2);
        }

        // 在第 2 块的压缩数据中间翻转一个字节
        long corruptOffset = victim.compressedOffset() + victim.compressedLength() / 2;
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.allocate(1);
            ch.read(buf, corruptOffset);
            buf.flip();
            buf.put(0, (byte) (buf.get(0) ^ 0xFF));
            ch.write(buf, corruptOffset);
        }

        try (BlockCompressedReader reader = new BlockCompressedReader(file)) {
            // 读损坏块：报 CorruptBlockException，并指出是第 2 块
            assertThatThrownBy(() -> reader.read(2 * blockSize, 10))
                    .isInstanceOf(CorruptBlockException.class)
                    .hasMessageContaining("2");
            // 损坏块之前、之后的块仍然可以正常读取
            assertThat(reader.read(0, blockSize))
                    .isEqualTo(java.util.Arrays.copyOfRange(data, 0, blockSize));
            assertThat(reader.read(3 * blockSize, blockSize))
                    .isEqualTo(java.util.Arrays.copyOfRange(data, 3 * blockSize, 4 * blockSize));
            // 跨损坏块的范围同样失败（但不影响其他范围的后续读取）
            assertThatThrownBy(() -> reader.read(2 * blockSize + 1, 3 * blockSize))
                    .isInstanceOf(CorruptBlockException.class);
        }
    }

    @Test
    void rejectsTruncatedFileWithClearError() throws Exception {
        byte[] data = TestData.semiCompressible(10_000, 5);
        Path file = dir.resolve("trunc.blkz");
        try (BlockCompressedWriter writer = new BlockCompressedWriter(file, 1024)) {
            writer.write(data);
        }
        long size = java.nio.file.Files.size(file);
        // 截掉文件尾（含索引区的一部分），打开时应明确报错而不是静默出错
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.truncate(size - BlockCompressedWriter.TRAILER_SIZE - 10);
        }
        assertThatThrownBy(() -> new BlockCompressedReader(file))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("文件尾魔数");
    }

    @Test
    void rejectsNonBlockCompressedFile() throws Exception {
        Path file = dir.resolve("plain.bin");
        java.nio.file.Files.write(file, new byte[4096]);
        assertThatThrownBy(() -> new BlockCompressedReader(file))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("魔数");
    }
}
