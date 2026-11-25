package com.strickland.jeepdash;

import android.annotation.TargetApi;
import android.os.Build;

import com.strickland.jeepdash.core.VizTune;

/**
 * The music road: a night road running toward a striped sun, with equalizer sidewalks (each slab lights with
 * its band) and buildings on both sides that rise and pulse with the music. It moves at the Jeep's real speed
 * and bends the moment you turn: speed comes from the Jeep (read only, through the link that already runs),
 * smoothed between readings with the G-meter so there is no lag, and turns come straight from the gyro.
 * Without the Jeep it cruises at a pace set by the music. Drawn by the graphics chip.
 */
@TargetApi(Build.VERSION_CODES.TIRAMISU)
final class GpuRoad extends GpuLook {
    private static final int BANDS = 16;
    private static final String SHADER = ""
            + "uniform float time;\n"
            + "uniform float travel;\n"
            + "uniform float curve;\n"
            + "uniform float bass;\n"
            + "uniform float beat;\n"
            + "uniform float build;\n"
            + "uniform float tilt;\n"
            + "uniform float lv[16];\n"
            + "half4 main(float2 p) {\n"
            + "    float2 q = (p - 0.5 * size) / size.y;\n"
            + "    float a = tilt * 0.08;\n"
            + "    float ca = cos(a);\n"
            + "    float sa = sin(a);\n"
            + "    q = float2(ca * q.x - sa * q.y, sa * q.x + ca * q.y);\n"
            + "    float hor = -0.06;\n"
            + "    float up = hor - q.y;\n"
            + "    half3 col = mix(c0.rgb * 0.35, half3(0.01, 0.0, 0.03), half(clamp(up * 3.0, 0.0, 1.0)));\n"
            + "    float2 sc = q - float2(curve * 0.04, hor - 0.15);\n"
            + "    float sr = 0.13 * (1.0 + 0.06 * bass);\n"
            + "    if (length(sc) < sr && q.y < hor) {\n"
            + "        float stripe = step(0.0, -sc.y) + step(0.45, fract((sc.y + 0.3) * 26.0));\n"
            + "        if (stripe > 0.5) col = mix(c2.rgb, c1.rgb, half(clamp((sc.y + sr) / (2.0 * sr), 0.0, 1.0)));\n"
            + "    }\n"
            + "    col += c1.rgb * half(0.25 * exp(-abs(up) * 14.0) * (0.6 + bass) * (0.9 + 0.1 * sin(time * 2.0)));\n"
            + "    if (q.y > hor + 0.0005) {\n"
            + "        float z = 0.25 / (q.y - hor);\n"
            + "        float x = q.x * z - curve * z * z * 0.02;\n"
            + "        float wz = z + travel;\n"
            + "        float ax = abs(x);\n"
            + "        float px = z * 1.5 / size.y;\n"
            + "        half3 g = half3(0.01, 0.0, 0.03);\n"
            + "        if (ax < 1.0) {\n"
            + "            g = half3(0.05, 0.05, 0.07);\n"
            + "            float dash = step(0.5, fract(wz * 0.5));\n"
            + "            g += half3(0.9, 0.85, 0.5) * half(dash * (1.0 - smoothstep(0.03, 0.03 + px, ax)));\n"
            + "            g += c1.rgb * half(0.6 * (1.0 - smoothstep(0.02, 0.02 + px, abs(ax - 0.97))));\n"
            + "        } else if (ax < 1.5) {\n"
            + "            float k = mod(floor(wz * 2.0), 16.0);\n"
            + "            float level = 0.0;\n"
            + "            for (int i = 0; i < 16; i++) if (abs(float(i) - k) < 0.5) level = lv[i];\n"
            + "            half3 tint = x < 0.0 ? c0.rgb : c2.rgb;\n"
            + "            float tile = step(0.08, fract(wz * 2.0)) * step(0.04, fract(ax * 4.0));\n"
            + "            g = tint * half(0.12 + 1.1 * level * tile);\n"
            + "        } else {\n"
            + "            float line = step(0.95, fract(wz)) + step(0.95, fract(x * 0.5));\n"
            + "            g += c0.rgb * half(0.45 * min(line, 1.0));\n"
            + "        }\n"
            + "        col = mix(g, col, half(clamp(z / 14.0, 0.0, 1.0)));\n"
            + "    }\n"
            + "    for (int i = 15; i >= 0; i--) {\n"
            + "        float zi = 1.0 + float(i) * 0.9 - fract(travel / 0.9) * 0.9;\n"
            + "        if (zi > 0.3) {\n"
            + "            float bh = (0.4 + 2.6 * lv[i]) * build;\n"
            + "            float yb = hor + 0.25 / zi;\n"
            + "            float yt = yb - bh * 0.25 / zi;\n"
            + "            float off = curve * zi * zi * 0.02;\n"
            + "            float xl0 = (-3.2 + off) / zi;\n"
            + "            float xl1 = (-1.8 + off) / zi;\n"
            + "            float xr0 = (1.8 + off) / zi;\n"
            + "            float xr1 = (3.2 + off) / zi;\n"
            + "            bool left = q.x > xl0 && q.x < xl1;\n"
            + "            bool right = q.x > xr0 && q.x < xr1;\n"
            + "            if (q.y < yb && q.y > yt && (left || right)) {\n"
            + "                float u = left ? (q.x - xl0) / (xl1 - xl0) : (q.x - xr0) / (xr1 - xr0);\n"
            + "                float v = (yb - q.y) / (yb - yt);\n"
            + "                half3 tint = left ? c0.rgb : c2.rgb;\n"
            + "                float win = step(0.35, fract(u * 5.0)) * step(0.4, fract(v * bh * 3.0));\n"
            + "                half3 b = half3(0.03, 0.03, 0.05) + tint * half(win * (0.3 + 0.9 * lv[i]));\n"
            + "                float rim = step(0.95, v) + step(u, 0.04) + step(0.96, u);\n"
            + "                b += tint * half(min(rim, 1.0) * (0.5 + beat));\n"
            + "                col = mix(b, col, half(clamp(zi / 14.0, 0.0, 1.0)));\n"
            + "            }\n"
            + "        }\n"
            + "    }\n"
            + "    return half4(col, 1.0);\n"
            + "}\n";

