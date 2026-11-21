package com.strickland.jeepdash;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

/** Draws the music visualizer: five looks, seven colour profiles, 16 to 96 bars. Only graphics, no Android views. */
public final class VizPainter {
    public static final int BANDS = 96;                              // how finely the sound is analysed
    public static final int[] COUNTS = {16, 24, 32, 48, 64, 96};    // how many bars are drawn
    public static final String[] LOOKS = {"Blocks", "Smooth", "Boom meters", "Bass ring", "Swirl"};
    public static final String[] COLORS = {"RPM", "Vice", "Factory", "XP", "Fire", "Ice", "Classic"};
    /** low, middle, high, accent. The first profile follows the engine instead. */
    private static final int[][] PROFILES = {
            null,
            {0xFFFF2FA8, 0xFF9B4DFF, 0xFF22E6FF, 0xFFFFE45C},      // Vice: hot pink, purple, cyan
            {0xFF0B6F60, 0xFF19C9A3, 0xFF9DFFE4, 0xFFFF6A1A},      // Factory: cluster teal, orange needle
            {0xFF245EDC, 0xFF2FB5C4, 0xFF7BD14B, 0xFFFFFFFF},      // XP: Luna blue to green
            {0xFFD81E05, 0xFFFF7A0F, 0xFFFFD21F, 0xFFFFFFFF},      // Fire
            {0xFF1B4DFF, 0xFF39C2FF, 0xFFD6F8FF, 0xFFFFFFFF},      // Ice
            {0xFF19C23A, 0xFFE8D21A, 0xFFFF2A1A, 0xFFFFFFFF},      // Classic stereo
    };
    private static final Typeface BOLD = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
    private static final String[] MARKS = {"-20", "-10", "-7", "-5", "-3", "0", "+3"};
    private static final float[] MARK_AT = {0f, 0.22f, 0.36f, 0.48f, 0.60f, 0.72f, 1f};
    private static final int[] FACE = {0xFFFFF4CF, 0xFFE7BE5F};
    private static final float[] FACE_STOPS = {0f, 1f};
    private static final float[] SWEEP_STOPS = {0f, 0.5f, 1f};

    /** A look drawn by another class (the swirl needs off-screen pictures this class does not use). */
    public interface Extra {
        void draw(Canvas c, int w, int h, float dt, float seconds, VizPainter p);
    }

    public Extra swirl;

    // What to draw; the view fills these in before every frame.
    public final float[] levels = new float[BANDS], peaks = new float[BANDS];
    public float vu, bass, beat;
    public Double rpm;                   // null when the Jeep is not connected
    public int look, color, shown = 32;
    public String message;               // shown at the bottom (microphone not allowed yet)
    public final RectF dashButton = new RectF(), colorButton = new RectF(), slider = new RectF();

    private final Paint paint = new Paint();
    private final RectF rect = new RectF();
    private final int[] pal = new int[4], sweep = new int[3];
    private final float[] disp = new float[BANDS], dpeak = new float[BANDS];
    private float needleL, needleR, hue = 200;

    public void draw(Canvas c, int w, int h, float dt, float seconds) {
        palette(dt, seconds);
        fill(0xFF000000);
        c.drawRect(0, 0, w, h, paint);
        // the room lights up with the bass
        paint.setShader(new RadialGradient(w / 2f, h, h * 0.95f, pal[0], pal[0] & 0x00FFFFFF, Shader.TileMode.CLAMP));
        paint.setAlpha(Math.min(255, (int) (40 + 110 * bass + 90 * beat)));
        c.drawRect(0, 0, w, h, paint);

        group(dt);
        if (look == 4 && swirl != null) swirl.draw(c, w, h, dt, seconds, this);
        else if (look == 2) meters(c, w, h, dt);
        else if (look == 3) ring(c, w, h);
        else if (look == 1) smooth(c, w * 0.04f, w * 0.96f, h * 0.14f, h * 0.93f);
        else bars(c, w * 0.04f, w * 0.96f, h * 0.14f, h * 0.93f, 30);

        float u = h / 800f;
        pill(c, dashButton, 18 * u, 16 * u, "DASH", u);
        pill(c, colorButton, dashButton.right + 12 * u, 16 * u, "COLORS: " + COLORS[color].toUpperCase(), u);
        slider(c, w, u);
        text(c, LOOKS[look].toUpperCase(), w - 22 * u, 46 * u, 24 * u, 0x99FFFFFF, Paint.Align.RIGHT);
        if (rpm != null)
            text(c, Math.round(rpm) + " rpm", w - 22 * u, 78 * u, 22 * u, pal[2], Paint.Align.RIGHT);
        if (message != null) text(c, message, w / 2f, h - 30 * u, 26 * u, 0xFFFFFFFF, Paint.Align.CENTER);
    }

