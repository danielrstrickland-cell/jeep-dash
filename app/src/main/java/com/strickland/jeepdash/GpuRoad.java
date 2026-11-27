package com.strickland.jeepdash;

import android.annotation.TargetApi;
import android.os.Build;

import com.strickland.jeepdash.core.VizTune;

/**
 * The music road: a night road running toward a striped sun, past sidewalks and street lamps, between
 * buildings that are the equalizer: the bass on the left, the treble on the right, with different shapes and
 * spacing so the two sides never mirror. Each building keeps its band the whole way past. It moves at the Jeep's real speed
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
            + "uniform float bassLv[16];\n"
            + "uniform float trebleLv[16];\n"
            + "uniform float city;\n"
            + "uniform float bwidth;\n"
            + "uniform float baseL;\n"
            + "uniform float baseR;\n"
            + "float hash1(float v) { return fract(sin(v * 12.9898) * 43758.5453); }\n"
            // the level of building slot k (0..15) on one side; -1 when there is no such slot
            + "float levelOf(float k, bool isLeft) {\n"
            + "    float level = -1.0;\n"
            + "    for (int i = 0; i < 16; i++) if (abs(float(i) - k) < 0.5) level = isLeft ? bassLv[i] : trebleLv[i];\n"
            + "    return level;\n"
            + "}\n"
            // one city building: a 3D block with a facade along the road and an end facing the camera
            + "half3 building(float2 q, float hor, float zi, float sp, float id, float k, bool isLeft, half3 col) {\n"
            // cheap checks first: below the street at the building's near end, or above the tallest roof possible
            + "    float ybNear = hor + 0.25 / zi;\n"
            + "    if (q.y > ybNear || q.y < ybNear - 6.6 * build * 0.25 / zi) return col;\n"
            + "    float level = levelOf(k, isLeft);\n"
            + "    float h1 = hash1(id * 1.31 + (isLeft ? 0.0 : 57.3));\n"
            + "    float h2 = hash1(id * 2.17 + 11.1);\n"
            + "    float h3 = hash1(id * 3.71 + 5.7);\n"
            + "    float h4 = hash1(id * 4.13 + 9.2);\n"
            + "    float h5 = hash1(id * 5.97 + 3.3);\n"
            + "    float d = min(0.95 * sp, sp * (0.55 + 0.35 * h1) * bwidth);\n"
            + "    float thick = (1.6 + 1.6 * h2) * bwidth;\n"
            + "    float style = isLeft ? (h3 < 0.45 ? 0.0 : (h3 < 0.8 ? 1.0 : 2.0)) : (h3 < 0.35 ? 0.0 : (h3 < 0.55 ? 1.0 : 2.0));\n"
            + "    float base = style < 0.5 ? 2.2 + 3.0 * h4 : (style < 1.5 ? 1.0 + 0.8 * h4 : 1.6 + 1.6 * h4);\n"
            + "    float hgt = (base * (isLeft ? 1.15 : 1.0) + level * 0.5) * build;\n"
            + "    float zm = zi + d * 0.5;\n"
            + "    float off = curve * zm * zm * 0.02;\n"
            + "    float xs = isLeft ? -1.9 : 1.9;\n"
            + "    float side = isLeft ? -1.0 : 1.0;\n"
            + "    float wu = -1.0;\n"                                         // where on the building, in world units
            + "    float wv = 0.0;\n"
            + "    float shade = 1.0;\n"
            + "    bool endFace = false;\n"
            + "    float zf = (xs + off) / q.x;\n"                             // the facade, along the road
            + "    if (q.x * side > 0.0 && zf > zi && zf < zi + d) {\n"
            + "        float yb = hor + 0.25 / zf;\n"
            + "        float yt = yb - hgt * 0.25 / zf;\n"
            + "        if (q.y < yb && q.y > yt) { wu = zf - zi; wv = (yb - q.y) / (yb - yt) * hgt; }\n"
            + "    }\n"
            + "    float xw = q.x * zi - off;\n"                                // the end facing the camera
            + "    float across = (xw - xs) * side;\n"
            + "    if (across > 0.0 && across < thick) {\n"
            + "        float yb = hor + 0.25 / zi;\n"
            + "        float yt = yb - hgt * 0.25 / zi;\n"
            + "        float v = (yb - q.y) / (yb - yt);\n"
            + "        bool stepped = style < 0.5 && h5 > 0.5 && v > 0.78 && across > thick * 0.7;\n"
            + "        bool mast = style < 0.5 && h5 > 0.75 && abs(across - thick * 0.4) < 0.03 && v > 1.0 && v < 1.0 + 0.6 / hgt;\n"
            + "        if (mast) return mix(half3(0.35, 0.35, 0.4), col, half(clamp(zi / 14.0, 0.0, 1.0)));\n"
            + "        if (q.y < yb && q.y > yt && !stepped) { wu = across; wv = v * hgt; shade = 0.72; endFace = true; }\n"
            + "    }\n"
            + "    if (wu < 0.0) return col;\n"
            + "    half3 wall = style < 0.5 ? half3(0.07, 0.1, 0.15) : (style < 1.5 ? half3(0.2, 0.1, 0.07) : half3(0.12, 0.12, 0.14));\n"
            + "    half3 lamp = style < 1.5 && style > 0.5 ? half3(1.0, 0.8, 0.5) : half3(0.75, 0.9, 1.0);\n"
            + "    half3 tint = isLeft ? c0.rgb : c2.rgb;\n"
            + "    float floorH = style > 0.5 && style < 1.5 ? 0.2 : 0.16;\n"
            + "    float fl = floor(wv / floorH);\n"
            + "    float floors = floor(hgt / floorH);\n"
            + "    float fy = fract(wv / floorH);\n"
            + "    float fx = fract(wu / 0.22);\n"
            + "    bool pane = style > 1.5 ? (fy > 0.35 && fy < 0.8) : (fy > 0.3 && fy < 0.85 && fx > 0.2 && fx < 0.8);\n"
            + "    float rnd = hash1(fl * 7.1 + floor(wu / 0.22) * 3.3 + id * 1.7);\n"
            + "    float litTo = level * floors * 1.1;\n"                       // lit floors climb with the music
            + "    half3 c = wall * half(shade);\n"
            + "    if (wv < 0.18) {\n"                                          // the shop at street level
            + "        c = tint * half(0.55 + 0.4 * beat) * half(fx > 0.1 && fx < 0.9 ? 1.0 : 0.3);\n"
            + "    } else if (pane) {\n"
            + "        bool lit = (fl < litTo && rnd > 0.12) || rnd > 0.94;\n"
            + "        bool peak = fl < litTo && fl > litTo - 1.0;\n"
            + "        c = lit ? mix(lamp, tint, half(peak ? 0.7 : 0.2)) * half(endFace ? 0.8 : 1.0) : wall * half(0.55);\n"
            + "    }\n"
            + "    if (wv > hgt - 0.03) c = wall * half(2.2);\n"                // the roof edge
            + "    return mix(c, col, half(clamp(zi / 14.0, 0.0, 1.0)));\n"
            + "}\n"
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
            + "        } else if (ax < 1.6) {\n"
            + "            float joint = step(0.94, fract(wz * 1.2));\n"
            + "            g = half3(0.11, 0.11, 0.13) * half(1.0 - 0.45 * joint);\n"
            + "            g += half3(0.35, 0.35, 0.4) * half(1.0 - smoothstep(0.0, 0.03 + px, ax - 1.0));\n"
            + "        } else {\n"
            + "            float line = step(0.95, fract(wz)) + step(0.95, fract(x * 0.5));\n"
            + "            g += c0.rgb * half(0.45 * min(line, 1.0));\n"
            + "        }\n"
            + "        float dz = (fract(wz / 1.5) - 0.5) * 1.5;\n"                      // street-lamp light on the ground
            + "        float pool = exp(-(dz * dz + (ax - 1.15) * (ax - 1.15)) * 2.5);\n"
            + "        g += half3(1.0, 0.78, 0.45) * half(pool * 0.22 * (ax < 1.6 ? 1.0 : 0.4));\n"
            + "        col = mix(g, col, half(clamp(z / 14.0, 0.0, 1.0)));\n"
            + "    }\n"
            // buildings, far to near. City: 3D blocks on 1.6-unit plots. Classic: the old flat bars on 0.9.
            + "    float sp = city > 0.5 ? 1.6 : 0.9;\n"
            // only the plots that can be under this pixel are looked at: a pixel at screen x sees world x = x*z
            // (less the bend), so the buildings' near and far edges give the depths, and from those the plots
            + "    for (int side = 0; side < 2; side++) {\n"
            + "        bool isLeft = side == 0;\n"
            + "        float sgn = isLeft ? -1.0 : 1.0;\n"
            + "        float zb = 1.0 - fract(travel / sp + (isLeft ? 0.0 : 0.5)) * sp + (isLeft ? 0.0 : sp * 0.5);\n"
            + "        float idb = isLeft ? baseL : baseR;\n"
            + "        float inner = 1.7;\n"
            + "        float outer = city > 0.5 ? 1.9 + 3.3 * bwidth : 3.6;\n"
            + "        float qx = q.x * sgn;\n"
            + "        float zEst = inner / max(abs(q.x), 0.01);\n"
            + "        qx -= sgn * curve * min(zEst, 15.0) * 0.02;\n"               // allow for the bend
            + "        if (qx > 0.005) {\n"
            + "            float zNear = inner / qx;\n"
            + "            float zFar = min(outer / qx, 15.5);\n"
            + "            float kFar = floor((zFar - zb) / sp) + 1.0;\n"
            + "            float kNear = floor((zNear - zb) / sp) - 1.0;\n"
            + "            for (int j = 0; j < 6; j++) {\n"
            + "                float k = kFar - float(j);\n"
            + "                if (k >= max(kNear, 0.0) && k <= 15.0) {\n"
            + "                    float zi = zb + k * sp;\n"
            + "                    if (zi >= 0.3) {\n"
            + "                        if (city > 0.5) {\n"
            + "                            col = building(q, hor, zi, sp, idb + k, k, isLeft, col);\n"
            + "                        } else {\n"
            + "                            float level = levelOf(k, isLeft);\n"
            + "                            float bh = (isLeft ? 0.5 + 2.8 * level : 0.3 + 2.2 * level) * build;\n"
            + "                            float yb = hor + 0.25 / zi;\n"
            + "                            float yt = yb - bh * 0.25 / zi;\n"
            + "                            float off = curve * zi * zi * 0.02;\n"
            + "                            float x0 = isLeft ? (-3.4 + off) / zi : (1.9 + off) / zi;\n"
            + "                            float x1 = isLeft ? (-1.9 + off) / zi : (3.0 + off) / zi;\n"
            + "                            if (q.y < yb && q.y > yt && q.x > x0 && q.x < x1) {\n"
            + "                                float u = (q.x - x0) / (x1 - x0);\n"
            + "                                float v = (yb - q.y) / (yb - yt);\n"
            + "                                half3 tint = isLeft ? c0.rgb : c2.rgb;\n"
            + "                                float win = isLeft ? step(0.35, fract(u * 4.0)) * step(0.45, fract(v * bh * 2.5))\n"
            + "                                                   : step(0.5, fract(u * 7.0)) * step(0.3, fract(v * bh * 4.0));\n"
            + "                                half3 b = half3(0.03, 0.03, 0.05) + tint * half(win * (0.3 + 0.9 * level));\n"
            + "                                float rim = step(0.95, v) + step(u, 0.04) + step(0.96, u);\n"
            + "                                b += tint * half(min(rim, 1.0) * (0.5 + beat));\n"
            + "                                col = mix(b, col, half(clamp(zi / 14.0, 0.0, 1.0)));\n"
            + "                            }\n"
            + "                        }\n"
            + "                    }\n"
            + "                }\n"
            + "            }\n"
            + "        }\n"
            + "    }\n"
            // street lamps: only the two or three posts that can be at this spot are drawn
            + "    float ph = fract(travel / 1.5) * 1.5;\n"
            + "    float zp = 1.35 / max(abs(q.x), 0.02);\n"
            + "    float n0 = floor((zp + ph) / 1.5 - 1.0);\n"
            + "    float sx = q.x < 0.0 ? -1.35 : 1.35;\n"
            + "    for (int j = 2; j >= 0; j--) {\n"
            + "        float n = n0 + float(j) - 1.0;\n"
            + "        float zk = (n + 1.0) * 1.5 - ph;\n"
            + "        if (n >= 0.0 && n < 12.0 && zk > 0.35) {\n"
            + "            float off = curve * zk * zk * 0.02;\n"
            + "            float yb = hor + 0.25 / zk;\n"
            + "            float yt = yb - 1.35 * 0.25 / zk;\n"
            + "            float fog = clamp(zk / 14.0, 0.0, 1.0);\n"
            + "            float px0 = (sx + off) / zk;\n"
            + "            float hw = 0.022 / zk;\n"
            + "            if (abs(q.x - px0) < hw && q.y < yb && q.y > yt) col = mix(half3(0.16, 0.16, 0.19), col, half(fog));\n"
            + "            float lx = (sx * 0.86 + off) / zk;\n"
            + "            if (q.y < yt + 0.02 / zk && q.y > yt - 0.005 / zk && (q.x - px0) * (lx - px0) > 0.0\n"
            + "                && abs(q.x - px0) < abs(lx - px0)) col = mix(half3(0.16, 0.16, 0.19), col, half(fog));\n"
            + "            float2 lamp = (q - float2(lx, yt + 0.02 / zk)) * zk;\n"
            + "            float l2 = dot(lamp, lamp);\n"
            + "            if (l2 < 0.1) col += half3(1.0, 0.82, 0.5) * half((exp(-l2 * 900.0) + 0.25 * exp(-l2 * 60.0)) * (1.0 - fog));\n"
            + "        }\n"
            + "    }\n"
            + "    return half4(col, 1.0);\n"
            + "}\n";

    private static final float MPH_PER_G_SECOND = 21.94f, MPS_PER_MPH = 0.44704f;
    private static final float LOOP = 720;                              // whole buildings, posts and bands: no jump
    private final float[] bassLv = new float[BANDS], trebleLv = new float[BANDS];
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
        // each building keeps one band for as long as it is on screen: building number (counted along the
        // road) picks the band, so heights only change with the music, never as it scrolls
        travel %= LOOP;
        int half = p.shown / 2;                                         // low half of the bars left, high half right
        boolean city = p.tune.on(look, "city");
        float sp = city ? 1.6f : 0.9f;                                  // plot length along the road
        long left = (long) Math.floor(travel / sp), right = (long) Math.floor(travel / sp + 0.5f);
        for (int i = 0; i < BANDS; i++) {
            bassLv[i] = p.bar((int) Math.floorMod(left + i, (long) BANDS) * half / BANDS);
            trebleLv[i] = p.bar(half + (int) Math.floorMod(right + i, (long) BANDS) * half / BANDS);
        }
        shader.setFloatUniform("time", seconds);
        shader.setFloatUniform("travel", travel);
        shader.setFloatUniform("curve", curve);
        shader.setFloatUniform("bass", p.bass);
        shader.setFloatUniform("beat", p.beat);
        shader.setFloatUniform("build", p.tune.get(look, "build"));
        shader.setFloatUniform("tilt", motion.sloshX());
        shader.setFloatUniform("bassLv", bassLv);
        shader.setFloatUniform("trebleLv", trebleLv);
        shader.setFloatUniform("city", city ? 1 : 0);
        shader.setFloatUniform("bwidth", p.tune.get(look, "bwidth"));
        shader.setFloatUniform("baseL", left % 1000);
        shader.setFloatUniform("baseR", right % 1000);
    }
}
