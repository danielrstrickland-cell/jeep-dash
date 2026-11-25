package com.strickland.jeepdash;

import android.annotation.TargetApi;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Build;

import com.strickland.jeepdash.core.VizTune;

/**
 * Street racer: an original low tuner car racing a neon track at night, underglow pulsing with the bass, the
 * car bouncing on hits, a skyline that kicks with the music, and an angular racing readout (beats a minute,
 * speed, rpm). Moves at the Jeep's speed and leans into real turns, like the road. The track is drawn by the
 * graphics chip; the car and readout are drawn sharp on top.
 */
@TargetApi(Build.VERSION_CODES.TIRAMISU)
final class GpuRacer extends GpuLook {
    private static final String SHADER = ""
            + "uniform float time;\n"
            + "uniform float travel;\n"
            + "uniform float curve;\n"
            + "uniform float bass;\n"
            + "uniform float beat;\n"
            + "uniform float under;\n"
            + "uniform float2 car;\n"                                     // x across the track, z depth
            + "half4 main(float2 p) {\n"
            + "    float2 q = (p - 0.5 * size) / size.y;\n"
            + "    float hor = -0.08;\n"
            + "    float up = hor - q.y;\n"
            + "    half3 col = mix(c1.rgb * 0.25, half3(0.01, 0.0, 0.03), half(clamp(up * 2.5, 0.0, 1.0)));\n"
            + "    float2 st = floor(q * 120.0);\n"
            + "    float star = fract(sin(dot(st, float2(12.9898, 78.233))) * 43758.5453);\n"
            + "    if (q.y < hor && star > 0.995) col += half3(0.6) * half(0.5 + 0.5 * sin(time * 3.0 + star * 50.0));\n"
            + "    float tower = fract(sin(floor(q.x * 30.0 + curve * 0.3) * 91.7) * 437.0);\n"
            + "    float sky = hor - 0.02 - 0.06 * tower * (1.0 + 0.6 * bass);\n"
            + "    if (q.y < hor && q.y > sky) col = half3(0.02, 0.01, 0.05) + c2.rgb * half(0.1 + 0.15 * beat);\n"
            + "    if (q.y > hor + 0.0005) {\n"
            + "        float z = 0.22 / (q.y - hor);\n"
            + "        float x = q.x * z - curve * z * z * 0.02;\n"
            + "        float wz = z + travel;\n"
            + "        float ax = abs(x);\n"
            + "        float px = z * 1.5 / size.y;\n"
            + "        half3 g = half3(0.02, 0.015, 0.05);\n"
            + "        float grid = step(0.47, max(abs(fract(x * 0.5) - 0.5), abs(fract(wz * 0.5) - 0.5)));\n"
            + "        g += c1.rgb * half(0.3 * grid);\n"
            + "        if (ax < 1.2) {\n"
            + "            g = half3(0.04, 0.03, 0.08);\n"
            + "            g += half3(1.0, 0.3, 0.85) * half(step(0.5, fract(wz * 0.6)) * (1.0 - smoothstep(0.02, 0.02 + px, ax)));\n"
            + "        }\n"
            + "        float edge = 1.0 - smoothstep(0.0, 0.05 + px, abs(ax - 1.2));\n"
            + "        g += (x < 0.0 ? c0.rgb : c2.rgb) * half(edge * (0.8 + bass));\n"
            + "        float2 d = float2(x - car.x, (z - car.y) * 1.8);\n"            // underglow on the tarmac
            + "        g += c1.rgb * half(exp(-dot(d, d) * 5.0) * under * (0.35 + bass + 0.5 * beat));\n"
            + "        col = mix(g, col, half(clamp(z / 16.0, 0.0, 1.0)));\n"
            + "    }\n"
            + "    return half4(col, 1.0);\n"
            + "}\n";

