package com.strickland.jeepdash;

import android.annotation.TargetApi;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;

import com.strickland.jeepdash.core.SubPhysics;
import com.strickland.jeepdash.core.VizTune;

import java.util.Random;

/**
 * Sub cam: Daniel's 15-inch sub, built to the EVL-15's published dimensions, in a carpeted ported box, seen at
 * an angle so the cone's travel shows. Each pixel is traced through the real 3D shapes: the cast basket and
 * flange, the stitched high-roll foam surround, the pressed-paper cone, the convex dust cap and, with the box
 * hidden (a cutaway), the red two-layer spider, the coil former, the double-stack ferrite magnets, plates and
 * vented pole. The cone is moved by {@link SubPhysics} on the driver's real numbers: slowed, so each push,
 * rebound and settle can be seen, or at real speed with motion blur. Port air puffs out on hits, an LED ring
 * glows in the colour profile, and a readout shows live excursion in millimetres (red past Xmax).
 */
@TargetApi(Build.VERSION_CODES.TIRAMISU)
final class GpuSubs extends GpuLook {
    private static final int PUFFS = 6;
    private static final float SLOW = 12;                              // slowed motion: 12 times slower
    private static final String SHADER = ""
            + "uniform float time;\n"
            + "uniform float xc;\n"
            + "uniform float xlo;\n"
            + "uniform float xhi;\n"
            + "uniform float theta;\n"
            + "uniform float phi;\n"
            + "uniform float dist;\n"
            + "uniform float tz;\n"
            + "uniform float boxOn;\n"
            + "uniform float cutOn;\n"
            + "uniform float ring;\n"
            + "uniform float4 puffs[6];\n"
            + "float hash(float2 v) { return fract(sin(dot(v, float2(12.9898, 78.233))) * 43758.5453); }\n"
            + "float box2(float2 p, float2 c, float2 h) {\n"
            + "    float2 d = abs(p - c) - h;\n"
            + "    return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0);\n"
            + "}\n"
            + "float seg(float2 p, float2 a, float2 b) {\n"
            + "    float2 pa = p - a;\n"
            + "    float2 ba = b - a;\n"
            + "    return length(pa - ba * clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0));\n"
            + "}\n"
            + "float2 pick(float2 a, float d, float id) { return d < a.x ? float2(d, id) : a; }\n"
            // the driver in millimetres: axis along z, z = 0 the mounting face, x = cone travel (mm, out)
            + "float2 driver(float3 p, float x) {\n"
            + "    float r = length(p.xy);\n"
            + "    float z = p.z;\n"
            + "    float2 q = float2(r, z);\n"
            + "    float2 res = float2(1e5, 0.0);\n"
            + "    res = pick(res, box2(q, float2(187.0, 5.0), float2(10.0, 5.0)) - 1.0, 1.0);\n"          // flange
            + "    float2 s = q - float2(164.0, 10.0 + x * 0.5);\n"                                          // surround
            + "    s.y /= 1.0 - min(abs(x), 30.0) / 90.0;\n"
            + "    float ds = s.y > 0.0 ? abs(length(s) - 15.0) : min(length(s - float2(15.0, 0.0)), length(s + float2(15.0, 0.0)));\n"
            + "    res = pick(res, ds - 2.5, 2.0);\n"
            + "    res = pick(res, seg(q, float2(149.0, 10.0 + x), float2(78.0, -62.0 + x)) - 2.0, 3.0);\n"  // cone
            + "    float dc = abs(length(q - float2(0.0, -123.0 + x)) - 99.0) - 2.0;\n"                       // dust cap
            + "    dc = max(dc, max((-62.0 + x) - z, r - 80.0));\n"
            + "    res = pick(res, dc, 4.0);\n"
            + "    if (boxOn > 0.5) return res;\n"
            + "    float db = min(seg(q, float2(178.0, 0.0), float2(130.0, -100.0)), seg(q, float2(130.0, -100.0), float2(100.0, -128.0))) - 4.0;\n"
            + "    float spoke = abs(fract(atan(p.y, p.x) / 6.2831853 * 6.0) - 0.5) * 1.0472 * r - 16.0;\n"  // 6 spokes
            + "    float rings = min(abs(z), abs(z + 100.0)) - 8.0;\n"
            + "    res = pick(res, max(db, min(spoke, rings)), 1.0);\n"
            + "    float zs = mix(-100.0 + x, -100.0, clamp((r - 40.0) / 88.0, 0.0, 1.0)) + 4.0 * sin((r - 40.0) * 0.29);\n"
            + "    float band = max(40.0 - r, r - 128.0);\n"
            + "    float dsp = min(max(abs(z - zs) - 1.6, band), max(abs(z - zs + 6.0) - 1.6, band));\n"   // two-layer spider
            + "    res = pick(res, dsp * 0.7, 5.0);\n"
            + "    res = pick(res, max(abs(r - 38.1) - 1.5, abs(z - (-106.0 + x)) - 44.0), 6.0);\n"       // former + coil
            + "    res = pick(res, min(box2(q, float2(70.0, -135.5), float2(30.0, 7.5)), box2(q, float2(50.0, -206.5), float2(50.0, 7.5))), 7.0);\n"
            + "    res = pick(res, min(box2(q, float2(69.75, -157.5), float2(23.75, 12.5)), box2(q, float2(69.75, -184.5), float2(23.75, 12.5))), 8.0);\n"
            + "    res = pick(res, max(box2(q, float2(18.0, -170.0), float2(18.0, 42.0)), 12.0 - r), 9.0);\n"  // vented pole
            + "    return res;\n"
            + "}\n"
            + "float2 scene(float3 p, float x) {\n"
            + "    float2 d = driver(p, x);\n"
            + "    if (cutOn > 0.5) d.x = max(d.x, min(p.x, p.y));\n"                                     // a quarter cut away
            + "    return d;\n"
            + "}\n"
            + "float3 normalAt(float3 p, float x) {\n"
            + "    float2 k = float2(1.0, -1.0);\n"
            + "    float e = 0.4;\n"
            + "    return normalize(k.xyy * scene(p + k.xyy * e, x).x + k.yyx * scene(p + k.yyx * e, x).x\n"
            + "        + k.yxy * scene(p + k.yxy * e, x).x + k.xxx * scene(p + k.xxx * e, x).x);\n"
            + "}\n"
            + "half3 light(float3 base, float spec, float pw, float3 n, float3 rd) {\n"
            + "    float3 l = normalize(float3(-0.35, 0.85, 0.45));\n"
            + "    float dif = max(dot(n, l), 0.0);\n"
            + "    float sp = pow(max(dot(n, normalize(l - rd)), 0.0), pw) * spec;\n"
            + "    float fill = max(dot(n, normalize(float3(0.0, -0.35, 1.0))), 0.0);\n"
            + "    half3 col = half3(base * (0.16 + 1.05 * dif)) + half3(sp * 1.2);\n"
            + "    col += c1.rgb * half(ring * 0.22 * fill) * half3(base * 6.0 + 0.04);\n"
            + "    col += c2.rgb * half(pow(1.0 - max(dot(n, -rd), 0.0), 4.0) * 0.12);\n"
            + "    return col;\n"
            + "}\n"
            + "half3 material(float id, float3 p, float3 n, float3 rd, float x) {\n"
            + "    float r = length(p.xy);\n"
            + "    float a = atan(p.y, p.x);\n"
            + "    float3 base = float3(0.045);\n"
            + "    float spec = 0.35;\n"
            + "    float pw = 30.0;\n"
            + "    if (id < 1.5) { base = float3(0.045, 0.045, 0.05); }\n"                                   // cast basket
            + "    else if (id < 2.5) { base = float3(0.032) * (0.9 + 0.2 * hash(floor(p.xy * 0.8))); spec = 0.06; pw = 8.0;\n"
            + "        if (abs(r - 178.0) < 1.4 && fract(a * r / 7.0) < 0.5) base = float3(0.5); }\n"          // stitched foam
            + "    else if (id < 3.5) { base = float3(0.05) * (0.9 + 0.1 * sin(a * 140.0)); spec = 0.12; pw = 12.0;\n"
            + "        if (abs(r - 146.0) < 1.4 && fract(a * r / 7.0) < 0.5) base = float3(0.55); }\n"         // pressed paper, stitching
            + "    else if (id < 4.5) { base = float3(0.055); spec = 0.8; pw = 70.0; }\n"                     // convex dust cap
            + "    else if (id < 5.5) { base = float3(0.6, 0.05, 0.04); spec = 0.05; pw = 6.0; }\n"            // red spider
            + "    else if (id < 6.5) { base = p.z < -128.0 + x ? float3(0.04) : float3(0.5, 0.5, 0.52); spec = 0.5; }\n"
            + "    else if (id < 7.5) { base = float3(0.06); spec = 0.45; pw = 40.0; }\n"                     // plates
            + "    else if (id < 8.5) { base = float3(0.035); spec = 0.05; pw = 6.0; }\n"                     // ferrite
            + "    else { base = float3(0.55, 0.56, 0.6); spec = 0.9; pw = 80.0; }\n"                         // pole
            + "    float ao = 0.55 + 0.45 * clamp(scene(p + n * 8.0, x).x / 8.0, 0.0, 1.0);\n"
            + "    return light(base, spec, pw, n, rd) * half(ao);\n"
            + "}\n"
            + "half3 carpet(float3 p, float3 n, float3 rd) {\n"
            + "    float fib = hash(floor(p.xy * 0.9) + floor(p.zz * 0.9));\n"
            + "    return light(float3(0.07 + 0.025 * fib), 0.04, 6.0, n, rd);\n"
            + "}\n"
            + "half4 main(float2 fc) {\n"
            + "    float2 q = (fc - 0.5 * size) / size.y;\n"
            + "    float x = xhi > xlo + 0.05 ? mix(xlo, xhi, hash(fc + fract(time * 7.13) * 31.0)) : xc;\n"  // motion blur
            + "    float3 tgt = float3(0.0, 0.0, tz);\n"
            + "    float ct = cos(theta);\n"
            + "    float3 ro = tgt + dist * float3(sin(phi) * ct, sin(theta), cos(phi) * ct);\n"
            + "    float3 fw = normalize(tgt - ro);\n"
            + "    float3 rt = normalize(cross(fw, float3(0.0, 1.0, 0.0)));\n"
            + "    float3 up = cross(rt, fw);\n"
            + "    float3 rd = normalize(fw * 1.7 + rt * q.x - up * q.y);\n"
            + "    half3 col = half3(0.012, 0.012, 0.016) * half(1.0 - 0.6 * q.y);\n"                       // trunk, dim
            + "    float tHit = 1e9;\n"
            + "    float floorY = boxOn > 0.5 ? -230.0 : -260.0;\n"
            + "    if (rd.y < 0.0) {\n"
            + "        float tf = (floorY - ro.y) / rd.y;\n"
            + "        float3 fp = ro + rd * tf;\n"
            + "        col = carpet(fp, float3(0.0, 1.0, 0.0), rd) * half(clamp(1.0 - tf / 6000.0, 0.2, 1.0));\n"
            + "        tHit = tf;\n"
            + "    }\n"
            + "    bool march = false;\n"
            + "    float t0 = 0.0;\n"
            + "    float t1 = 0.0;\n"
            + "    if (boxOn > 0.5) {\n"
            + "        float3 bmin = float3(-340.0, -230.0, -420.0);\n"
            + "        float3 bmax = float3(340.0, 230.0, 0.0);\n"
            + "        float3 ta = (bmin - ro) / rd;\n"
            + "        float3 tb = (bmax - ro) / rd;\n"
            + "        float3 tmn = min(ta, tb);\n"
            + "        float3 tmx = max(ta, tb);\n"
            + "        float tn = max(max(tmn.x, tmn.y), tmn.z);\n"
            + "        float tfar = min(min(tmx.x, tmx.y), tmx.z);\n"
            + "        if (tn < tfar && tn > 0.0 && tn < tHit) {\n"
            + "            float3 hp = ro + rd * tn;\n"
            + "            bool front = tn == tmn.z;\n"
            + "            float rr = length(hp.xy);\n"
            + "            float3 n = front ? float3(0.0, 0.0, 1.0) : (tn == tmn.y ? float3(0.0, 1.0, 0.0) : float3(sign(hp.x), 0.0, 0.0));\n"
            + "            tHit = tn;\n"
            + "            float t60 = (60.0 - ro.z) / rd.z;\n"                                         // the sub stands proud
            + "            float r60 = length((ro + rd * t60).xy);\n"
            + "            if (front && (rr < 197.0 || r60 < 205.0)) {\n"
            + "                march = true;\n"
            + "                t0 = max(0.0, t60);\n"
            + "                t1 = rr < 197.0 ? (-240.0 - ro.z) / rd.z : tn;\n"
            + "            }\n"
            + "            if (front && rr < 197.0) {\n"
            + "                col = half3(0.0);\n"
            + "                tHit = 1e9;\n"
            + "            } else if (front && hp.x > 262.0 && hp.x < 320.0 && abs(hp.y) < 200.0) {\n"
            + "                col = half3(0.006) + c1.rgb * half(ring * 0.05);\n"                                 // the port slot
            + "            } else {\n"
            + "                col = carpet(hp, n, rd);\n"
            + "                if (front) col += c1.rgb * half(ring * exp(-pow((rr - 207.0) * 0.3, 2.0)));\n"      // LED ring
            + "            }\n"
            + "        }\n"
            + "    } else {\n"
            + "        float3 oc = ro - float3(0.0, 0.0, -100.0);\n"                                                // bounding sphere
            + "        float b = dot(oc, rd);\n"
            + "        float h = b * b - dot(oc, oc) + 260.0 * 260.0;\n"
            + "        if (h > 0.0) {\n"
            + "            march = true;\n"
            + "            t0 = max(0.0, -b - sqrt(h));\n"
            + "            t1 = -b + sqrt(h);\n"
            + "        }\n"
            + "    }\n"
            + "    if (march) {\n"
            + "        float t = t0;\n"
            + "        float id = -1.0;\n"
            + "        for (int i = 0; i < 64; i++) {\n"
            + "            float2 d = scene(ro + rd * t, x);\n"
            + "            if (d.x < 0.35 + t * 0.0005) { id = d.y; break; }\n"
            + "            t += d.x * 1.1;\n"
            + "            if (t > t1) break;\n"
            + "        }\n"
            + "        if (id > 0.0 && t <= t1 + 1.0 && t < tHit + 1.0) {\n"
            + "            float3 p = ro + rd * t;\n"
            + "            col = material(id, p, normalAt(p, x), rd, x);\n"
            + "            tHit = t;\n"
            + "        }\n"
            + "    }\n"
            + "    for (int i = 0; i < 6; i++) {\n"                                                             // port air
            + "        float4 pf = puffs[i];\n"
            + "        if (pf.w > 0.0) {\n"
            + "            float tc = dot(pf.xyz - ro, rd);\n"
            + "            if (tc > 0.0 && tc < tHit) {\n"
            + "                float d = length(pf.xyz - (ro + rd * tc));\n"
            + "                float rad = 30.0 + (1.0 - pf.w) * 110.0;\n"
            + "                col += half3(0.75, 0.82, 0.95) * half(pf.w * 0.3 * exp(-d * d / (rad * rad)));\n"
            + "            }\n"
            + "        }\n"
            + "    }\n"
            + "    col *= half(1.0 - 0.35 * dot(q, q));\n"
            + "    return half4(col, 1.0);\n"
            + "}\n";

