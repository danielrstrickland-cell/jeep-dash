package com.strickland.jeepdash.theme;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import com.strickland.jeepdash.LinkService;
import com.strickland.jeepdash.core.GaugeSpec;
import com.strickland.jeepdash.core.GaugeType;
import com.strickland.jeepdash.core.Param;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * An 1980s digital dash: glowing segment numerals and bar graphs on black glass, the tachometer a band of
 * bars that climbs across the top. Original artwork in the style of the era's fluorescent displays (no
 * maker's design is copied). Everything is drawn from lines, so it stays sharp and cheap on a slow screen.
 */
public final class RetroTheme implements Theme {
    static final int MAIN = 0xff3df2d0, AMBER = 0xffffb13b, RED = 0xffff4336, FRAME = 0xff12332d;
    /** Which segments each digit lights: bit 0 = top, then clockwise, bit 6 = middle. */
    private static final int[] DIGIT = {0x3f, 0x06, 0x5b, 0x4f, 0x66, 0x6d, 0x7d, 0x07, 0x7f, 0x6f};
    private static final float SLANT = 0.12f;

    private final String id;
    private final Map<String, RectF> buttons = new LinkedHashMap<>();

    /** @param id the key this look's layout is saved under (the Jeep and the Porsche each have their own) */
    public RetroTheme(String id) { this.id = id; }

    @Override
    public String id() { return id; }

    @Override
    public void drawBackground(Canvas c, float w, float h) {
        Paint p = Art.paint();
        p.setColor(0xff000000);
        c.drawRect(0, 0, w, h, p);
        p.setShader(Art.radial(w / 2, h * 0.45f, Math.max(w, h) * 0.6f, 0xff06211c, 0xff000000));
        c.drawRect(0, 0, w, h, p);
    }

    // ---------------- glowing strokes and segment numerals ----------------

    static int dim(int color, int alpha) { return (color & 0x00ffffff) | (alpha << 24); }

    /** One lit stroke: a wide soft glow under a sharp core. */
    static void lit(Canvas c, float x0, float y0, float x1, float y1, float thick, int color) {
        Paint p = Art.paint();
        p.setStyle(Paint.Style.STROKE);
        if (thick >= 3) {                                // a halo on anything big enough to carry one
            p.setStrokeWidth(thick * 1.7f);
            p.setColor(dim(color, 0x26));
            c.drawLine(x0, y0, x1, y1, p);
        }
        p.setStrokeWidth(thick);
        p.setColor(color);
        c.drawLine(x0, y0, x1, y1, p);
    }

    static void unlit(Canvas c, float x0, float y0, float x1, float y1, float thick, int color) {
        Paint p = Art.paint();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(thick);
        p.setColor(dim(color, 0x1c));
        c.drawLine(x0, y0, x1, y1, p);
    }

    private static float digitW(float h) { return h * 0.5f; }

    private static float step(float h) { return h * 0.68f; }

    /** How wide a string of segment characters is at height h. */
    private static float widthOf(String s, float h) {
        float w = 0;
        for (int i = 0; i < s.length(); i++) w += s.charAt(i) == '.' ? h * 0.22f : step(h);
        return w - (step(h) - digitW(h));
    }

    private static void segment(Canvas c, int which, float x, float y, float w, float h, float t, int color, boolean on) {
        float g = t * 0.85f, half = h / 2;
        float x0, y0, x1, y1;
        switch (which) {
            case 0: x0 = g; y0 = 0; x1 = w - g; y1 = 0; break;
            case 1: x0 = w; y0 = g; x1 = w; y1 = half - g; break;
            case 2: x0 = w; y0 = half + g; x1 = w; y1 = h - g; break;
            case 3: x0 = g; y0 = h; x1 = w - g; y1 = h; break;
            case 4: x0 = 0; y0 = half + g; x1 = 0; y1 = h - g; break;
            case 5: x0 = 0; y0 = g; x1 = 0; y1 = half - g; break;
            default: x0 = g; y0 = half; x1 = w - g; y1 = half; break;
        }
        // lean the digit: the top sits further right than the bottom
        float sx0 = x + x0 + SLANT * (h - y0), sx1 = x + x1 + SLANT * (h - y1);
        if (on) lit(c, sx0, y + y0, sx1, y + y1, t, color);
        else unlit(c, sx0, y + y0, sx1, y + y1, t, color);
    }