    private static final float MPH_PER_G_SECOND = 21.94f, MPS_PER_MPH = 0.44704f, LOOP = 720;
    private static final float[] BODY = {115, 160, 130, 140, 165, 128, 205, 128, 235, 140, 248, 160};
    private static final float[] GLASS = {140, 140, 168, 131, 200, 131, 222, 140};
    private static final float[] SKIRT = {115, 160, 248, 160, 244, 166, 119, 166};
    private final VizMotion motion;
    private final Path path = new Path();
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] beats = new float[9];
    private float mph, travel, curve, lastBeat, clock, bounce;
    private int beatCount;

    GpuRacer(VizMotion motion) {
        super("racer", SHADER);
        this.motion = motion;
    }

    @Override
    protected void uniforms(VizPainter p, int lw, int lh, float dt, float seconds) {
        int look = VizTune.RACER;
        clock = seconds;
        if (p.beat > 0.9f && lastBeat <= 0.9f) {                       // remember beat times for the BPM readout
            System.arraycopy(beats, 0, beats, 1, beats.length - 1);
            beats[0] = seconds;
            beatCount++;
        }
        lastBeat = p.beat;
        Double jeep = p.speed;
        if (jeep != null) {
            mph += motion.forwardG() * MPH_PER_G_SECOND * dt;
            mph += (jeep.floatValue() - mph) * Math.min(1, dt * 2.5f);
        } else {
            mph += (35 + 45 * p.vu - mph) * Math.min(1, dt * 0.8f);
        }
        mph = Math.max(0, mph);
        float mps = mph * MPS_PER_MPH;
        travel = (travel + mps * 0.3f * p.tune.get(look, "pace") * dt) % LOOP;
        float want = Math.max(-6, Math.min(6, -motion.yaw / Math.max(4, mps) * 60));
        curve += (want - curve) * Math.min(1, dt * 8);
        bounce += ((p.beat * 7 + p.bass * 3) - bounce) * Math.min(1, dt * 18);
        shader.setFloatUniform("time", seconds);
        shader.setFloatUniform("travel", travel);
        shader.setFloatUniform("curve", curve);
        shader.setFloatUniform("bass", p.bass);
        shader.setFloatUniform("beat", p.beat);
        shader.setFloatUniform("under", p.tune.get(look, "under"));
        shader.setFloatUniform("car", curve * 0.009f, 0.22f / (0.38f - (-0.08f)));   // under the car on screen
    }

    /** Beats a minute from the gaps between recent bass hits, or 0 when there are too few. */
    private int bpm() {
        int n = Math.min(beatCount, beats.length) - 1;
        if (n < 3 || clock - beats[0] > 3) return 0;
        float[] gaps = new float[n];
        for (int i = 0; i < n; i++) gaps[i] = beats[i] - beats[i + 1];
        java.util.Arrays.sort(gaps);
        float gap = gaps[n / 2];
        while (gap > 0 && gap < 0.33f) gap *= 2;                         // half-beats: count whole beats
        return gap <= 0 ? 0 : Math.round(60 / gap);
    }

    @Override
    protected void overlay(Canvas c, int w, int h, VizPainter p) {
        float u = h / 800f, k = w * 0.36f / 133f;
        float cx = w / 2f + curve * w * 0.012f, base = h * 0.94f - bounce * u;
        int glow = p.color(0.5f), lo = p.color(0), hi = p.color(1);
        shape(c, SKIRT, cx, base, k, 0, glow);
        shape(c, BODY, cx, base, k, (curve * 0.6f), 0xFF1B1340);
        shape(c, GLASS, cx, base, k, (curve * 0.6f), 0x8C3FD0FF);
        ink.setStyle(Paint.Style.FILL);
        boolean braking = p.sloshY < -0.15f;
        ink.setColor(braking ? 0xFFFF2040 : 0xFFB0102A);
        c.drawRect(cx + (124 - 181.5f) * k, base + (150 - 166) * k, cx + (138 - 181.5f) * k, base + (154 - 166) * k, ink);
        c.drawRect(cx + (226 - 181.5f) * k, base + (150 - 166) * k, cx + (240 - 181.5f) * k, base + (154 - 166) * k, ink);
        ink.setColor(0xFF0A0A12);                                        // tyres
        c.drawOval(cx + (126 - 181.5f) * k, base + (158 - 166) * k, cx + (150 - 181.5f) * k, base + (170 - 166) * k, ink);
        c.drawOval(cx + (214 - 181.5f) * k, base + (158 - 166) * k, cx + (238 - 181.5f) * k, base + (170 - 166) * k, ink);
        ink.setStyle(Paint.Style.STROKE);                                // spoiler
        ink.setStrokeWidth(3 * u);
        ink.setColor(lo);
        c.drawLine(cx + (120 - 181.5f) * k, base + (136 - 166) * k, cx + (243 - 181.5f) * k, base + (136 - 166) * k, ink);
        if (!p.tune.on(VizTune.RACER, "hud")) return;
        int b = bpm();
        Double rpm = p.rpm;
        panel(c, 24 * u, h - 80 * u, 210 * u, 56 * u, lo, (b > 0 ? b : "--") + " BPM", u, false);
        String right = Math.round(mph) + " MPH" + (rpm != null ? "  ·  " + Math.round(rpm) + " RPM" : "");
        panel(c, w - 24 * u - 330 * u, h - 80 * u, 330 * u, 56 * u, hi, right, u, true);
    }

    /** A filled outline from the car drawing, placed and sized on screen, sheared a little into turns. */
    private void shape(Canvas c, float[] pts, float cx, float base, float k, float lean, int color) {
        path.rewind();
        for (int i = 0; i < pts.length; i += 2) {
            float x = cx + (pts[i] - 181.5f) * k + (166 - pts[i + 1]) * k * lean * 0.05f;
            float y = base + (pts[i + 1] - 166) * k;
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        path.close();
        ink.setStyle(Paint.Style.FILL);
        ink.setColor(color);
        c.drawPath(path, ink);
    }

    /** An angular racing-game readout: a slanted glowing box with bold text. */
    private void panel(Canvas c, float x, float y, float pw, float ph, int color, String text, float u, boolean flip) {
        float s = 18 * u;
        path.rewind();
        if (!flip) {
            path.moveTo(x, y);
            path.lineTo(x + pw, y);
            path.lineTo(x + pw - s, y + ph);
            path.lineTo(x, y + ph);
        } else {
            path.moveTo(x + s, y);
            path.lineTo(x + pw, y);
            path.lineTo(x + pw, y + ph);
            path.lineTo(x, y + ph);
        }
        path.close();
        ink.setStyle(Paint.Style.FILL);
        ink.setColor((color & 0x00FFFFFF) | 0x44000000);
        c.drawPath(path, ink);
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeWidth(2.5f * u);
        ink.setColor(color);
        c.drawPath(path, ink);
        ink.setStyle(Paint.Style.FILL);
        ink.setColor(0xFFFFFFFF);
        ink.setTextSize(26 * u);
        ink.setFakeBoldText(true);
        ink.setTextSkewX(-0.2f);
        ink.setTextAlign(Paint.Align.CENTER);
        c.drawText(text, x + pw / 2, y + ph / 2 + 9 * u, ink);
        ink.setTextSkewX(0);
        ink.setFakeBoldText(false);
    }
}
