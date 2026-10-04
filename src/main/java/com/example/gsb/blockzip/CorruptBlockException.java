package com.example.gsb.blockzip;

import java.io.IOException;

/**
 * 某个压缩块损坏（无法解压或 CRC-32 校验和不匹配）时抛出。
 * 异常中携带块序号，便于定位损坏位置。
 */
public class CorruptBlockException extends IOException {

    private final int blockIndex;
    private final Long expectedCrc32;
    private final Long actualCrc32;

    public CorruptBlockException(int blockIndex, String message, Throwable cause) {
        super("块 " + blockIndex + " 损坏: " + message, cause);
        this.blockIndex = blockIndex;
        this.expectedCrc32 = null;
        this.actualCrc32 = null;
    }

    public CorruptBlockException(int blockIndex, long expectedCrc32, long actualCrc32) {
        super("块 " + blockIndex + " 校验和不匹配: 期望 CRC-32=0x"
                + Long.toHexString(expectedCrc32) + ", 实际 CRC-32=0x" + Long.toHexString(actualCrc32));
        this.blockIndex = blockIndex;
        this.expectedCrc32 = expectedCrc32;
        this.actualCrc32 = actualCrc32;
    }

    /** 损坏块的序号（从 0 开始）。 */
    public int blockIndex() {
        return blockIndex;
    }

    /** 索引中记录的期望校验和；解压失败导致无法校验时为 null。 */
    public Long expectedCrc32() {
        return expectedCrc32;
    }

    /** 实际解压数据计算出的校验和；解压失败时为 null。 */
    public Long actualCrc32() {
        return actualCrc32;
    }
}
