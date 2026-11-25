package com.strickland.jeepdash;

import android.annotation.TargetApi;
import android.os.Build;

import com.strickland.jeepdash.core.VizTune;

import java.util.Random;

/**
 * The ferrofluid look, drawn entirely by the graphics chip: a blob of black liquid metal that grows spikes with
 * the music (one ring of spikes round its edge, one per band, plus small spikes over its face on the bass),
 * throws droplets on bass hits that are pulled back and melt into it, and leans with the G-meter. Lit like
 * polished metal in a studio: soft light from above, coloured rim lights from the colour profile, sharp
 * highlights. It is drawn at a lower resolution and scaled up, and that resolution drops by itself if the
 * tablet cannot keep up, so the screen stays smooth. Android 13 and up.
 */
@TargetApi(Build.VERSION_CODES.TIRAMISU)
final class GpuFerro extends GpuLook {
    private static final int BANDS = 16, DROPS = 6;
    private static final String SHADER = ""
            + "uniform float time;\n"
            + "uniform float bass;\n"
            + "uniform float beat;\n"
            + "uniform float radius;\n"
            + "uniform float spikes;\n"
            + "uniform float shine;\n"
            + "uniform float turn;\n"
            + "uniform float2 center;\n"
            + "uniform float lv[16];\n"
            + "uniform float4 drops[6];\n"
            // the liquid's height at q: the blob and droplets as metaballs (a true dome for a single ball),
            // spikes round the edge, small spikes over the face
            + "float height(float2 q) {\n"
            + "    float2 d = q - center;\n"
            + "    float f = radius * radius / max(dot(d, d), 1e-6);\n"
            + "    for (int i = 0; i < 6; i++) {\n"
            + "        float2 e = q - drops[i].xy;\n"
            + "        f += drops[i].z * drops[i].z / max(dot(e, e), 1e-6);\n"
            + "    }\n"
            + "    float h = f > 1.0 ? radius * sqrt(1.0 - 1.0 / f) : -1.0;\n"
            + "    float r = length(d);\n"
            + "    float a = atan(d.y, d.x) + turn;\n"
            + "    float s = a / 6.2831853 * 32.0;\n"
            + "    float idx = floor(s + 0.5);\n"
            + "    float k = mod(idx, 32.0);\n"
            + "    if (k > 15.5) k = 31.0 - k;\n"
            + "    float level = 0.0;\n"
            + "    for (int i = 0; i < 16; i++) if (abs(float(i) - k) < 0.5) level = lv[i];\n"
            + "    float side = abs(s - idx) * 2.0;\n"                                  // 0 at the spike's middle
            + "    float tip = radius * 0.92 + level * radius * 1.15 * spikes * (1.0 + 0.5 * beat);\n"
            + "    float edge = radius * 0.92 + (tip - radius * 0.92) * pow(max(0.0, 1.0 - side), 1.7)\n"
            + "        + radius * 0.02 * sin(time * 3.1 + idx * 1.7);\n"
            + "    h = max(h, (edge - r) * 1.6);\n"
            // small spikes on the face: a hex pattern that stands up on the bass
            + "    float2 g = d / (radius * 0.18);\n"
            + "    float2 hx = float2(g.x + g.y * 0.57735, g.y * 1.1547);\n"
            + "    float2 cell = floor(hx + 0.5);\n"
            + "    float2 off = hx - cell;\n"
            + "    float face = max(0.0, 1.0 - length(float2(off.x - off.y * 0.5, off.y * 0.866)) * 2.2);\n"
            + "    float inside = 1.0 - smoothstep(radius * 0.25, radius * 0.95, r);\n"
            + "    h += face * face * inside * radius * 0.22 * spikes * (0.25 + bass);\n"
            + "    return h;\n"
            + "}\n"
            + "half3 env(float3 r) {\n"                                                  // a studio around the metal
            + "    float top = smoothstep(-0.1, 0.8, -r.y);\n"
            + "    half3 col = mix(half3(0.02, 0.02, 0.03), half3(0.85, 0.88, 0.95), half(top * top));\n"
            + "    col += c0.rgb * half(smoothstep(0.35, 0.95, -r.x) * 0.9);\n"
            + "    col += c2.rgb * half(smoothstep(0.35, 0.95, r.x) * 0.9);\n"
            + "    col += c1.rgb * half(smoothstep(0.5, 1.0, r.y) * 0.6);\n"
            + "    return col;\n"
            + "}\n"
            + "half4 main(float2 p) {\n"
            + "    float2 q = (p - 0.5 * size) / size.y;\n"
            + "    float px = 1.0 / size.y;\n"
            // the floor: dark, with a coloured pool of light under the blob
            + "    float2 fl = (q - center - float2(0.0, radius * 1.35)) * float2(1.0, 4.0);\n"
            + "    half3 back = half3(0.01, 0.01, 0.015) + c1.rgb * half(0.35 * exp(-dot(fl, fl) * 9.0) * (0.4 + bass));\n"
            + "    float h = height(q);\n"
            + "    if (h <= -0.5 * px) return half4(back, 1.0);\n"
            + "    float e = px * 1.5;\n"
            + "    float hx = height(q + float2(e, 0.0));\n"
            + "    float hy = height(q + float2(0.0, e));\n"
            + "    float3 n = normalize(float3(-(hx - h) / e, -(hy - h) / e, 1.0));\n"
            + "    float3 r = reflect(float3(0.0, 0.0, -1.0), n);\n"
            + "    float fres = pow(1.0 - max(n.z, 0.0), 3.0);\n"
            + "    half3 col = half3(0.012, 0.012, 0.016) + env(r) * half(0.06 + 0.94 * fres);\n"
            + "    float3 l = normalize(float3(-0.45, -0.65, 0.62));\n"
            + "    float spec = pow(max(dot(r, l), 0.0), 90.0) * 2.2 + pow(max(dot(r, l), 0.0), 12.0) * 0.18;\n"
            + "    col += half3(1.0, 0.98, 0.95) * half(spec * shine);\n"
            + "    float cover = clamp(h / px * 0.8 + 0.5, 0.0, 1.0);\n"                     // smooth edges
            + "    return half4(mix(back, col, half(cover)), 1.0);\n"
            + "}\n";