    private static final float MPH_PER_G_SECOND = 21.94f, MPS_PER_MPH = 0.44704f;
    private final float[] lv = new float[BANDS];
    private final VizMotion motion;
    private float mph, travel, curve;

    GpuRoad(VizMotion motion) {
        super("road", SHADER);
        this.motion = motion;
    }

    @Override
    protected void uniforms(VizPainter p, int lw, int lh, float dt, float seconds) {
        int look = VizTune.ROAD;
        Double jeep = p.speed;
        if (jeep != null) {
            mph += motion.forwardG() * MPH_PER_G_SECOND * dt;          // the G-meter fills in between readings
            mph += (jeep.floatValue() - mph) * Math.min(1, dt * 2.5f);  // the Jeep's reading keeps it honest
        } else {
            mph += (22 + 40 * p.vu - mph) * Math.min(1, dt * 0.8f);    // no Jeep: cruise with the music
        }
        mph = Math.max(0, mph);
        float mps = mph * MPS_PER_MPH;
        travel += mps * 0.25f * p.tune.get(look, "pace") * dt;         // a road unit is about 4 m
        float sign = p.tune.on(look, "flip") ? 1 : -1;
        float want = Math.max(-6, Math.min(6, sign * motion.yaw / Math.max(4, mps) * 60 * p.tune.get(look, "turns")));
        curve += (want - curve) * Math.min(1, dt * 8);
        for (int i = 0; i < BANDS; i++) lv[i] = p.bar(i * p.shown / BANDS);
        shader.setFloatUniform("time", seconds);
        shader.setFloatUniform("travel", travel % 1000f);
        shader.setFloatUniform("curve", curve);
        shader.setFloatUniform("bass", p.bass);
        shader.setFloatUniform("beat", p.beat);
        shader.setFloatUniform("build", p.tune.get(look, "build"));
        shader.setFloatUniform("tilt", motion.sloshX());
        shader.setFloatUniform("lv", lv);
    }
}
