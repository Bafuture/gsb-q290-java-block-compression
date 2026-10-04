package com.example.gsb.blockio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Block codec backed by raw DEFLATE ({@link Deflater}/{@link Inflater}).
 * Codec id is {@code 0}.
 */
public class DeflateCodec implements BlockCodec {

    public static final int ID = 0;

    private final int level;

    public DeflateCodec() {
        this(Deflater.DEFAULT_COMPRESSION);
    }

    public DeflateCodec(int level) {
        this.level = level;
    }

    @Override
    public int id() {
        return ID;
    }

    @Override
    public byte[] compress(byte[] data, int offset, int length) {
        Deflater deflater = new Deflater(level);
        deflater.setInput(data, offset, length);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, length / 2));
        byte[] buffer = new byte[8192];
        while (!deflater.finished()) {
            int n = deflater.deflate(buffer);
            out.write(buffer, 0, n);
        }
        deflater.end();
        return out.toByteArray();
    }

    @Override
    public byte[] decompress(byte[] compressed, int expectedLength) throws IOException {
        if (expectedLength == 0) {
            return new byte[0];
        }
        Inflater inflater = new Inflater();
        inflater.setInput(compressed);
        byte[] output = new byte[expectedLength];
        int written = 0;
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(output, written, output.length - written);
                if (n == 0) {
                    if (written >= output.length) {
                        throw new IOException("Decompressed chunk is larger than the indexed length");
                    }
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        throw new IOException("Truncated or corrupt DEFLATE stream");
                    }
                }
                written += n;
            }
        } catch (DataFormatException e) {
            throw new IOException("Corrupt DEFLATE stream", e);
        } finally {
            inflater.end();
        }
        if (written != expectedLength) {
            throw new IOException("Decompressed chunk length " + written
                    + " does not match indexed length " + expectedLength);
        }
        return output;
    }
}
