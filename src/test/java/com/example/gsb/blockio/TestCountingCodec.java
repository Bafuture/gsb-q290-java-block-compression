package com.example.gsb.blockio;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Test-only DEFLATE codec recording how many times chunks were decompressed. */
final class TestCountingCodec extends DeflateCodec {

    final AtomicInteger decompressCalls = new AtomicInteger();
    final AtomicLong decompressedBytes = new AtomicLong();

    @Override
    public byte[] decompress(byte[] compressed, int expectedLength) throws IOException {
        byte[] output = super.decompress(compressed, expectedLength);
        decompressCalls.incrementAndGet();
        decompressedBytes.addAndGet(output.length);
        return output;
    }
}