    private void palette(float dt, float seconds) {
        int[] p = PROFILES[color];
        if (p != null) {
            System.arraycopy(p, 0, pal, 0, 4);
            return;
        }
        // blue at idle through green and yellow to red near the top; a slow drift when there is no engine data
        float want = rpm == null ? (seconds * 14) % 360 : 200 - 200 * clamp((float) ((rpm - 650) / 3850));
        hue = rpm == null ? want : hue + (want - hue) * Math.min(1, dt * 5);
        pal[0] = hsv(hue, 1f, 0.85f);
        pal[1] = hsv(hue - 22, 0.95f, 1f);
        pal[2] = hsv(hue - 45, 0.55f, 1f);
        pal[3] = 0xFFFFFFFF;
    }

    /** Folds the analysed bands into the bars on screen and glides them between sound frames. */
    private void group(float dt) {
        float k = 1 - (float) Math.exp(-dt * 28);
        for (int j = 0; j < shown; j++) {
            int from = j * BANDS / shown, to = Math.max(from + 1, (j + 1) * BANDS / shown);
            float lv = 0, pk = 0;
            for (int b = from; b < to; b++) {
                lv = Math.max(lv, levels[b]);
                pk = Math.max(pk, peaks[b]);
            }
            disp[j] += (lv - disp[j]) * k;
            dpeak[j] += (pk - dpeak[j]) * k;
        }
    }

    // ---- look 1: retro LED blocks with falling peak caps ----

    private void bars(Canvas c, float left, float right, float top, float base, int segs) {
        float slot = (right - left) / shown, bw = slot * 0.78f, sh = (base - top) / segs;
        float gap = Math.max(1.5f, sh * 0.22f);
        fill(0);
        for (int b = 0; b < shown; b++) {
            float x = left + b * slot + (slot - bw) / 2;
            int lit = Math.round(disp[b] * segs);
            for (int s = 0; s < segs; s++) {
                float y = base - (s + 1) * sh;
                paint.setColor(s < lit ? mix((float) s / (segs - 1)) : 0xFF151515);
                c.drawRect(x, y + gap, x + bw, y + sh, paint);
            }
            if (dpeak[b] > 0.03f) {
                float y = base - Math.min(segs, Math.round(dpeak[b] * segs) + 1) * sh;
                paint.setColor(pal[3]);
                c.drawRect(x, y + gap, x + bw, y + gap + Math.max(3, sh * 0.3f), paint);
            }
        }
    }

    // ---- look 2: smooth bars, one continuous colour sweep, redrawn at the screen's full refresh rate ----

    private void smooth(Canvas c, float left, float right, float top, float base) {
        float slot = (right - left) / shown, bw = slot * 0.72f, full = base - top;
        float round = Math.min(bw / 2, full * 0.012f);
        System.arraycopy(pal, 0, sweep, 0, 3);
        Shader colors = new LinearGradient(0, base, 0, top, sweep, SWEEP_STOPS, Shader.TileMode.CLAMP);
        for (int b = 0; b < shown; b++) {
            float x = left + b * slot + (slot - bw) / 2, tall = Math.max(round * 2, disp[b] * full);
            fill(0xFF000000);
            paint.setShader(colors);
            paint.setAlpha(60);                                     // soft halo
            c.drawRoundRect(set(x - bw * 0.14f, base - tall - bw * 0.14f, x + bw * 1.14f, base), round * 1.6f, round * 1.6f, paint);
            paint.setAlpha(255);
            c.drawRoundRect(set(x, base - tall, x + bw, base), round, round, paint);
            if (dpeak[b] > 0.03f) {
                float y = base - dpeak[b] * full - round * 1.5f;
                fill(pal[3]);
                c.drawRoundRect(set(x, y - round * 0.5f, x + bw, y + round * 0.5f), round * 0.5f, round * 0.5f, paint);
            }
        }
    }

    // ---- look 3: two needle meters, bass on the left, overall level on the right ----

    private void meters(Canvas c, int w, int h, float dt) {
        needleL += (bass - needleL) * Math.min(1, dt * 10);
        needleR += (vu - needleR) * Math.min(1, dt * 10);
        float mw = w * 0.40f, mh = h * 0.56f, y = h * 0.15f;
        meter(c, w * 0.07f, y, mw, mh, needleL, "BASS");
        meter(c, w * 0.53f, y, mw, mh, needleR, "LEVEL");
        bars(c, w * 0.07f, w * 0.93f, h * 0.80f, h * 0.95f, 7);
    }

