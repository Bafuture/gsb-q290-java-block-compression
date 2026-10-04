package com.example.gsb.blockzip;

/**
 * 单个压缩块在文件中的定位信息与校验和。
 *
 * @param compressedOffset 压缩数据在文件中的起始偏移（字节）
 * @param compressedLength 压缩数据长度（字节）
 * @param originalLength   该块解压后的原始长度（最后一块可能小于块大小）
 * @param crc32            该块原始（未压缩）数据的 CRC-32 校验和
 */
public record BlockIndexEntry(long compressedOffset, int compressedLength,
                              int originalLength, long crc32) {
}