    private final SubPhysics phys = new SubPhysics();
    private final VizMotion motion;
    private final float[] puffs = new float[PUFFS * 4];
    private final Random random = new Random(9);
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private float mm, peakMm, puffWait;
    private boolean over;
    private int nextPuff;

    GpuSubs(VizMotion motion) {
        super("subs", SHADER);
        this.motion = motion;
    }

    /** The loudest low bass frequency right now (the bars under 90 Hz), or 45 Hz when there is none. */
    private static float bassHz(VizPainter p) {
        double ratio = 350;                                            // the bars run log-spaced 40 Hz to 14 kHz
        float sum = 0, weight = 0;
        for (int i = 0; i < p.shown; i++) {
            double hz = 40 * Math.pow(ratio, (i + 0.5) / p.shown);
            if (hz > 90) break;
            float w = p.bar(i) * p.bar(i);
            sum += w * (float) hz;
            weight += w;
        }
        return weight > 1e-4f ? Math.max(32, sum / weight) : 45;
    }

    @Override
    protected void uniforms(VizPainter p, int lw, int lh, float dt, float seconds) {
        int look = VizTune.SUBS;
        boolean slowed = Math.round(p.tune.get(look, "motion")) == 0;
        phys.setBox(p.tune.get(look, "tune"));
        phys.setFeel(p.tune.get(look, "stiff"), p.tune.get(look, "damp"));
        double level = Math.min(1, p.bass * 1.15);
        double volts = SubPhysics.FULL_VOLTS * p.tune.get(look, "drive") * Math.pow(level, 1.2);
        double[] span = phys.run(slowed ? dt / SLOW : dt, bassHz(p), volts);
        mm = (float) (phys.cone() * 1000);
        over = phys.overXmax();
        peakMm = Math.max(Math.abs(mm), peakMm - dt * 20);
        boolean box = p.tune.on(look, "box");

        // port air: a puff each time the air rushes out hard
        puffWait -= dt;
        double air = phys.portSpeed();
        if (box && p.tune.on(look, "port") && air > 6 && puffWait <= 0) {
            int i = nextPuff;
            nextPuff = (nextPuff + 1) % PUFFS;
            puffs[i * 4] = 291;
            puffs[i * 4 + 1] = (random.nextFloat() - 0.5f) * 300;
            puffs[i * 4 + 2] = 20;
            puffs[i * 4 + 3] = Math.min(1, (float) air / 15f);
            puffWait = slowed ? 0.35f : 0.12f;
        }
        for (int i = 0; i < PUFFS; i++) {
            if (puffs[i * 4 + 3] <= 0) continue;
            puffs[i * 4 + 2] += dt * (slowed ? 160 : 600);                // drifts out of the port
            puffs[i * 4 + 3] = Math.max(0, puffs[i * 4 + 3] - dt * (slowed ? 0.7f : 2.2f));
        }

        float sway = p.tune.on(look, "sway") ? 1 : 0;
        float angle = (float) Math.toRadians(p.tune.get(look, "angle") + sway * p.sloshY * 6);
        float phi = (box ? 0 : 0.42f) + sway * p.sloshX * 0.12f;
        shader.setFloatUniform("time", seconds);
        shader.setFloatUniform("xc", mm);
        shader.setFloatUniform("xlo", slowed ? mm : (float) (span[0] * 1000));
        shader.setFloatUniform("xhi", slowed ? mm : (float) (span[1] * 1000));
        shader.setFloatUniform("theta", angle);
        shader.setFloatUniform("phi", phi);
        shader.setFloatUniform("dist", 1400 / p.tune.get(look, "zoom"));
        shader.setFloatUniform("tz", box ? -30 : -100);
        shader.setFloatUniform("boxOn", box ? 1 : 0);
        shader.setFloatUniform("cutOn", !box && p.tune.on(look, "cut") ? 1 : 0);
        shader.setFloatUniform("ring", p.tune.on(look, "rings") ? 0.35f + p.bass + 0.5f * p.beat : 0);
        shader.setFloatUniform("puffs", puffs);
    }