    private void meter(Canvas c, float x, float y, float mw, float mh, float v, String name) {
        float pad = mh * 0.035f;
        fill(0xFF262626);
        c.drawRoundRect(set(x - pad, y - pad, x + mw + pad, y + mh + pad), pad * 2.4f, pad * 2.4f, paint);
        paint.setShader(new LinearGradient(0, y, 0, y + mh, FACE, FACE_STOPS, Shader.TileMode.CLAMP));
        c.drawRoundRect(set(x, y, x + mw, y + mh), pad * 1.6f, pad * 1.6f, paint);

        float cx = x + mw / 2, cy = y + mh * 1.08f, r = mh * 0.74f, sweep = 76, from = 270 - sweep / 2;
        stroke(0xFF1B1B1B, mh * 0.012f);
        c.drawArc(set(cx - r, cy - r, cx + r, cy + r), from, sweep * 0.72f, false, paint);
        stroke(0xFFC4161C, mh * 0.03f);
        c.drawArc(set(cx - r, cy - r, cx + r, cy + r), from + sweep * 0.72f, sweep * 0.28f, false, paint);
        for (int i = 0; i < MARKS.length; i++) {
            double a = Math.toRadians(-sweep / 2 + sweep * MARK_AT[i]);
            float sx = (float) Math.sin(a), sy = (float) -Math.cos(a);
            int ink = MARK_AT[i] > 0.72f ? 0xFFC4161C : 0xFF1B1B1B;
            stroke(ink, mh * 0.012f);
            c.drawLine(cx + sx * r, cy + sy * r, cx + sx * r * 1.06f, cy + sy * r * 1.06f, paint);
            text(c, MARKS[i], cx + sx * r * 1.13f, cy + sy * r * 1.13f + mh * 0.02f, mh * 0.058f, ink, Paint.Align.CENTER);
        }
        text(c, "VU", cx, y + mh * 0.68f, mh * 0.12f, 0xFF1B1B1B, Paint.Align.CENTER);
        text(c, name, cx, y + mh * 0.80f, mh * 0.065f, 0xFF5A4A22, Paint.Align.CENTER);

        boolean hot = v > 0.9f;
        float lx = x + mw - mh * 0.11f, ly = y + mh * 0.12f;
        if (hot) {
            fill(0);
            paint.setShader(new RadialGradient(lx, ly, mh * 0.11f, 0xAAFF2A1A, 0x00FF2A1A, Shader.TileMode.CLAMP));
            c.drawCircle(lx, ly, mh * 0.11f, paint);
        }
        fill(hot ? 0xFFFF2A1A : 0xFF6B1A14);
        c.drawCircle(lx, ly, mh * 0.035f, paint);

        double a = Math.toRadians(-sweep / 2 + sweep * clamp(v));
        float sx = (float) Math.sin(a), sy = (float) -Math.cos(a);
        float r0 = (cy - (y + mh * 0.95f)) / -sy;                  // start under the cover strip
        stroke(0xFF151515, mh * 0.014f);
        c.drawLine(cx + sx * r0, cy + sy * r0, cx + sx * r * 1.03f, cy + sy * r * 1.03f, paint);
        fill(0xFF262626);
        c.drawRoundRect(set(x, y + mh * 0.88f, x + mw, y + mh), pad * 1.6f, pad * 1.6f, paint);
        fill(pal[1]);
        c.drawRect(x + mw * 0.3f, y + mh * 0.93f, x + mw * 0.3f + mw * 0.4f * clamp(v), y + mh * 0.95f, paint);
    }

    // ---- look 4: the spectrum wrapped round a circle that swells with the bass ----

    private void ring(Canvas c, int w, int h) {
        float m = Math.min(w, h), cx = w / 2f, cy = h * 0.53f;
        float r = m * 0.18f * (1 + 0.12f * bass + 0.07f * beat), maxLen = m * 0.20f;
        int n = shown * 2;
        fill(0);
        paint.setShader(new RadialGradient(cx, cy, r, mix(0.5f), pal[0] & 0x00FFFFFF, Shader.TileMode.CLAMP));
        paint.setAlpha(Math.min(255, (int) (90 + 165 * bass)));
        c.drawCircle(cx, cy, r, paint);
        stroke(pal[0], m * 0.006f);
        c.drawCircle(cx, cy, r, paint);
        float thick = (float) (2 * Math.PI * r / n * 0.55f);
        for (int i = 0; i < n; i++) {
            int b = i < shown ? i : n - 1 - i;                      // bass at the bottom, treble at the top
            double a = Math.PI / 2 + (i + 0.5) * 2 * Math.PI / n;
            float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
            float r1 = r + m * 0.02f, r2 = r1 + m * 0.008f + disp[b] * maxLen;
            stroke(mix(disp[b] * 0.65f + (float) b / shown * 0.35f), thick);
            paint.setStrokeCap(Paint.Cap.ROUND);
            c.drawLine(cx + dx * r1, cy + dy * r1, cx + dx * r2, cy + dy * r2, paint);
            if (dpeak[b] > 0.03f) {
                float rp = r1 + m * 0.02f + dpeak[b] * maxLen;
                fill(pal[3]);
                c.drawCircle(cx + dx * rp, cy + dy * rp, thick * 0.3f, paint);
            }
        }
        if (rpm != null) {
            text(c, String.valueOf(Math.round(rpm)), cx, cy + m * 0.025f, m * 0.075f, 0xFFFFFFFF, Paint.Align.CENTER);
            text(c, "RPM", cx, cy + m * 0.065f, m * 0.028f, 0xCCFFFFFF, Paint.Align.CENTER);
        }
    }