    /** Segment numerals with their right edge at {@code right}. Understands digits, '-', '.' and blank. */
    static void numerals(Canvas c, String s, float right, float top, float h, int color) {
        float t = Math.max(1.5f, h * 0.085f), w = digitW(h), x = right - SLANT * h;
        for (int i = s.length() - 1; i >= 0; i--) {
            char ch = s.charAt(i);
            if (ch == '.') {
                x -= h * 0.22f;
                lit(c, x + h * 0.03f, top + h, x + h * 0.03f + t * 1.3f, top + h, t * 1.3f, color);
                continue;
            }
            x -= w;
            int mask = ch >= '0' && ch <= '9' ? DIGIT[ch - '0'] : ch == '-' ? 0x40 : 0;
            for (int k = 0; k < 7; k++) segment(c, k, x, top, w, h, t, color, (mask & (1 << k)) != 0);
            x -= step(h) - w;
        }
    }

    /** The largest numeral height up to {@code want} at which s fits in maxW. */
    private static float fitH(String s, float want, float maxW) {
        float w = widthOf(s, want) + SLANT * want;
        return w > maxW ? want * maxW / w : want;
    }

    private static String reading(Param p, Double v) {
        if (v == null) return "--";
        if (p.unit.startsWith("°") || p.unit.equals("rpm") || p.unit.equals("mph") || p.unit.equals("km/h") || p.max - p.min > 250 || Art.whole(p))
            return String.valueOf(Math.round(v));
        return String.format(Locale.US, "%.1f", v);
    }

    static void label(Canvas c, String s, float x, float base, float size, int color, float maxW, Paint.Align align) {
        Paint p = Art.paint();
        p.setTypeface(Art.ITALIC_BOLD);
        p.setLetterSpacing(0.08f);
        p.setTextSize(size);
        float w = p.measureText(s);
        if (w > maxW && w > 0) p.setTextSize(Math.max(6, size * maxW / w));
        p.setColor(color);
        p.setTextAlign(align);
        c.drawText(s, x, base, p);
    }

    private static String name(Param p) {
        if (p.id.equals("rpm")) return "TACH";
        return p.name.toUpperCase(Locale.US);
    }

    private static String unit(Param p) {
        if (p.id.equals("rpm")) return "r/min";
        return p.unit.startsWith("°") ? p.unit : p.unit.toUpperCase(Locale.US).replace("MPH", "mph").replace("PSI", "psi")
                .replace("KM/H", "km/h");
    }

    private static float redFrom(Param p) {
        if (p.redLine != null) return Art.frac(p, p.redLine);   // the gauge's own red zone
        if (p.id.equals("rpm")) return Art.frac(p, p.max > 6500 ? 6500 : 5000);
        if (p.id.equals("coolant")) return Art.frac(p, Art.fromBase(p, 230));
        if (p.id.equals("oil_temp")) return Art.frac(p, Art.fromBase(p, 270));
        return 2;
    }

