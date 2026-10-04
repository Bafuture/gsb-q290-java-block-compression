package com.example.gsb.blockio;

/**
 * On-disk layout constants for a chunk-compressed archive.
 *
 * <pre>
 *   [ chunk record ] * chunkCount
 *   [ index entries ]
 *   [ footer ]
 *
 * chunk record:
 *   u32 magic = {@code 'CHNK'}
 *   u32 compressed length
 *   u32 original length
 *   u64 CRC32 of the original (uncompressed) chunk bytes
 *   compressed payload
 *
 * index entry (24 bytes):
 *   i64 compressed offset
 *   i32 compressed length
 *   i32 original length
 *   u64 CRC32 of the original chunk bytes
 *
 * footer (32 bytes, last bytes of the file):
 *   i64 index offset
 *   i32 chunk count
 *   i32 configured chunk size
 *   i64 total original size
 *   i32 codec id
 *   u32 magic = {@code 'BLKA'}
 * </pre>
 *
 * All multi-byte integers are big-endian.
 */
final class ArchiveFormat {

    static final int CHUNK_MAGIC = 0x43484E4B;
    static final int FOOTER_MAGIC = 0x424C4B41;

    static final int CHUNK_HEADER_SIZE = 4 + 4 + 4 + 8;
    static final int INDEX_ENTRY_SIZE = 8 + 4 + 4 + 8;
    static final int FOOTER_SIZE = 8 + 4 + 4 + 8 + 4 + 4;

    private ArchiveFormat() {
    }
}
