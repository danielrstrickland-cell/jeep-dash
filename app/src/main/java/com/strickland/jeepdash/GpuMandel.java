package com.strickland.jeepdash;

import android.annotation.TargetApi;
import android.os.Build;

import com.strickland.jeepdash.core.VizTune;

/**
 * Mandelbrot zoom: an endless dive into the fractal's seahorse valley, coloured with the colour profile,
 * pulsing with the bass and beat. Engine rpm sets how fast it dives (or the music does, without the Jeep).
 * When the dive gets as deep as the graphics chip can draw sharply, it fades and starts again. Drawn by the
 * graphics chip.
 */
@TargetApi(Build.VERSION_CODES.TIRAMISU)
final class GpuMandel extends GpuLook {
    private static final float DEEPEST = 11;                         // e^-11: about as deep as 32-bit floats stay sharp
    private static final String SHADER = ""
            + "uniform float zoom;\n"
            + "uniform float rot;\n"
            + "uniform float bass;\n"
            + "uniform float beat;\n"
            + "uniform float fade;\n"
            + "uniform float shift;\n"
            + "half4 main(float2 p) {\n"
            + "    float2 q = (p - 0.5 * size) / size.y;\n"
            + "    float cr = cos(rot);\n"
            + "    float sr = sin(rot);\n"
            + "    q = float2(cr * q.x - sr * q.y, sr * q.x + cr * q.y);\n"
            + "    float2 c = float2(-0.743643887, 0.131825904) + q * zoom;\n"
            + "    float2 z = float2(0.0);\n"
            + "    float n = 0.0;\n"
            + "    float m2 = 0.0;\n"
            + "    for (int i = 0; i < 110; i++) {\n"
            + "        z = float2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + c;\n"
            + "        m2 = dot(z, z);\n"
            + "        if (m2 > 256.0) break;\n"
            + "        n += 1.0;\n"
            + "    }\n"
            + "    if (n > 109.5) return half4(0.0, 0.0, 0.0, 1.0);\n"
            + "    float sn = n - log2(log2(m2)) + 4.0;\n"
            + "    float t = fract(sn * 0.035 + shift);\n"
            + "    half3 col = t < 0.3333 ? mix(c0.rgb, c1.rgb, half(t * 3.0))\n"
            + "        : (t < 0.6667 ? mix(c1.rgb, c2.rgb, half(t * 3.0 - 1.0)) : mix(c2.rgb, c0.rgb, half(t * 3.0 - 2.0)));\n"
            + "    col *= half((0.55 + 0.45 * bass + 0.4 * beat) * fade);\n"
            + "    return half4(col, 1.0);\n"
            + "}\n";

    private float depth, rot, shift;

    GpuMandel() { super("mandel", SHADER); }

    @Override
    protected void uniforms(VizPainter p, int lw, int lh, float dt, float seconds) {
        int look = VizTune.MANDEL;
        float pace = p.rpm != null && p.tune.on(look, "rpmdive")
                ? 0.3f + (float) (p.rpm / 2500.0) : 0.5f + 0.8f * p.vu;
        depth += dt * 0.25f * pace * p.tune.get(look, "dive");
        if (depth > DEEPEST) depth = 0;
        float fade = Math.min(1, Math.min(depth / 0.6f, (DEEPEST - depth) / 0.6f));
        rot += dt * 0.1f * p.tune.get(look, "spin") * (1 + p.vu);
        shift += dt * (0.04f + 0.3f * p.beat);
        shader.setFloatUniform("zoom", 2.5f * (float) Math.exp(-depth));
        shader.setFloatUniform("rot", rot);
        shader.setFloatUniform("bass", p.bass);
        shader.setFloatUniform("beat", p.beat);
        shader.setFloatUniform("fade", Math.max(0, fade));
        shader.setFloatUniform("shift", shift % 1);
    }
}