    private static void frame(Canvas c, GaugeSpec g, boolean flash) {
        float round = Math.min(12, Math.min(g.w, g.h) * 0.1f);
        Paint p = Art.paint();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f);
        p.setColor(flash ? RED : FRAME);
        c.drawRoundRect(Art.rect(g.x, g.y, g.x + g.w, g.y + g.h), round, round, p);
    }

    // ---------------- gauges ----------------

    @Override
    public void drawGauge(Canvas c, GaugeSpec g, Param p, Double value, Double shown, boolean flashOn, boolean editing) {
        if (g.type == GaugeType.DIAL) {
            if (g.w > g.h * 1.8f) sweep(c, g, p, value, shown, flashOn);
            else ring(c, g, p, value, shown, flashOn);
        } else if (g.type == GaugeType.DIGITAL) {
            readout(c, g, p, value, flashOn);
        } else {
            bar(c, g, p, value, shown, flashOn);
        }
    }

    /** A wide gauge: a band of bars that climbs and grows from left to right, numbered underneath. */
    private void sweep(Canvas c, GaugeSpec g, Param p, Double value, Double shown, boolean flash) {
        float l = g.x + g.w * 0.03f, r = g.x + g.w * 0.97f, bottom = g.y + g.h * 0.80f, H = g.h * 0.74f;
        int n = Math.max(24, Math.min(64, (int) (g.w / 16)));
        float slot = (r - l) / n, thick = slot * 0.52f, red = redFrom(p);
        float f = shown == null ? -1 : Art.frac(p, shown);
        for (int i = 0; i < n; i++) {
            float t = (i + 0.5f) / n;
            float base = bottom - H * 0.42f * (float) Math.pow(t, 1.7), tall = H * (0.16f + 0.40f * t);
            float x = l + (i + 0.5f) * slot;
            int color = flash || t >= red ? RED : MAIN;
            if (f >= 0 && (i + 1f) / n <= f + 0.5f / n) lit(c, x, base, x, base - tall, thick, color);
            else unlit(c, x, base, x, base - tall, thick, color);
        }
        // numbers along the foot of the band
        Art.Scale sc = Art.scale(p);
        double every = p.id.equals("rpm") ? 1000 : sc.labelEvery, div = p.id.equals("rpm") ? 1000 : 1;
        float size = Math.max(8, g.h * 0.085f);
        for (double v = p.min; v <= p.max + 0.001; v += every) {
            float t = Art.frac(p, v), x = l + (r - l) * t;
            float base = bottom - H * 0.42f * (float) Math.pow(t, 1.7);
            label(c, Art.compact(v / div), x, base + size * 1.25f, size, t >= red ? RED : MAIN, slot * 4, Paint.Align.CENTER);
        }
        label(c, name(p), l, g.y + size * 1.3f, size * 1.1f, AMBER, g.w * 0.3f, Paint.Align.LEFT);
        label(c, p.id.equals("rpm") ? "x1000 r/min" : unit(p), l, g.y + size * 2.6f, size * 0.85f, dim(AMBER, 0xb0), g.w * 0.3f, Paint.Align.LEFT);
        // the exact figure, small, tucked under the low end of the band
        String v = reading(p, value);
        float dh = fitH(v, g.h * 0.20f, g.w * 0.20f);
        numerals(c, v, l + g.w * 0.42f, g.y + g.h * 0.07f, dh, flash ? RED : MAIN);
    }

    /** A squarer gauge: bars round three quarters of a circle with the figure in the middle. */
    private void ring(Canvas c, GaugeSpec g, Param p, Double value, Double shown, boolean flash) {
        float R = Math.min(g.w, g.h) / 2, cx = g.x + g.w / 2, cy = g.y + g.h / 2;
        int n = 40;
        float red = redFrom(p), f = shown == null ? -1 : Art.frac(p, shown);
        float thick = (float) (2 * Math.PI * R * 0.84f * 0.75f / n * 0.6f);
        for (int i = 0; i < n; i++) {
            float t = (i + 0.5f) / n;
            double a = Math.toRadians(135 + 270.0 * t);
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a), r0 = R * 0.72f, r1 = R * (0.84f + 0.12f * t);
            int color = flash || t >= red ? RED : MAIN;
            if (f >= 0 && (i + 1f) / n <= f + 0.5f / n) lit(c, cx + r0 * cos, cy + r0 * sin, cx + r1 * cos, cy + r1 * sin, thick, color);
            else unlit(c, cx + r0 * cos, cy + r0 * sin, cx + r1 * cos, cy + r1 * sin, thick, color);
        }
        String v = reading(p, value);
        float dh = fitH(v, R * 0.42f, R * 1.05f);
        numerals(c, v, cx + (widthOf(v, dh) + SLANT * dh) / 2, cy - dh * 0.62f, dh, flash ? RED : MAIN);
        label(c, name(p), cx, cy + R * 0.50f, Math.max(8, R * 0.12f), AMBER, R * 1.1f, Paint.Align.CENTER);
        label(c, unit(p), cx, cy + R * 0.70f, Math.max(7, R * 0.10f), dim(AMBER, 0xb0), R * 0.8f, Paint.Align.CENTER);
        label(c, Art.compact(p.min), cx - R * 0.56f, cy + R * 0.92f, Math.max(7, R * 0.09f), MAIN, R * 0.4f, Paint.Align.CENTER);
        label(c, Art.compact(p.max), cx + R * 0.56f, cy + R * 0.92f, Math.max(7, R * 0.09f), MAIN, R * 0.4f, Paint.Align.CENTER);
    }

    private void readout(Canvas c, GaugeSpec g, Param p, Double value, boolean flash) {
        frame(c, g, flash);
        if (g.h < 150 && g.w > g.h * 2) {
            // a low, wide box: name on the left, figure and unit on the right, all on one line
            float pad = Math.min(18, g.w * 0.05f), size = Math.max(8, Math.min(20, g.h * 0.2f)), cy = g.y + g.h / 2;
            label(c, name(p), g.x + pad, cy + size * 0.36f, size, AMBER, g.w * 0.4f, Paint.Align.LEFT);
            float unitW = Art.width(unit(p), size, Art.ITALIC_BOLD) + 10;
            label(c, unit(p), g.x + g.w - pad, cy + size * 0.36f, size, dim(AMBER, 0xc0), g.w * 0.2f, Paint.Align.RIGHT);
            String v = reading(p, value);
            float dh = fitH(v, g.h * 0.56f, g.w * 0.36f);
            numerals(c, v, g.x + g.w - pad - unitW, cy - dh / 2, dh, flash ? RED : MAIN);
            return;
        }
        float pad = Math.min(18, g.w * 0.06f), size = Math.max(8, Math.min(20, g.h * 0.12f));
        label(c, name(p), g.x + pad, g.y + pad + size, size, AMBER, g.w * 0.6f, Paint.Align.LEFT);
        label(c, unit(p), g.x + g.w - pad, g.y + g.h - pad, size, dim(AMBER, 0xc0), g.w * 0.4f, Paint.Align.RIGHT);
        String v = reading(p, value);
        float top = g.y + pad + size * 1.5f, room = g.h - pad * 2 - size * 2.9f;
        float dh = fitH(v, Math.max(12, room * 0.92f), g.w * 0.62f);
        numerals(c, v, g.x + g.w / 2 + (widthOf(v, dh) + SLANT * dh) / 2, top + (room - dh) / 2, dh, flash ? RED : MAIN);
    }

    private void bar(Canvas c, GaugeSpec g, Param p, Double value, Double shown, boolean flash) {
        frame(c, g, flash);
        float pad = Math.min(16, g.w * 0.05f), l = g.x + pad, r = g.x + g.w - pad;
        float size = Math.max(8, Math.min(18, g.h * 0.17f));
        label(c, name(p), l, g.y + pad + size, size, AMBER, (r - l) * 0.55f, Paint.Align.LEFT);
        String v = reading(p, value);
        float dh = fitH(v, Math.min(g.h * 0.30f, 40), (r - l) * 0.3f);
        float unitW = Art.width(unit(p), size * 0.8f, Art.ITALIC_BOLD) + 8;
        label(c, unit(p), r, g.y + pad + dh, size * 0.8f, dim(AMBER, 0xc0), (r - l) * 0.2f, Paint.Align.RIGHT);
        numerals(c, v, r - unitW, g.y + pad, dh, flash ? RED : MAIN);

        int n = Math.max(12, Math.min(32, (int) ((r - l) / 11)));
        float slot = (r - l) / n, thick = slot * 0.52f, red = redFrom(p);
        float top = g.y + g.h * 0.52f, bottom = g.y + g.h - pad - size * 0.9f;
        if (bottom - top < 6) return;
        float f = shown == null ? -1 : Art.frac(p, shown);
        float zero = p.min < 0 && p.max > 0 ? Art.frac(p, 0) : 0;
        for (int i = 0; i < n; i++) {
            float t = (i + 0.5f) / n, x = l + (i + 0.5f) * slot;
            int color = flash || t >= red ? RED : MAIN;
            boolean on = f >= 0 && (zero > 0 ? (t >= Math.min(zero, f) && t <= Math.max(zero, f)) : (i + 1f) / n <= f + 0.5f / n);
            if (on) lit(c, x, bottom, x, top, thick, color);
            else unlit(c, x, bottom, x, top, thick, color);
        }
        float small = size * 0.7f;
        label(c, Art.compact(p.min), l, g.y + g.h - pad * 0.45f, small, MAIN, 60, Paint.Align.LEFT);
        label(c, Art.compact(p.max), r, g.y + g.h - pad * 0.45f, small, MAIN, 60, Paint.Align.RIGHT);
        if (zero > 0) label(c, "0", l + (r - l) * zero, g.y + g.h - pad * 0.45f, small, MAIN, 30, Paint.Align.CENTER);
    }

    @Override
    public void drawPanel(Canvas c, float w, float h, String title, String message) {
        label(c, title.toUpperCase(Locale.US), w / 2, h / 2 - 12, 26, AMBER, w - 60, Paint.Align.CENTER);
        label(c, message, w / 2, h / 2 + 24, 18, MAIN, w - 60, Paint.Align.CENTER);
    }

    // ---------------- the strip along the bottom ----------------

    private float bh;

    private float button(Canvas c, String action, float right, float top, String text, boolean strong) {
        float size = bh * 0.40f, w = Art.width(text, size, Art.ITALIC_BOLD) * 1.1f + bh * 0.8f, l = right - w;
        Paint p = Art.paint();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f);
        p.setColor(strong ? AMBER : dim(MAIN, 0x90));
        c.drawRoundRect(Art.rect(l, top, right, top + bh), 6, 6, p);
        label(c, text, l + w / 2, Art.centre(top + bh / 2, size, Art.ITALIC_BOLD), size, strong ? AMBER : MAIN, w, Paint.Align.CENTER);
        buttons.put(action, new RectF(l - 4, top - 8, right + 4, top + bh + 8));
        return l;
    }

    @Override
    public void drawChrome(Canvas c, float w, float h, Chrome s) {
        buttons.clear();
        bh = Math.max(28, Math.min(44, h * 0.066f));
        float top = h - bh - Math.max(8, h * 0.02f), x = w - 20;
        if (s.editing) {
            x = button(c, "done", x, top, "DONE", true) - 8;
            x = button(c, "snap", x, top, s.snap ? "SNAP ON" : "SNAP OFF", false) - 8;
            button(c, "add", x, top, "+ GAUGE", false);
        } else {
            if (s.nextLook != null) x = button(c, "switch", x, top, s.nextLook, false) - 8;
            x = button(c, "menu", x, top, "SETUP", false) - 8;
            x = button(c, "logs", x, top, "LOGS", false) - 8;
            if (s.canEdit) x = button(c, "edit", x, top, "EDIT", false) - 8;
            if (s.music) button(c, "music", x, top, "\u266A MUSIC", s.musicOpen);
        }
        boolean good = s.status == LinkService.Status.LIVE && s.detail.isEmpty();
        boolean bad = s.status == LinkService.Status.BUSY || s.status == LinkService.Status.NO_PERMISSION;
        int ink = good ? MAIN : bad ? RED : AMBER;
        String lt = JeepTheme.linkText(s).replace(" ● ", "  ");
        float size = bh * 0.44f;
        label(c, lt, 24, Art.centre(top + bh / 2, size, Art.ITALIC_BOLD), size, ink, w * 0.4f, Paint.Align.LEFT);
        buttons.put("status", new RectF(8, top - 8, w * 0.4f, top + bh + 8));
        if (!s.detail.isEmpty()) {
            label(c, s.detail, w / 2, Math.max(18, h * 0.035f), 15, ink, w - 60, Paint.Align.CENTER);
            buttons.put("status2", new RectF(30, 0, w - 30, Math.max(30, h * 0.05f)));
        } else if (!s.notice.isEmpty()) {
            label(c, s.notice, w / 2, Math.max(18, h * 0.035f), 15, AMBER, w - 60, Paint.Align.CENTER);
            buttons.put("notice", new RectF(30, 0, w - 30, Math.max(30, h * 0.05f)));
        }
    }

    @Override
    public RectF buttonRect(String action) { return buttons.get(action); }

    /** The window on the look's dark panel in its glow colour and lettering. */
    @Override
    public MusicPainter.Dress musicDress() {
        MusicPainter.Dress d = new MusicPainter.Dress();
        d.frame = 0xff000000;
        d.edge = dim(MAIN, 0x90);
        d.title = new int[]{FRAME};
        d.titleInk = MAIN;
        d.close = new int[]{0xff000000};
        d.closeInk = AMBER;
        d.body = 0xff06211c;
        d.ink = MAIN;
        d.soft = AMBER;
        d.button = 0xff000000;
        d.buttonEdge = dim(MAIN, 0x90);
        d.buttonInk = MAIN;
        d.glow = MAIN;
        d.spacing = 0.08f;
        d.corner = 6;
        d.face = Art.ITALIC_BOLD;
        d.titleFace = Art.ITALIC_BOLD;
        return d;
    }

    @Override
    public String hit(float x, float y) {
        for (Map.Entry<String, RectF> e : buttons.entrySet())
            if (e.getValue().contains(x, y)) return e.getKey().equals("status2") ? "status" : e.getKey();
        return null;
    }
}