    private final float[] lv = new float[BANDS], drops = new float[DROPS * 4];
    private final float[] dx = new float[DROPS], dy = new float[DROPS], vx = new float[DROPS], vy = new float[DROPS];
    private final Random random = new Random(11);
    private final VizMotion motion;
    private float turn, lastBeat;

    GpuFerro(VizMotion motion) {
        super("ferro", SHADER);
        this.motion = motion;
    }

    @Override
    protected void uniforms(VizPainter p, int lw, int lh, float dt, float seconds) {
        int look = VizTune.FERRO;
        float radius = 0.2f * p.tune.get(look, "size") * (1 + 0.18f * p.bass + 0.08f * p.beat);
        float cx = motion.sloshX() * 0.22f, cy = motion.sloshY() * 0.16f;
        for (int i = 0; i < BANDS; i++) lv[i] = p.bar(i * p.shown / BANDS);
        turn += (0.15f + 0.8f * p.vu) * p.tune.get(look, "spin") * dt;
        moveDrops(dt, p, radius, cx, cy, p.tune.on(look, "drops"));
        shader.setFloatUniform("time", seconds);
        shader.setFloatUniform("bass", p.bass);
        shader.setFloatUniform("beat", p.beat);
        shader.setFloatUniform("radius", radius);
        shader.setFloatUniform("spikes", p.tune.get(look, "spikes"));
        shader.setFloatUniform("shine", p.tune.get(look, "shine"));
        shader.setFloatUniform("turn", turn);
        shader.setFloatUniform("center", cx, cy);
        shader.setFloatUniform("lv", lv);
        shader.setFloatUniform("drops", drops);
    }

    /** Droplets thrown on bass hits, pulled home, melting back in when they arrive. */
    private void moveDrops(float dt, VizPainter p, float radius, float cx, float cy, boolean on) {
        boolean hit = p.beat > 0.9f && lastBeat <= 0.9f;
        lastBeat = p.beat;
        for (int i = 0; i < DROPS; i++) {
            float size = drops[i * 4 + 2];
            if (!on) size = 0;
            if (size <= 0 && hit && on) {
                hit = random.nextFloat() < 0.5f;                   // usually one or two a hit
                double a = random.nextDouble() * 2 * Math.PI;
                float speed = 0.5f + random.nextFloat() * 0.35f;
                dx[i] = (float) Math.cos(a) * radius * 0.8f;
                dy[i] = (float) Math.sin(a) * radius * 0.8f;
                vx[i] = (float) Math.cos(a) * speed;
                vy[i] = (float) Math.sin(a) * speed;
                size = radius * (0.16f + random.nextFloat() * 0.1f);
            }
            if (size > 0) {
                float r = (float) Math.hypot(dx[i], dy[i]);
                if (r < radius * 0.6f) size = 0;                     // melted back in
                else {
                    vx[i] += (-dx[i] / r * 2.6f) * dt;               // the magnet pulls it home
                    vy[i] += (-dy[i] / r * 2.6f) * dt;
                    vx[i] *= 1 - 0.8f * dt;
                    vy[i] *= 1 - 0.8f * dt;
                    dx[i] += vx[i] * dt;
                    dy[i] += vy[i] * dt;
                }
            }
            drops[i * 4] = cx + dx[i];
            drops[i * 4 + 1] = cy + dy[i];
            drops[i * 4 + 2] = Math.max(0, size);
            drops[i * 4 + 3] = 0;
        }
    }
}