    // ---- small helpers ----

    private void slider(Canvas c, int w, float u) {
        float x0 = w * 0.40f, x1 = w * 0.60f, y = 39 * u;
        slider.set(x0 - 30 * u, y - 30 * u, x1 + 30 * u, y + 30 * u);
        int at = 0;
        for (int i = 0; i < COUNTS.length; i++) if (COUNTS[i] == shown) at = i;
        float knob = x0 + (x1 - x0) * at / (COUNTS.length - 1);
        stroke(0x55FFFFFF, 6 * u);
        paint.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(x0, y, x1, y, paint);
        stroke(0xFFFFFFFF, 6 * u);
        paint.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(x0, y, knob, y, paint);
        fill(0xFFFFFFFF);
        c.drawCircle(knob, y, 13 * u, paint);
        text(c, shown + " BARS", x1 + 26 * u, y + 7 * u, 20 * u, 0xCCFFFFFF, Paint.Align.LEFT);
    }

    /** A finger on the slider at x picks the nearest bar count. */
    public void slide(float x) {
        float t = clamp((x - (slider.left + slider.height() / 2)) / (slider.width() - slider.height()));
        shown = COUNTS[Math.round(t * (COUNTS.length - 1))];
    }

    private void pill(Canvas c, RectF where, float x, float y, String label, float u) {
        paint.reset();
        paint.setTypeface(BOLD);
        paint.setTextSize(20 * u);
        float tw = paint.measureText(label);
        where.set(x, y, x + tw + 36 * u, y + 46 * u);
        fill(0x55FFFFFF);
        c.drawRoundRect(where, 23 * u, 23 * u, paint);
        text(c, label, x + 18 * u, y + 30 * u, 20 * u, 0xFFFFFFFF, Paint.Align.LEFT);
    }

    private void text(Canvas c, String s, float x, float baseline, float size, int color, Paint.Align align) {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setColor(color);
        paint.setTypeface(BOLD);
        paint.setTextSize(size);
        paint.setTextAlign(align);
        c.drawText(s, x, baseline, paint);
    }

    private void fill(int color) {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
    }

    private void stroke(int color, float width) {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setColor(color);
    }

    private RectF set(float l, float t, float r, float b) {
        rect.set(l, t, r, b);
        return rect;
    }

    /** Height of bar j on screen, 0..1 (for looks drawn elsewhere). */
    public float bar(int j) { return disp[j]; }

    public int color(float t) { return mix(t); }

    public int accent() { return pal[3]; }

    /** The profile's colour at t: 0 = low, 0.5 = middle, 1 = high. */
    private int mix(float t) {
        t = clamp(t);
        return t < 0.5f ? lerp(pal[0], pal[1], t * 2) : lerp(pal[1], pal[2], t * 2 - 1);
    }

    static float clamp(float x) { return x < 0 ? 0 : Math.min(x, 1); }

    static int lerp(int a, int b, float t) {
        int out = 0xFF000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            int ca = (a >> shift) & 0xff, cb = (b >> shift) & 0xff;
            out |= Math.round(ca + (cb - ca) * t) << shift;
        }
        return out;
    }

    static int hsv(float h, float s, float v) {
        h = ((h % 360) + 360) % 360;
        float ch = v * s, x = ch * (1 - Math.abs(h / 60 % 2 - 1)), m = v - ch, r, g, b;
        switch ((int) (h / 60)) {
            case 0: r = ch; g = x; b = 0; break;
            case 1: r = x; g = ch; b = 0; break;
            case 2: r = 0; g = ch; b = x; break;
            case 3: r = 0; g = x; b = ch; break;
            case 4: r = x; g = 0; b = ch; break;
            default: r = ch; g = 0; b = x; break;
        }
        return 0xFF000000 | Math.round((r + m) * 255) << 16 | Math.round((g + m) * 255) << 8 | Math.round((b + m) * 255);
    }
}
