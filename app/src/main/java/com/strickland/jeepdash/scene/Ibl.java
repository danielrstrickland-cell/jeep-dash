package com.strickland.jeepdash.scene;

import com.google.android.filament.Engine;
import com.google.android.filament.IndirectLight;
import com.google.android.filament.Texture;
import com.strickland.jeepdash.core.Ktx1;

import java.nio.ByteBuffer;

/** Image-based light from a cmgen KTX1 file (tools/make_ibl.py): its cube map for reflections and its SH for the soft fill. */
final class Ibl {
    final IndirectLight light;
    final Texture texture;

    private Ibl(IndirectLight light, Texture texture) { this.light = light; this.texture = texture; }

    /** Null when the file cannot be read. */
    static Ibl load(Engine engine, byte[] file, float intensity) {
        Ktx1 k = Ktx1.parse(file);
        if (k == null || k.sh == null) return null;
        Trail.step("IBL texture " + k.size + "px, " + k.levels + " levels");
        Texture tex = new Texture.Builder()
                .width(k.size)
                .height(k.size)
                .levels(k.levels)
                .sampler(Texture.Sampler.SAMPLER_CUBEMAP)
                .format(Texture.InternalFormat.R11F_G11F_B10F)
                .build(engine);
        for (int level = 0; level < k.levels; level++) {
            int n = k.faceBytes[level];
            Trail.step("IBL setImage level " + level + " faceBytes " + n);
            ByteBuffer buf = ByteBuffer.allocateDirect(6 * n);
            buf.put(file, k.offset[level], 6 * n);
            buf.flip();
            // the six faces follow each other. Not the 3D setImage: Filament's Java binding shrinks its region's
            // height by the level a second time, so every level past 0 failed its size check (the native crash)
            int[] faces = {0, n, 2 * n, 3 * n, 4 * n, 5 * n};
            tex.setImage(engine, level, new Texture.PixelBufferDescriptor(buf, Texture.Format.RGB,
                    Texture.Type.UINT_10F_11F_11F_REV), faces);
        }
        Trail.step("IBL light");
        IndirectLight light = new IndirectLight.Builder()
                .reflections(tex)
                .irradiance(3, k.sh)
                .intensity(intensity)
                .build(engine);
        return new Ibl(light, tex);
    }

    void destroy(Engine engine) {
        engine.destroyIndirectLight(light);
        engine.destroyTexture(texture);
    }
}
