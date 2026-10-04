package com.example.gsb.blockio;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkCorruptionTest {

    private static final int CHUNK = 256;
    private static final int CHUNK_HEADER = 20;

    private Path archive;

    @AfterEach
    void deleteArchive() throws IOException {
        if (archive != null) {
            Files.deleteIfExists(archive);
        }
    }

    private byte[] writeArchive(int chunkCount) throws IOException {
        byte[] data = new byte[CHUNK * chunkCount];
        new Random(9).nextBytes(data);
        archive = Files.createTempFile("chunk-corrupt-", ".bin");
        try (ChunkArchiveWriter writer =
                     new ChunkArchiveWriter(Files.newOutputStream(archive), CHUNK)) {
            writer.write(data);
        }
        return data;
    }

    private void flipByteInsideChunkPayload(ChunkEntry entry) throws IOException {
        long position = entry.compressedOffset() + CHUNK_HEADER + entry.compressedLength() / 2;
        try (FileChannel channel = FileChannel.open(archive,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            java.nio.ByteBuffer one = java.nio.ByteBuffer.allocate(1);
            channel.read(one, position);
            one.flip();
            one.put(0, (byte) (one.get(0) ^ 0xFF));
            channel.write(one, position);
        }
    }

    @Test
    void corruptChunkIsLocatedByIndexAndFailsLoudly() throws IOException {
        byte[] data = writeArchive(8);
        int corruptChunk = 3;

        ChunkEntry entry;
        try (ChunkArchiveReader reader = ChunkArchiveReader.open(archive)) {
            entry = reader.index().entries().get(corruptChunk);
        }
        flipByteInsideChunkPayload(entry);

        try (ChunkArchiveReader reader = ChunkArchiveReader.open(archive)) {
            assertThatThrownBy(() -> reader.read((long) corruptChunk * CHUNK, CHUNK))
                    .isInstanceOfSatisfying(ChunkCorruptedException.class, e -> {
                        assertThat(e.chunkIndex()).isEqualTo(corruptChunk);
                        assertThat(e.getMessage()).contains("chunk " + corruptChunk);
                    });

            // A range merely overlapping the corrupt chunk must fail too.
            assertThatThrownBy(() -> reader.read((long) corruptChunk * CHUNK - 1, 2))
                    .isInstanceOf(ChunkCorruptedException.class);

            // Untouched chunks remain fully readable.
            assertThat(reader.read(0, CHUNK))
                    .isEqualTo(java.util.Arrays.copyOfRange(data, 0, CHUNK));
            assertThat(reader.read((long) 5 * CHUNK, CHUNK))
                    .isEqualTo(java.util.Arrays.copyOfRange(data, 5 * CHUNK, 6 * CHUNK));
        }
    }

    @Test
    void corruptingOneChunkDoesNotBreakNeighbourChunks() throws IOException {
        byte[] data = writeArchive(4);
        ChunkEntry entry;
        try (ChunkArchiveReader reader = ChunkArchiveReader.open(archive)) {
            entry = reader.index().entries().get(1);
        }
        flipByteInsideChunkPayload(entry);

        try (ChunkArchiveReader reader = ChunkArchiveReader.open(archive)) {
            assertThat(reader.read(0, CHUNK))
                    .isEqualTo(java.util.Arrays.copyOfRange(data, 0, CHUNK));
            assertThat(reader.read(2L * CHUNK, 2 * CHUNK))
                    .isEqualTo(java.util.Arrays.copyOfRange(data, 2 * CHUNK, 4 * CHUNK));
            assertThatThrownBy(() -> reader.read(0, 4 * CHUNK))
                    .isInstanceOfSatisfying(ChunkCorruptedException.class,
                            e -> assertThat(e.chunkIndex()).isEqualTo(1));
        }
    }
}
