package com.strickland.jeepdash;

import android.annotation.TargetApi;
import android.os.Build;

import com.strickland.jeepdash.core.VizTune;

/**
 * Highway sub cam: a slow camera pan across two big subwoofers in a carpeted box. The cones and surrounds
 * push out with the bass and the cones ripple with the highs, with LED rings glowing round them, while the
 * highway's lights stream past the back glass. All drawn by the graphics chip: real shading on the rubber
 * surround, the cone, the glossy dust cap and the metal rim.
 */
@TargetApi(Build.VERSION_CODES.TIRAMISU)
final class GpuSubs extends GpuLook {
    private static final String SHADER = ""
            + "uniform float time;\n"
            + "uniform float exc;\n"
            + "uniform float treble;\n"
            + "uniform float ring;\n"
            + "uniform float cam;\n"
            + "uniform float zoom;\n"
            + "uniform float flow;\n"
            + "float sub(float2 q, float2 c) {\n"
            + "    float r = length(q - c) / 0.26;\n"
            + "    if (r > 1.12) return 0.0;\n"
            + "    if (r > 1.0) return 0.035;\n"
            + "    if (r > 0.85) {\n"
            + "        float t = (r - 0.925) / 0.075;\n"
            + "        return 0.02 + 0.05 * sqrt(max(0.0, 1.0 - t * t)) + exc * 0.5;\n"
            + "    }\n"
            + "    if (r > 0.3) return -0.1 * (0.85 - r) / 0.55 + exc + 0.004 * sin(r * 45.0 - time * 25.0) * treble;\n"
            + "    float t = r / 0.3;\n"
            + "    return exc - 0.1 + 0.05 * sqrt(max(0.0, 1.0 - t * t));\n"
            + "}\n"
            + "float height(float2 q) { return (sub(q, float2(-0.32, 0.1)) + sub(q, float2(0.32, 0.1))) * 0.26; }\n"
            + "half4 main(float2 p) {\n"
            + "    float2 q = (p - 0.5 * size) / size.y / zoom + float2(cam, 0.0);\n"
            + "    if (q.y < -0.33) {\n"                                                   // the back glass
            + "        half3 sky = half3(0.02, 0.03, 0.07);\n"
            + "        float a = smoothstep(0.6, 1.0, fract(q.x * 2.5 + time * flow));\n"
            + "        float b = smoothstep(0.7, 1.0, fract(-q.x * 1.7 + time * flow * 0.7));\n"
            + "        sky += half3(1.0, 0.65, 0.3) * half(exp(-pow((q.y + 0.42) * 60.0, 2.0)) * a);\n"
            + "        sky += half3(0.95, 0.12, 0.1) * half(exp(-pow((q.y + 0.38) * 70.0, 2.0)) * b);\n"
            + "        sky *= half(smoothstep(-0.33, -0.36, q.y));\n"
            + "        return half4(sky, 1.0);\n"
            + "    }\n"
            + "    float h = height(q);\n"
            + "    float e = 0.0015;\n"
            + "    float3 n = normalize(float3(-(height(q + float2(e, 0.0)) - h) / e, -(height(q + float2(0.0, e)) - h) / e, 1.0));\n"
            + "    float2 c = q.x < 0.0 ? float2(-0.32, 0.1) : float2(0.32, 0.1);\n"
            + "    float2 d = q - c;\n"
            + "    float r = length(d) / 0.26;\n"
            + "    float noise = fract(sin(dot(floor(q * 900.0), float2(12.9898, 78.233))) * 43758.5453);\n"
            + "    half3 base = half3(0.09, 0.09, 0.1) * half(0.8 + 0.2 * noise);\n"
            + "    float shin = 0.0;\n"
            + "    if (r < 1.12) {\n"
            + "        if (r > 1.0) {\n"
            + "            float bolt = step(0.82, fract(atan(d.y, d.x) / 6.2831853 * 8.0)) * step(abs(r - 1.06), 0.02);\n"
            + "            base = half3(0.32, 0.33, 0.36) * half(1.0 - 0.6 * bolt);\n"
            + "            shin = 0.8;\n"
            + "        } else if (r > 0.85) {\n"
            + "            base = half3(0.05, 0.05, 0.06);\n"
            + "            shin = 0.25;\n"
            + "        } else if (r > 0.3) {\n"
            + "            base = half3(0.11, 0.11, 0.13) * half(0.9 + 0.1 * sin(atan(d.y, d.x) * 60.0));\n"
            + "            shin = 0.35;\n"
            + "        } else {\n"
            + "            base = half3(0.16, 0.16, 0.19);\n"
            + "            shin = 0.9;\n"
            + "        }\n"
            + "    }\n"
            + "    float3 l = normalize(float3(-0.5, -0.6, 0.6));\n"
            + "    float dif = max(dot(n, l), 0.0);\n"
            + "    float spec = pow(max(dot(reflect(float3(0.0, 0.0, -1.0), n), l), 0.0), 40.0) * shin;\n"
            + "    half3 col = base * half(0.3 + 0.9 * dif) + half3(1.0) * half(spec * 0.8);\n"
            + "    col += c1.rgb * half(ring * exp(-pow((r - 1.17) * 25.0, 2.0)));\n"
            + "    col *= half(smoothstep(-0.31, -0.27, q.y));\n"                                // shadow under the glass
            + "    return half4(col, 1.0);\n"
            + "}\n";

    private float panClock;

    GpuSubs() { super("subs", SHADER); }

    @Override
    protected void uniforms(VizPainter p, int lw, int lh, float dt, float seconds) {
        int look = VizTune.SUBS;
        float pan = p.tune.get(look, "pan");
        panClock += dt * pan;
        float treble = 0;
        for (int i = p.shown * 2 / 3; i < p.shown; i++) treble += p.bar(i);
        treble /= Math.max(1, p.shown / 3);
        shader.setFloatUniform("time", seconds);
        shader.setFloatUniform("exc", p.tune.get(look, "throw") * (0.05f * p.bass + 0.03f * p.beat));
        shader.setFloatUniform("treble", Math.min(1, treble * 2));
        shader.setFloatUniform("ring", p.tune.on(look, "rings") ? 0.3f + p.bass + 0.5f * p.beat : 0);
        shader.setFloatUniform("cam", (float) Math.sin(panClock * 0.15) * 0.3f);
        shader.setFloatUniform("zoom", 1 + 0.12f * (float) Math.sin(panClock * 0.09 + 1));
        shader.setFloatUniform("flow", 0.4f + 0.6f * p.vu);
    }
}
