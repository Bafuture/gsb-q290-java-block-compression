package com.example.gsb.blockio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkArchiveRoundTripTest {

    private static final int CHUNK = 64;

    private Path archive;

    private Path writeArchive(byte[] data, int chunkSize) throws IOException {
        archive = Files.createTempFile("chunk-roundtrip-", ".bin");
        try (ChunkArchiveWriter writer =
                     new ChunkArchiveWriter(Files.newOutputStream(archive), chunkSize)) {
            writer.write(data);
        }
        return archive;
    }

    @AfterEach
    void deleteArchive() throws IOException {
        if (archive != null) {
            Files.deleteIfExists(archive);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 31, 32, 33, 64, 95, 96, 97, 1000})
    void roundTripsAnySizeIncludingChunkBoundaries(int size) throws IOException {
        byte[] data = deterministicData(size, 1);
        writeArchive(data, CHUNK);

        try (ChunkArchiveReader reader = ChunkArchiveReader.open(archive)) {
            assertThat(reader.originalSize()).isEqualTo(size);
            assertThat(reader.chunkSize()).isEqualTo(CHUNK);
            assertThat(reader.chunkCount()).isEqualTo((size + CHUNK - 1) / CHUNK);
            assertThat(reader.read(0, size)).isEqualTo(data);
        }
    }

    @Test
    void indexRecordsOffsetsLengthsAndChecksumsOfEveryChunk() throws IOException {
        int chunkSize = 64;
        byte[] data = deterministicData(chunkSize * 3 + 10, 99);
        List<ChunkEntry> written = writeAndIndex(data, chunkSize);
        assertThat(written).hasSize(4);
        assertThat(written.get(0).compressedOffset()).isZero();
        for (int i = 0; i < written.size(); i++) {
            ChunkEntry entry = written.get(i);
            int start = i * chunkSize;
            int length = Math.min(chunkSize, data.length - start);
            assertThat(entry.originalLength()).isEqualTo(length);
            assertThat(entry.crc32()).isEqualTo(crc(data, start, length));
            if (i > 0) {
                ChunkEntry previous = written.get(i - 1);
                assertThat(entry.compressedOffset()).isEqualTo(
                        previous.compressedOffset() + 20 + previous.compressedLength());
            }
        }

        try (ChunkArchiveReader reader = ChunkArchiveReader.open(archive)) {
            List<ChunkEntry> parsed = reader.index().entries();
            assertThat(parsed).isEqualTo(written);
        }
    }

    private List<ChunkEntry> writeAndIndex(byte[] data, int chunkSize) throws IOException {
        Path path = Files.createTempFile("chunk-index-", ".bin");
        Files.deleteIfExists(path);
        archive = path;
        ChunkArchiveWriter writer =
                new ChunkArchiveWriter(Files.newOutputStream(path), chunkSize);
        writer.write(data);
        assertThat(writer.originalSize()).isEqualTo(data.length);
        writer.close();
        return writer.index();
    }

    @Test
    void randomReadsOfArbitraryRangesReturnExactlyTheRequestedSlice() throws IOException {
        int chunkSize = 4096;
        byte[] data = deterministicData(200_000, 17);
        writeArchive(data, chunkSize);

        Random random = new Random(1234);
        try (ChunkArchiveReader reader = ChunkArchiveReader.open(archive)) {
            for (int i = 0; i < 300; i++) {
                int length = 1 + random.nextInt(3 * chunkSize);
                int offset = random.nextInt(data.length - length + 1);
                byte[] expected = new byte[length];
                System.arraycopy(data, offset, expected, 0, length);
                assertThat(reader.read(offset, length)).as("read %d @ %d", length, offset)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void crossChunkReadsStitchBoundariesWithoutOverlapOrGap() throws IOException {
        int chunkSize = 64;
        byte[] data = deterministicData(chunkSize * 5, 5);
        writeArchive(data, chunkSize);

        try (ChunkArchiveReader reader = ChunkArchiveReader.open(archive)) {
            assertSlice(reader, data, 0, chunkSize * 5);
            assertSlice(reader, data, chunkSize - 1, 2);
            assertSlice(reader, data, chunkSize - 1, chunkSize + 2);
            assertSlice(reader, data, chunkSize, chunkSize);
            assertSlice(reader, data, chunkSize + 10, 2 * chunkSize);
            assertSlice(reader, data, 2 * chunkSize - 3, 2 * chunkSize + 6);
            assertSlice(reader, data, chunkSize * 4, chunkSize);
        }
    }

    @Test
    void readIntoExistingBufferReusesCallerMemory() throws IOException {
        int chunkSize = 32;
        byte[] data = deterministicData(chunkSize * 3, 3);
        writeArchive(data, chunkSize);

        try (ChunkArchiveReader reader = ChunkArchiveReader.open(archive)) {
            byte[] dst = new byte[70];
            reader.read(20, dst, 10, 50);
            assertThat(java.util.Arrays.copyOfRange(dst, 10, 60))
                    .isEqualTo(java.util.Arrays.copyOfRange(data, 20, 70));
        }
    }

    @Test
    void indexIsGeneratedWhileStreamingAndFileIsReadableImmediatelyAfterClose()
            throws IOException {
        int chunkSize = 32;
        archive = Files.createTempFile("chunk-stream-", ".bin");
        ChunkArchiveWriter writer =
                new ChunkArchiveWriter(Files.newOutputStream(archive), chunkSize);
        writer.write(new byte[30]);
        assertThat(writer.chunkCount()).isZero();
        writer.write(new byte[40]);
        assertThat(writer.chunkCount()).isEqualTo(2);
        assertThat(writer.index()).hasSize(2);
        writer.write(new byte[30]);
        writer.close();

        byte[] all = new byte[100];
        try (ChunkArchiveReader reader = ChunkArchiveReader.open(archive)) {
            assertThat(reader.chunkCount()).isEqualTo(4);
            assertThat(reader.read(0, 100)).isEqualTo(all);
            assertThat(reader.read(60, 10)).isEqualTo(new byte[10]);
        }
    }

    @Test
    void emptyArchiveIsValidAndReadable() throws IOException {
        writeArchive(new byte[0], CHUNK);
        try (ChunkArchiveReader reader = ChunkArchiveReader.open(archive)) {
            assertThat(reader.originalSize()).isZero();
            assertThat(reader.chunkCount()).isZero();
            assertThat(reader.read(0, 0)).isEmpty();
        }
    }

    @Test
    void onlyChunksOverlappingTheRangeAreDecompressed() throws IOException {
        int chunkSize = 256;
        byte[] data = deterministicData(chunkSize * 8, 42);
        Path path = writeArchive(data, chunkSize);

        TestCountingCodec codec = new TestCountingCodec();
        try (ChunkArchiveReader reader = ChunkArchiveReader.open(path, codec)) {
            reader.read(chunkSize * 2 + 10, 5);
            assertThat(codec.decompressCalls).hasValue(1);
            assertThat(codec.decompressedBytes).hasValue(chunkSize);

            reader.read(chunkSize * 4L, chunkSize * 3);
            assertThat(codec.decompressCalls).hasValue(4);
            assertThat(codec.decompressedBytes).hasValue((long) chunkSize * 4);
        }
    }

    @Test
    void repeatedReadsOfSameChunkAreServedFromCache() throws IOException {
        int chunkSize = 128;
        byte[] data = deterministicData(chunkSize * 4, 4);
        TestCountingCodec codec = new TestCountingCodec();
        try (ChunkArchiveReader reader =
                     ChunkArchiveReader.open(writeArchive(data, chunkSize), codec)) {
            for (int i = 0; i < 10; i++) {
                assertThat(reader.read(chunkSize + 5, 20)).hasSize(20);
            }
            assertThat(codec.decompressCalls).hasValue(1);
        }
    }

    @Test
    void outOfRangeReadsAreRejected() throws IOException {
        byte[] data = deterministicData(200, 1);
        try (ChunkArchiveReader reader = ChunkArchiveReader.open(writeArchive(data, CHUNK))) {
            assertThatThrownBy(() -> reader.read(201, 1))
                    .isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> reader.read(199, 2))
                    .isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> reader.read(-1, 1))
                    .isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Test
    void rejectsFileWithoutFooter() throws IOException {
        archive = Files.createTempFile("chunk-bad-", ".bin");
        Files.write(archive, new byte[11]);
        assertThatThrownBy(() -> ChunkArchiveReader.open(archive))
                .isInstanceOf(IOException.class);
    }

    private static void assertSlice(ChunkArchiveReader reader, byte[] data,
                                    long offset, int length) throws IOException {
        byte[] expected = new byte[length];
        System.arraycopy(data, (int) offset, expected, 0, length);
        assertThat(reader.read(offset, length))
                .as("slice %d bytes @ %d", length, offset)
                .isEqualTo(expected);
    }

    private static long crc(byte[] data, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, offset, length);
        return crc.getValue();
    }

    private static byte[] deterministicData(int size, int seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }
}