    /** The live excursion readout, top right: millimetres out or in, and a bar to Xmax that turns red past it. */
    @Override
    protected void overlay(Canvas c, int w, int h, VizPainter p) {
        if (!p.tune.on(VizTune.SUBS, "readout")) return;
        float u = h / 800f, pw = 230 * u, ph = 78 * u, x = w - pw - 24 * u, y = p.safeTop + 14 * u;
        ink.setStyle(Paint.Style.FILL);
        ink.setColor(0x99000000);
        c.drawRoundRect(x, y, x + pw, y + ph, 8 * u, 8 * u, ink);
        ink.setTextAlign(Paint.Align.LEFT);
        ink.setTextSize(17 * u);
        ink.setColor(0xFF9FE1CB);
        c.drawText("Excursion", x + 16 * u, y + 26 * u, ink);
        ink.setTextSize(28 * u);
        ink.setColor(over ? 0xFFFF4B4B : 0xFFFFFFFF);
        c.drawText(String.format(java.util.Locale.US, "%+.1f mm", mm), x + 16 * u, y + 62 * u, ink);
        float bx = x + pw - 34 * u, top = y + 10 * u, bottom = y + ph - 10 * u, mid = (top + bottom) / 2;
        float scale = (bottom - top) / 2 / (float) (SubPhysics.XMAX * 1000 * 1.4);
        ink.setColor(0xFF333333);
        c.drawRect(bx, top, bx + 14 * u, bottom, ink);
        bar.set(bx, Math.min(mid, mid - mm * scale), bx + 14 * u, Math.max(mid, mid - mm * scale));
        ink.setColor(over ? 0xFFFF4B4B : 0xFF1D9E75);
        c.drawRect(bar, ink);
        float xm = (float) (SubPhysics.XMAX * 1000) * scale;                 // Xmax marks
        ink.setColor(0xFFFFFFFF);
        c.drawRect(bx - 3 * u, mid - xm - u, bx + 17 * u, mid - xm + u, ink);
        c.drawRect(bx - 3 * u, mid + xm - u, bx + 17 * u, mid + xm + u, ink);
        ink.setColor(0x88FFFFFF);
        c.drawRect(bx - 3 * u, mid - peakMm * scale - u, bx + 17 * u, mid - peakMm * scale, ink);
    }
}
