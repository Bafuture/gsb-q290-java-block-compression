package com.example.gsb.blockzip;

import java.nio.charset.StandardCharsets;
import java.util.Random;

/** 生成确定性的测试数据。 */
final class TestData {

    private TestData() {
    }

    /** 半可压缩数据：重复文本片段中混入随机字节，压缩率适中且可复现。 */
    static byte[] semiCompressible(int size, long seed) {
        String[] words = {
                "the quick brown fox jumps over the lazy dog. ",
                "block compression with random access. ",
                "pack my box with five dozen liquor jugs. ",
                "index entry: offset, length, checksum. ",
        };
        Random random = new Random(seed);
        byte[] data = new byte[size];
        int i = 0;
        while (i < size) {
            if (random.nextInt(5) == 0) {
                data[i++] = (byte) random.nextInt(256);
            } else {
                byte[] w = words[random.nextInt(words.length)].getBytes(StandardCharsets.UTF_8);
                int n = Math.min(w.length, size - i);
                System.arraycopy(w, 0, data, i, n);
                i += n;
            }
        }
        return data;
    }

    /** 高可压缩数据：周期性模式。 */
    static byte[] highlyCompressible(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i % 7);
        }
        return data;
    }
}
