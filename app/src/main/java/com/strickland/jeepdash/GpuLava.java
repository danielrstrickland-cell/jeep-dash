package com.strickland.jeepdash;

import android.annotation.TargetApi;
import android.os.Build;

import com.strickland.jeepdash.core.VizTune;

/**
 * Lava lamp: soft waxy blobs that rise, sink, merge and split, swelling with the bass and flowing faster when
 * the music is loud. Shaded like warm wax lit from inside, in the colour profile's colours, and they drift
 * with the G-meter. Drawn by the graphics chip.
 */
@TargetApi(Build.VERSION_CODES.TIRAMISU)
final class GpuLava extends GpuLook {
    private static final int BALLS = 8;
    private static final String SHADER = ""
            + "uniform float bass;\n"
            + "uniform float heat;\n"
            + "uniform float count;\n"
            + "uniform float4 balls[8];\n"
            + "half4 main(float2 p) {\n"
            + "    float2 q = (p - 0.5 * size) / size.y;\n"
            + "    half3 back = half3(0.02, 0.01, 0.02) + c0.rgb * half(clamp(0.7 - abs(q.x) * 1.1, 0.0, 1.0) * 0.3 * heat * (0.5 + bass));\n"
            + "    float f = 0.0;\n"
            + "    float2 g = float2(0.0);\n"
            + "    for (int i = 0; i < 8; i++) {\n"
            + "        if (float(i) < count) {\n"
            + "            float2 e = q - balls[i].xy;\n"
            + "            float d = max(dot(e, e), 1e-5);\n"
            + "            float rr = balls[i].z * balls[i].z;\n"
            + "            f += rr / d;\n"
            + "            g += -2.0 * rr * e / (d * d);\n"
            + "        }\n"
            + "    }\n"
            + "    back += c1.rgb * half(clamp(f, 0.0, 1.0) * 0.22 * heat);\n"
            + "    if (f < 0.9) return half4(back, 1.0);\n"
            + "    float h = sqrt(max(0.0, 1.0 - 1.0 / f));\n"
            + "    float2 dh = g / (f * f) * 0.5 / max(h, 0.05);\n"
            + "    float3 n = normalize(float3(-dh * 0.09, 1.0));\n"
            + "    float3 l = normalize(float3(-0.4, -0.6, 0.7));\n"
            + "    float dif = 0.45 + 0.55 * max(dot(n, l), 0.0);\n"
            + "    float spec = pow(max(dot(reflect(float3(0.0, 0.0, -1.0), n), l), 0.0), 30.0);\n"
            + "    half3 wax = mix(c1.rgb, c2.rgb, half(h)) * half(dif) + c0.rgb * half((1.0 - h) * 0.5 * heat);\n"
            + "    wax += half3(1.0, 0.95, 0.85) * half(spec * 0.5);\n"
            + "    return half4(mix(back, wax, half(smoothstep(0.97, 1.03, f))), 1.0);\n"
            + "}\n";

    private final float[] balls = new float[BALLS * 4];
    private final VizMotion motion;
    private float clock;

    GpuLava(VizMotion motion) {
        super("lava", SHADER);
        this.motion = motion;
    }

    @Override
    protected void uniforms(VizPainter p, int lw, int lh, float dt, float seconds) {
        int look = VizTune.LAVA;
        clock += dt * (0.3f + 0.9f * p.vu) * p.tune.get(look, "flow");
        float wide = 0.5f * lw / lh, lx = motion.sloshX() * 0.12f, ly = motion.sloshY() * 0.08f;
        for (int i = 0; i < BALLS; i++) {
            float k = 0.5f + i * 0.13f;
            balls[i * 4] = (float) Math.sin(clock * 0.31 * k + i * 2.3) * wide * 0.55f + lx;
            balls[i * 4 + 1] = (float) Math.sin(clock * 0.47 * k + i * 1.7) * 0.36f + ly;
            balls[i * 4 + 2] = (0.065f + 0.035f * (float) Math.sin(i * 1.9 + 1)) * (1 + 0.25f * p.bass + 0.1f * p.beat);
            balls[i * 4 + 3] = 0;
        }
        shader.setFloatUniform("bass", p.bass);
        shader.setFloatUniform("heat", p.tune.get(look, "heat"));
        shader.setFloatUniform("count", Math.round(p.tune.get(look, "blobs")) - 0.5f);
        shader.setFloatUniform("balls", balls);
    }
}
