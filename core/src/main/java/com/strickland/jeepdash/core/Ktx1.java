package com.strickland.jeepdash.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * A KTX1 cube map as Filament's cmgen writes it for image-based lighting: six faces per level, every level
 * after the one before, and the nine spherical-harmonics colours of its soft light under the key "sh".
 */
public final class Ktx1 {
    private static final byte[] MAGIC = {(byte) 0xAB, 'K', 'T', 'X', ' ', '1', '1', (byte) 0xBB, '\r', '\n', 0x1A, '\n'};

    public final int size, levels, glType, glFormat, glInternalFormat;
    /** Per level: where its first face starts in the file, and the bytes in each face (faces follow each other). */
    public final int[] offset, faceBytes;
    /** 27 floats (9 RGB), or null when the file has none. */
    public final float[] sh;

    private Ktx1(int size, int levels, int glType, int glFormat, int glInternalFormat, int[] offset, int[] faceBytes, float[] sh) {
        this.size = size; this.levels = levels; this.glType = glType; this.glFormat = glFormat;
        this.glInternalFormat = glInternalFormat; this.offset = offset; this.faceBytes = faceBytes; this.sh = sh;
    }

    /** Null when the file is not a little-endian six-face KTX1 or is cut short. */
    public static Ktx1 parse(byte[] data) {
        try {
            if (data.length < 64) return null;
            for (int i = 0; i < MAGIC.length; i++) if (data[i] != MAGIC[i]) return null;
            ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if (b.getInt(12) != 0x04030201) return null;
            int glType = b.getInt(16), glFormat = b.getInt(24), glInternal = b.getInt(28);
            int w = b.getInt(36), faces = b.getInt(52), levels = Math.max(1, b.getInt(56)), kvBytes = b.getInt(60);
            if (faces != 6 || w <= 0 || kvBytes < 0 || 64 + kvBytes > data.length) return null;
            float[] sh = null;
            int p = 64;
            while (p + 4 <= 64 + kvBytes) {
                int n = b.getInt(p);
                if (n < 0 || p + 4 + n > 64 + kvBytes) return null;
                String kv = new String(data, p + 4, n, StandardCharsets.US_ASCII);
                int z = kv.indexOf('\0');
                if (z > 0 && kv.substring(0, z).equals("sh")) sh = floats(kv.substring(z + 1));
                p += 4 + ((n + 3) & ~3);
            }
            p = 64 + kvBytes;
            int[] offset = new int[levels], faceBytes = new int[levels];
            for (int l = 0; l < levels; l++) {
                if (p + 4 > data.length) return null;
                int n = b.getInt(p);
                int stride = (n + 3) & ~3;
                if (n <= 0 || stride != n || p + 4 + 6L * stride > data.length) return null;
                offset[l] = p + 4;
                faceBytes[l] = n;
                p += 4 + 6 * stride;
            }
            return new Ktx1(w, levels, glType, glFormat, glInternal, offset, faceBytes, sh);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static float[] floats(String s) {
        String[] parts = s.trim().split("\\s+");
        if (parts.length < 27) return null;
        float[] f = new float[27];
        for (int i = 0; i < 27; i++) f[i] = Float.parseFloat(parts[i]);
        return f;
    }
}
