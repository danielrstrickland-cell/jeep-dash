package com.strickland.jeepdash;

import android.annotation.TargetApi;
import android.os.Build;

import com.strickland.jeepdash.core.VizTune;

import java.util.Random;

/**
 * Milk drop: a pool of milky liquid seen from low down. Every bass hit drops into it: a crown splash with
 * pointed fingers, a jet rising from the middle, and rings that spread and fade. The pool lilts with the music,
 * takes its tint from the colour profile, and tilts with the G-meter. Drawn by the graphics chip.
 */
@TargetApi(Build.VERSION_CODES.TIRAMISU)
final class GpuMilk extends GpuLook {
    private static final int DROPS = 4;
    private static final String SHADER = ""
            + "uniform float time;\n"
            + "uniform float bass;\n"
            + "uniform float vu;\n"
            + "uniform float tilt;\n"
            + "uniform float ripples;\n"
            + "uniform float splash;\n"
            + "uniform float cam;\n"
            + "uniform float4 drops[4];\n"
            + "half3 milk(float shade) { return mix(half3(0.93, 0.92, 0.9), c1.rgb, 0.35) * half(shade); }\n"
            + "half4 main(float2 p) {\n"
            + "    float2 q = (p - 0.5 * size) / size.y;\n"
            + "    float a = tilt * 0.12;\n"
            + "    float ca = cos(a);\n"
            + "    float sa = sin(a);\n"
            + "    q = float2(ca * q.x - sa * q.y, sa * q.x + ca * q.y);\n"
            + "    float hor = -0.14;\n"
            + "    half3 col = mix(c0.rgb * half(0.12 + 0.2 * bass), half3(0.0), half(clamp((hor - q.y) * 2.5, 0.0, 1.0)));\n"
            + "    if (q.y > hor + 0.001) {\n"
            + "        float z = cam * 0.4 / (q.y - hor);\n"
            + "        float2 w = float2(q.x * z, z);\n"
            + "        float2 g = float2(0.05 * sin(w.x * 3.0 + time * 1.3), 0.05 * cos(w.y * 4.0 - time * 1.7)) * (0.3 + vu);\n"
            + "        for (int i = 0; i < 4; i++) {\n"
            + "            float2 e = w - drops[i].xy;\n"
            + "            float r = length(e) + 1e-4;\n"
            + "            float x = r - drops[i].z * 0.8;\n"
            + "            float env = exp(-x * x * 10.0) * drops[i].w * exp(-drops[i].z * 0.9) * ripples;\n"
            + "            g += e / r * cos(x * 30.0) * env * 0.9;\n"
            + "        }\n"
            + "        float3 n = normalize(float3(-g.x, 1.0, -g.y));\n"
            + "        float3 d = normalize(float3(q.x, -q.y, 1.0));\n"
            + "        float3 r = reflect(d, n);\n"
            + "        float3 l = normalize(float3(-0.3, 0.8, 0.5));\n"
            + "        float dif = 0.35 + 0.65 * max(dot(n, l), 0.0);\n"
            + "        float fres = 0.04 + 0.96 * pow(1.0 - max(-dot(d, n), 0.0), 5.0);\n"
            + "        float spec = pow(max(dot(r, l), 0.0), 60.0);\n"
            + "        half3 pool = milk(dif) + c2.rgb * half(fres * 0.5) + half3(1.0) * half(spec * 0.9);\n"
            + "        col = mix(pool, col, half(clamp(z / 9.0, 0.0, 1.0)));\n"
            + "    }\n"
            + "    for (int i = 0; i < 4; i++) {\n"
            + "        float age = drops[i].z;\n"
            + "        float st = drops[i].w;\n"
            + "        if (age < 0.7 && st > 0.0) {\n"
            + "            float dz = drops[i].y;\n"
            + "            float sx = drops[i].x / dz;\n"
            + "            float sy = hor + cam * 0.4 / dz;\n"
            + "            float rr = (0.06 + age * 0.45) / dz;\n"
            + "            float ry = rr * 0.32;\n"
            + "            float hs = splash * st * 0.55 * sin(3.14159 * age / 0.7) / dz;\n"
            + "            float u = (q.x - sx) / rr;\n"
            + "            if (abs(u) < 1.0) {\n"
            + "                float s = sqrt(1.0 - u * u);\n"
            + "                float fing = 0.55 + 0.45 * pow(abs(cos(acos(u) * 7.0)), 3.0);\n"
            + "                float top = hs * fing;\n"
            + "                float bb = sy - ry * s;\n"
            + "                float bf = sy + ry * s;\n"
            + "                if (q.y < bb && q.y > bb - top) col = milk(0.72 + 0.25 * s);\n"
            + "                if (q.y < bf && q.y > bf - top) col = milk(0.85 + 0.15 * s) + half3(1.0) * half(0.25 * pow(s, 8.0));\n"
            + "            }\n"
            + "            float jt = clamp((age - 0.2) / 0.5, 0.0, 1.0);\n"
            + "            float jh = splash * st * 0.35 * sin(3.14159 * jt) / dz;\n"
            + "            float jw = 0.014 / dz * (1.0 - clamp((sy - q.y) / (jh + 1e-4), 0.0, 1.0) * 0.5);\n"
            + "            if (abs(q.x - sx) < jw && q.y < sy && q.y > sy - jh) col = milk(0.95);\n"
            + "        }\n"
            + "    }\n"
            + "    return half4(col, 1.0);\n"
            + "}\n";

    private final float[] drops = new float[DROPS * 4];
    private final Random random = new Random(5);
    private final VizMotion motion;
    private float lastBeat;
    private int next;

    GpuMilk(VizMotion motion) {
        super("milk", SHADER);
        this.motion = motion;
    }

    @Override
    protected void uniforms(VizPainter p, int lw, int lh, float dt, float seconds) {
        int look = VizTune.MILK;
        boolean hit = p.beat > 0.9f && lastBeat <= 0.9f;
        lastBeat = p.beat;
        for (int i = 0; i < DROPS; i++) {
            drops[i * 4 + 2] += dt;                                  // age in seconds
            if (drops[i * 4 + 2] > 5) drops[i * 4 + 3] = 0;           // a spent ripple
        }
        if (hit) {                                                    // a new drop lands in the oldest slot
            int i = next;
            next = (next + 1) % DROPS;
            float z = 1.3f + random.nextFloat() * 2.2f;
            drops[i * 4] = (random.nextFloat() - 0.5f) * z * 0.9f;
            drops[i * 4 + 1] = z;
            drops[i * 4 + 2] = 0;
            drops[i * 4 + 3] = 0.6f + 0.6f * p.bass;
        }
        shader.setFloatUniform("time", seconds);
        shader.setFloatUniform("bass", p.bass);
        shader.setFloatUniform("vu", p.vu);
        shader.setFloatUniform("tilt", motion.sloshX());
        shader.setFloatUniform("ripples", p.tune.get(look, "ripples"));
        shader.setFloatUniform("splash", p.tune.get(look, "splash"));
        shader.setFloatUniform("cam", p.tune.get(look, "view"));
        shader.setFloatUniform("drops", drops);
    }
}
