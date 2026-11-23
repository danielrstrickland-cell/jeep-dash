package com.strickland.jeepdash.theme;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import com.strickland.jeepdash.core.Param;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Drawing helpers shared by both looks. */
final class Art {
    private Art() { }

    static final Typeface MONO = Typeface.MONOSPACE;
    static final Typeface SANS = Typeface.DEFAULT;
    static final Typeface BOLD = Typeface.DEFAULT_BOLD;
    static final Typeface ITALIC_BOLD = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC);
    static final Typeface COND = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    static final String DASH = "—";

    private static final Paint PAINT = new Paint();
    private static final RectF RECT = new RectF();
    private static final Map<String, Shader> SHADERS = new HashMap<>();

    /** The shared paint, reset. Drawing happens on the UI thread only. */
    static Paint paint() {
        PAINT.reset();
        PAINT.setAntiAlias(true);
        return PAINT;
    }

    static RectF rect(float l, float t, float r, float b) {
        RECT.set(l, t, r, b);
        return RECT;
    }

    static Shader vertical(float y0, float y1, int[] colors, float[] stops) {
        String key = "v" + y0 + "," + y1 + "," + colors[0] + "," + colors[colors.length - 1] + "," + colors.length;
        Shader s = SHADERS.get(key);
        if (s == null) {
            if (SHADERS.size() > 200) SHADERS.clear();
            s = new LinearGradient(0, y0, 0, y1, colors, stops, Shader.TileMode.CLAMP);
            SHADERS.put(key, s);
        }
        return s;
    }

    static Shader radial(float cx, float cy, float r, int inner, int outer) {
        String key = "r" + cx + "," + cy + "," + r + "," + inner + "," + outer;
        Shader s = SHADERS.get(key);
        if (s == null) {
            if (SHADERS.size() > 200) SHADERS.clear();
            s = new RadialGradient(cx, cy, Math.max(1, r), inner, outer, Shader.TileMode.CLAMP);
            SHADERS.put(key, s);
        }
        return s;
    }

    // ---------------- text ----------------

    static void text(Canvas c, String s, float x, float baseline, float size, int color, Typeface tf, Paint.Align align) {
        Paint p = paint();
        p.setTypeface(tf);
        p.setTextSize(size);
        p.setColor(color);
        p.setTextAlign(align);
        c.drawText(s, x, baseline, p);
    }

    static float width(String s, float size, Typeface tf) {
        Paint p = paint();
        p.setTypeface(tf);
        p.setTextSize(size);
        return p.measureText(s);
    }

    /** The largest size up to {@code size} at which the text is no wider than maxW. */
    static float fit(String s, float size, float maxW, Typeface tf) {
        float w = width(s, size, tf);
        if (w <= maxW || w <= 0) return size;
        return Math.max(6, size * maxW / w);
    }

    /** Baseline that centres text of this size on cy. */
    static float centre(float cy, float size, Typeface tf) {
        Paint p = paint();
        p.setTypeface(tf);
        p.setTextSize(size);
        Paint.FontMetrics m = p.getFontMetrics();
        return cy - (m.ascent + m.descent) / 2;
    }

    // ---------------- readings ----------------

    /** True for a value that is only ever a whole number (the gear). */
    static boolean whole(Param p) { return "gear".equals(p.id); }

    static String format(Param p, double v) {
        if (whole(p)) return String.valueOf(Math.round(v));
        double range = p.max - p.min;
        String f = range <= 10 ? "%.2f" : range <= 100 ? "%.1f" : "%.0f";
        return String.format(Locale.US, f, v);
    }

    /** Scale-end labels: "100", "2.5", "-10" (no padding zeros). */
    static String compact(double v) {
        if (Math.abs(v - Math.round(v)) < 0.001) return String.valueOf(Math.round(v));
        String s = String.format(Locale.US, "%.2f", v);
        return s.endsWith("0") ? s.substring(0, s.length() - 1) : s;
    }

    static String reading(Param p, Double v) { return v == null ? DASH : format(p, v); }

    /** How far along its scale a reading is, 0..1. */
    static float frac(Param p, double v) {
        double f = (v - p.min) / (p.max - p.min);
        return (float) Math.max(0, Math.min(1, f));
    }

    /** Tick spacing for a dial. */
    static final class Scale {
        double major, minor, labelEvery, labelDiv = 1;
        Double redFrom;
    }

    static Scale scale(Param p) {
        Scale s = new Scale();
        switch (p.id) {
            case "rpm": s.major = 1000; s.minor = 250; s.labelEvery = 1000; s.labelDiv = 1000; s.redFrom = 5000.0; break;
            case "speed": case "gps_mph": s.major = 10; s.minor = 5; s.labelEvery = 20; break;
            case "coolant": s.major = 40; s.minor = 20; s.labelEvery = 80; s.redFrom = 230.0; break;
            case "iat": s.major = 50; s.minor = 25; s.labelEvery = 100; break;
            case "spark": s.major = 10; s.minor = 5; s.labelEvery = 25; break;
            case "map_v": s.major = 1; s.minor = 0.5; s.labelEvery = 2.5; break;
            case "throttle": s.major = 25; s.minor = 12.5; s.labelEvery = 50; break;
            case "map_kpa": s.major = 20; s.minor = 10; s.labelEvery = 40; break;
            default:
                s.major = nice((p.max - p.min) / 5);
                s.minor = s.major / 2;
                s.labelEvery = s.major * 2;
        }
        return s;
    }

    private static double nice(double x) {
        if (x <= 0) return 1;
        double mag = Math.pow(10, Math.floor(Math.log10(x)));
        double n = x / mag;
        return (n < 1.5 ? 1 : n < 3.5 ? 2 : n < 7.5 ? 5 : 10) * mag;
    }

    private static boolean whole(double a, double b) { return Math.abs(a / b - Math.round(a / b)) < 0.001; }

    // ---------------- dial ----------------

    static final class DialStyle {
        int bezel, faceInner, faceOuter, tick, label, needle, hub, hubRing, red;
        float minorAlpha = 1f;
        Typeface font = BOLD;
        float labelSize = 0.13f;       // of the radius
    }

    /** A 270-degree dial of radius R centred on (cx, cy), as in the approved mockups. */
    static void dial(Canvas c, float cx, float cy, float R, Param p, Double shown, DialStyle st) {
        Scale sc = scale(p);
        Paint pt = paint();
        pt.setColor(st.bezel);
        c.drawCircle(cx, cy, R * 0.985f, pt);
        pt.setShader(radial(cx, cy - R * 0.1f, R * 1.2f, st.faceInner, st.faceOuter));
        c.drawCircle(cx, cy, R * 0.94f, pt);
        pt.setShader(null);

        float r = R * 0.9f;
        if (sc.redFrom != null) {
            float rr = r - R * 0.03f;
            float a0 = 135 + 270 * frac(p, sc.redFrom);
            pt.setStyle(Paint.Style.STROKE);
            pt.setStrokeWidth(Math.max(2, R * 0.06f));
            pt.setColor(st.red);
            c.drawArc(rect(cx - rr, cy - rr, cx + rr, cy + rr), a0, 405 - a0, false, pt);
        }

        int steps = (int) Math.floor((p.max - p.min) / sc.minor + 0.001);
        for (int i = 0; i <= steps; i++) {
            double v = p.min + i * sc.minor;
            boolean major = whole(v - p.min, sc.major);
            double a = Math.toRadians(135 + 270.0 * (v - p.min) / (p.max - p.min));
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            float len = R * (major ? 0.105f : 0.053f);
            pt = paint();
            pt.setStyle(Paint.Style.STROKE);
            pt.setColor(st.tick);
            if (!major) pt.setAlpha((int) (255 * st.minorAlpha));
            pt.setStrokeWidth(Math.max(1, R * (major ? 0.022f : 0.011f)));
            c.drawLine(cx + r * cos, cy + r * sin, cx + (r - len) * cos, cy + (r - len) * sin, pt);
            if (whole(v - p.min, sc.labelEvery)) {
                double n = v / sc.labelDiv;
                String t = whole(n, 1) ? String.valueOf(Math.round(n)) : String.format(Locale.US, "%.1f", n);
                float size = Math.max(8, R * st.labelSize);
                float lr = r - R * 0.25f;
                text(c, t, cx + lr * cos, centre(cy + lr * sin, size, st.font), size, st.label, st.font, Paint.Align.CENTER);
            }
        }

        if (shown != null) {
            double a = Math.toRadians(135 + 270.0 * frac(p, shown));
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            float tip = r - R * 0.1f, tail = R * 0.13f;
            pt = paint();
            pt.setStyle(Paint.Style.STROKE);
            pt.setStrokeCap(Paint.Cap.ROUND);
            pt.setStrokeWidth(Math.max(2, R * 0.035f));
            pt.setColor(st.needle);
            c.drawLine(cx - tail * cos, cy - tail * sin, cx + tip * cos, cy + tip * sin, pt);
        }
        pt = paint();
        pt.setColor(st.hub);
        c.drawCircle(cx, cy, R * 0.085f, pt);
        if (st.hubRing != 0) {
            pt.setStyle(Paint.Style.STROKE);
            pt.setStrokeWidth(Math.max(1, R * 0.016f));
            pt.setColor(st.hubRing);
            c.drawCircle(cx, cy, R * 0.085f, pt);
        }
    }

    /** Blocks filled left to right, the XP-era progress bar. */
    static void segments(Canvas c, float l, float t, float r, float b, float frac, float seg, float gap, int color) {
        Paint pt = paint();
        pt.setColor(color);
        float end = l + (r - l) * frac;
        for (float x = l; x + 1 < end; x += seg + gap) c.drawRect(x, t, Math.min(x + seg, end), b, pt);
    }

    /** Two opposed arrows: the "swap look" icon. */
    static void swapIcon(Canvas c, float x, float y, float s, int color) {
        Paint pt = paint();
        pt.setStyle(Paint.Style.STROKE);
        pt.setStrokeWidth(Math.max(1.5f, s * 0.11f));
        pt.setStrokeCap(Paint.Cap.ROUND);
        pt.setColor(color);
        float y1 = y + s * 0.3f, y2 = y + s * 0.7f, a = s * 0.19f;
        c.drawLine(x + s * 0.1f, y1, x + s * 0.78f, y1, pt);
        c.drawLine(x + s * 0.78f, y1, x + s * 0.78f - a, y1 - a, pt);
        c.drawLine(x + s * 0.78f, y1, x + s * 0.78f - a, y1 + a, pt);
        c.drawLine(x + s * 0.9f, y2, x + s * 0.22f, y2, pt);
        c.drawLine(x + s * 0.22f, y2, x + s * 0.22f + a, y2 - a, pt);
        c.drawLine(x + s * 0.22f, y2, x + s * 0.22f + a, y2 + a, pt);
    }
}
