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
 * A modern Italian grand-tourer cluster: black glass with thin white markings and a red needle, and the
 * tachometer on a bright yellow face. Original artwork (no maker's badge or lettering). Everything is sized
 * from the gauge's own box so it holds up on a small head-unit screen.
 */
public final class GtTheme implements Theme {
    private static final int WHITE = 0xfff4f5f7, GREY = 0xff8b9099, DIM = 0xff2a2d33, RED = 0xffe0202b,
            GOOD = 0xff62d58a, WAIT = 0xffe8b24a, GLASS_TOP = 0xff15171b, GLASS_BOTTOM = 0xff08090b,
            YELLOW = 0xffffd21e, YELLOW_EDGE = 0xffe2a900, INK = 0xff111214;

    private final Map<String, RectF> buttons = new LinkedHashMap<>();
    private float heat;                 // how hot the little engine's headers are, 0..1
    private long lastNs;

    @Override
    public String id() { return "p_gt"; }

    @Override
    public void drawBackground(Canvas c, float w, float h) {
        Paint p = Art.paint();
        p.setColor(0xff000000);
        c.drawRect(0, 0, w, h, p);
        p.setShader(Art.radial(w / 2, h * 0.42f, Math.max(w, h) * 0.62f, 0xff15171c, 0xff000000));
        c.drawRect(0, 0, w, h, p);
    }

    // ---------------- lettering ----------------

    private static void spaced(Canvas c, String s, float x, float base, float size, int color, float maxW, Paint.Align align) {
        Paint p = Art.paint();
        p.setTypeface(Art.BOLD);
        p.setLetterSpacing(0.14f);
        p.setTextSize(size);
        float w = p.measureText(s);
        if (w > maxW && w > 0) p.setTextSize(Math.max(6, size * maxW / w));
        p.setColor(color);
        p.setTextAlign(align);
        c.drawText(s, x, base, p);
    }

    private static String caption(Param p) {
        if (p.id.equals("rpm")) return "RPM × 1000";
        return p.name.toUpperCase(Locale.US);
    }

    /** Whole numbers where a decimal would only flicker (temperatures, engine speed, road speed). */
    private static String reading(Param p, Double v) {
        if (v == null) return Art.DASH;
        if (p.unit.startsWith("°F") || p.unit.equals("°C") || p.unit.equals("rpm") || p.unit.equals("mph")
                || p.unit.equals("km/h") || Art.whole(p)) return String.valueOf(Math.round(v));
        return String.format(Locale.US, "%.1f", v);
    }

    private static Art.Scale scale(Param p) {
        Art.Scale s = new Art.Scale();
        double range = p.max - p.min;
        if (Art.converted(p)) return Art.scale(p);          // shown in another unit: plain spacings
        if (p.id.equals("rpm")) {
            s.major = 1000; s.minor = 500; s.labelEvery = 1000; s.labelDiv = 1000; s.redFrom = Math.min(6500.0, p.max * 0.93);
        } else if (p.id.equals("speed")) {
            s.major = 20; s.minor = 10; s.labelEvery = range > 150 ? 40 : 20;
        } else if (p.id.equals("boost")) {
            s.major = 5; s.minor = 2.5; s.labelEvery = 5;
        } else {
            return Art.scale(p);
        }
        return s;
    }

    private static boolean whole(double a, double b) { return Math.abs(a / b - Math.round(a / b)) < 0.001; }

    // ---------------- gauges ----------------

    @Override
    public void drawGauge(Canvas c, GaugeSpec g, Param p, Double value, Double shown, boolean flashOn, boolean editing) {
        if (g.type == GaugeType.DIAL) dial(c, g, p, value, shown, flashOn);
        else if (g.type == GaugeType.DIGITAL) tile(c, g, p, value, shown, flashOn);
        else bar(c, g, p, value, shown, flashOn);
    }

    private void dial(Canvas c, GaugeSpec g, Param p, Double value, Double shown, boolean flash) {
        float R = Math.min(g.w, g.h) / 2, cx = g.x + g.w / 2, cy = g.y + g.h / 2;
        Art.Scale sc = scale(p);
        boolean yellow = p.id.equals("rpm");            // the tachometer wears the yellow face
        int mark = yellow ? INK : WHITE, minor = yellow ? 0xff5a4a10 : GREY, track = yellow ? 0x33000000 : DIM;
        Paint pt = Art.paint();
        if (yellow) pt.setShader(Art.radial(cx, cy - R * 0.2f, R * 1.15f, YELLOW, YELLOW_EDGE));
        else pt.setShader(Art.radial(cx, cy - R * 0.25f, R * 1.25f, GLASS_TOP, 0xff020203));
        c.drawCircle(cx, cy, R * 0.97f, pt);
        pt = Art.paint();
        pt.setStyle(Paint.Style.STROKE);
        pt.setStrokeWidth(Math.max(1.5f, R * 0.012f));
        pt.setColor(flash ? RED : 0xff3a3e46);
        c.drawCircle(cx, cy, R * 0.97f, pt);

        // the sweep: a dim track all the way round, a red end, lit up to the needle
        float r = R * 0.88f, band = Math.max(2, R * 0.035f);
        RectF arc = Art.rect(cx - r, cy - r, cx + r, cy + r);
        pt.setStrokeWidth(band);
        pt.setColor(track);
        c.drawArc(arc, 135, 270, false, pt);
        float redAt = sc.redFrom == null ? 1 : Art.frac(p, sc.redFrom);
        if (redAt < 1) {
            pt.setColor(yellow ? 0xffb3261c : 0xff6e1418);
            c.drawArc(arc, 135 + 270 * redAt, 270 * (1 - redAt), false, pt);
        }
        if (shown != null) {
            float f = Art.frac(p, shown), zero = p.min < 0 && p.max > 0 ? Art.frac(p, 0) : 0;
            pt.setColor(mark);
            float from = Math.min(zero, f), to = Math.min(Math.max(zero, f), redAt);
            if (to > from) c.drawArc(arc, 135 + 270 * from, 270 * (to - from), false, pt);
            if (f > redAt) {
                pt.setColor(RED);
                c.drawArc(arc, 135 + 270 * redAt, 270 * (f - redAt), false, pt);
            }
        }

        // needle first, so the numbers stay readable where it passes over them
        if (shown != null) {
            double a = Math.toRadians(135 + 270.0 * Art.frac(p, shown));
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            float tip = r - band, from = R * (yellow ? 0.59f : 0.38f);      // the needle floats outside the centre
            pt = Art.paint();
            pt.setStyle(Paint.Style.STROKE);
            pt.setStrokeCap(Paint.Cap.ROUND);
            pt.setStrokeWidth(Math.max(4, R * 0.06f));
            pt.setColor(0x55e0202b);
            c.drawLine(cx + from * cos, cy + from * sin, cx + tip * cos, cy + tip * sin, pt);
            pt.setStrokeWidth(Math.max(2, R * 0.022f));
            pt.setColor(RED);
            c.drawLine(cx + from * cos, cy + from * sin, cx + tip * cos, cy + tip * sin, pt);
        }

        int steps = (int) Math.floor((p.max - p.min) / sc.minor + 0.001);
        float labelSize = Math.max(8, R * 0.12f);
        for (int i = 0; i <= steps; i++) {
            double v = p.min + i * sc.minor;
            boolean major = whole(v - p.min, sc.major);
            double a = Math.toRadians(135 + 270.0 * (v - p.min) / (p.max - p.min));
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            float r0 = r - band * 1.4f, len = R * (major ? 0.075f : 0.04f);
            boolean red = sc.redFrom != null && v >= sc.redFrom;
            pt = Art.paint();
            pt.setStyle(Paint.Style.STROKE);
            pt.setColor(red ? RED : major ? mark : minor);
            pt.setStrokeWidth(Math.max(1, R * (major ? 0.014f : 0.008f)));
            c.drawLine(cx + r0 * cos, cy + r0 * sin, cx + (r0 - len) * cos, cy + (r0 - len) * sin, pt);
            if (whole(v - p.min, sc.labelEvery)) {
                double n = v / sc.labelDiv;
                String t = whole(n, 1) ? String.valueOf(Math.round(n)) : String.format(Locale.US, "%.1f", n);
                float lr = r0 - len - labelSize * 0.85f;
                Art.text(c, t, cx + lr * cos, Art.centre(cy + lr * sin, labelSize, Art.BOLD), labelSize,
                        red ? RED : mark, Art.BOLD, Paint.Align.CENTER);
            }
        }

        // the reading sits in the middle (on a black disc when the face is yellow), name and unit around it
        int ink = flash ? RED : WHITE;
        String v = reading(p, value);
        if (yellow) {
            // the tachometer carries a little engine that lives off the revs, with the figure under it
            Paint disc = Art.paint();
            disc.setColor(0xff0b0c0e);
            c.drawCircle(cx, cy, R * 0.55f, disc);
            long now = System.nanoTime();
            float dt = lastNs == 0 ? 0 : Math.min(0.5f, (now - lastNs) / 1e9f), revs = shown == null ? 0 : Art.frac(p, shown);
            lastNs = now;
            // headers heat up over a few seconds of hard running and cool more slowly
            float want = (float) Math.pow(revs, 1.6);
            heat += (want - heat) * Math.min(1, dt / (want > heat ? 4f : 9f));
            EngineArt.draw(c, cx, cy - R * 0.12f, R * 0.48f, revs, heat, now / 1e9f);
            float size = Art.fit(v, R * 0.17f, R * 0.5f, Art.BOLD);
            Art.text(c, v, cx, Art.centre(cy + R * 0.39f, size, Art.BOLD), size, ink, Art.BOLD, Paint.Align.CENTER);
            spaced(c, caption(p), cx, cy + R * 0.66f, Math.max(7, R * 0.07f), INK, R * 0.9f, Paint.Align.CENTER);
            return;
        }
        float size = Art.fit(v, R * 0.34f, R * 0.52f, Art.BOLD);
        Art.text(c, v, cx, Art.centre(cy, size, Art.BOLD), size, ink, Art.BOLD, Paint.Align.CENTER);
        spaced(c, caption(p), cx, cy + R * 0.62f, Math.max(7, R * 0.075f), yellow ? INK : GREY, R * 0.95f, Paint.Align.CENTER);
        if (!p.unit.equals("rpm"))
            spaced(c, p.unit.toUpperCase(Locale.US), cx, cy + R * 0.27f, Math.max(7, R * 0.07f), GREY, R * 0.5f, Paint.Align.CENTER);
    }

    private static void glass(Canvas c, GaugeSpec g, boolean flash) {
        float round = Math.min(14, Math.min(g.w, g.h) * 0.12f);
        Paint p = Art.paint();
        p.setShader(Art.vertical(g.y, g.y + g.h, new int[]{GLASS_TOP, GLASS_BOTTOM}, null));
        c.drawRoundRect(Art.rect(g.x, g.y, g.x + g.w, g.y + g.h), round, round, p);
        p = Art.paint();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f);
        p.setColor(flash ? RED : DIM);
        c.drawRoundRect(Art.rect(g.x, g.y, g.x + g.w, g.y + g.h), round, round, p);
    }

    /** A thin line along the bottom of a box showing where the reading sits on its scale. */
    private static void track(Canvas c, float l, float r, float y, float thick, Param p, Double shown, boolean flash) {
        Paint pt = Art.paint();
        pt.setStyle(Paint.Style.STROKE);
        pt.setStrokeCap(Paint.Cap.ROUND);
        pt.setStrokeWidth(thick);
        pt.setColor(DIM);
        c.drawLine(l, y, r, y, pt);
        if (shown == null) return;
        float zero = p.min < 0 && p.max > 0 ? Art.frac(p, 0) : 0, f = Art.frac(p, shown);
        pt.setColor(flash ? RED : WHITE);
        c.drawLine(l + (r - l) * Math.min(zero, f), y, l + (r - l) * Math.max(zero, f), y, pt);
    }

    private void tile(Canvas c, GaugeSpec g, Param p, Double value, Double shown, boolean flash) {
        glass(c, g, flash);
        float pad = Math.min(16, g.w * 0.09f), cx = g.x + g.w / 2;
        float nameSize = Math.max(7, Math.min(15, g.h * 0.13f));
        spaced(c, caption(p), cx, g.y + pad + nameSize, nameSize, GREY, g.w - 2 * pad, Paint.Align.CENTER);
        String v = reading(p, value);
        float size = Art.fit(v, g.h * 0.40f, g.w - 2 * pad, Art.BOLD);
        Art.text(c, v, cx, Art.centre(g.y + g.h * 0.54f, size, Art.BOLD), size, flash ? RED : WHITE, Art.BOLD, Paint.Align.CENTER);
        float unitSize = Math.max(7, Math.min(13, g.h * 0.11f));
        spaced(c, p.unit.toUpperCase(Locale.US), cx, g.y + g.h - pad - unitSize * 0.9f, unitSize, GREY, g.w - 2 * pad, Paint.Align.CENTER);
        track(c, g.x + pad, g.x + g.w - pad, g.y + g.h - pad * 0.55f, Math.max(2, g.h * 0.022f), p, shown, flash);
    }

    private void bar(Canvas c, GaugeSpec g, Param p, Double value, Double shown, boolean flash) {
        glass(c, g, flash);
        float pad = Math.min(16, g.w * 0.07f), l = g.x + pad, r = g.x + g.w - pad;
        float nameSize = Math.max(7, Math.min(15, g.h * 0.16f));
        spaced(c, caption(p), l, g.y + pad + nameSize, nameSize, GREY, (r - l) * 0.6f, Paint.Align.LEFT);
        String v = reading(p, value) + " " + p.unit;
        float size = Art.fit(v, Math.min(34, g.h * 0.3f), (r - l) * 0.5f, Art.BOLD);
        Art.text(c, v, r, g.y + pad + size * 0.85f, size, flash ? RED : WHITE, Art.BOLD, Paint.Align.RIGHT);
        float y = g.y + g.h * 0.68f;
        track(c, l, r, y, Math.max(4, g.h * 0.07f), p, shown, flash);
        float small = Math.max(7, Math.min(12, g.h * 0.11f));
        Art.text(c, Art.compact(p.min), l, g.y + g.h - pad * 0.5f, small, GREY, Art.BOLD, Paint.Align.LEFT);
        Art.text(c, Art.compact(p.max), r, g.y + g.h - pad * 0.5f, small, GREY, Art.BOLD, Paint.Align.RIGHT);
    }

    @Override
    public void drawPanel(Canvas c, float w, float h, String title, String message) {
        spaced(c, title.toUpperCase(Locale.US), w / 2, h / 2 - 12, 24, WHITE, w - 60, Paint.Align.CENTER);
        float size = Art.fit(message, 18, w - 60, Art.SANS);
        Art.text(c, message, w / 2, h / 2 + 24, size, GREY, Art.SANS, Paint.Align.CENTER);
    }

    // ---------------- the strip along the bottom ----------------

    private float bh;

    /** An outlined button whose right edge is at {@code right}; returns its left edge. */
    private float button(Canvas c, String action, float right, float top, String label, boolean strong) {
        float size = bh * 0.38f;
        Paint m = Art.paint();
        m.setTypeface(Art.BOLD);
        m.setLetterSpacing(0.14f);
        m.setTextSize(size);
        float w = m.measureText(label) + bh * 0.9f, l = right - w;
        Paint p = Art.paint();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f);
        p.setColor(strong ? WHITE : 0xff4a4f58);
        c.drawRoundRect(Art.rect(l, top, right, top + bh), bh / 2, bh / 2, p);
        spaced(c, label, l + w / 2, Art.centre(top + bh / 2, size, Art.BOLD), size, strong ? WHITE : 0xffc9ccd2, w, Paint.Align.CENTER);
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
        int ink = good ? GOOD : bad ? RED : WAIT;
        String lt = JeepTheme.linkText(s).replace(" ● ", "  ");
        float size = bh * 0.40f, cy = top + bh / 2;
        Paint p = Art.paint();
        p.setColor(ink);
        c.drawCircle(26, cy, bh * 0.13f, p);
        spaced(c, lt, 26 + bh * 0.4f, Art.centre(cy, size, Art.BOLD), size, ink, w * 0.4f, Paint.Align.LEFT);
        buttons.put("status", new RectF(8, top - 8, w * 0.4f, top + bh + 8));
        if (!s.detail.isEmpty()) {
            float ds = Art.fit(s.detail, 15, w - 60, Art.SANS);
            Art.text(c, s.detail, w / 2, Math.max(18, h * 0.035f), ds, ink, Art.SANS, Paint.Align.CENTER);
            buttons.put("status2", new RectF(30, 0, w - 30, Math.max(30, h * 0.05f)));
        } else if (!s.notice.isEmpty()) {
            float ds = Art.fit(s.notice, 15, w - 60, Art.SANS);
            Art.text(c, s.notice, w / 2, Math.max(18, h * 0.035f), ds, WAIT, Art.SANS, Paint.Align.CENTER);
            buttons.put("notice", new RectF(30, 0, w - 30, Math.max(30, h * 0.05f)));
        }
    }

    @Override
    public RectF buttonRect(String action) { return buttons.get(action); }

    /** The window in the look's dark glass, white lettering spaced out and grey. */
    @Override
    public MusicPainter.Dress musicDress() {
        MusicPainter.Dress d = new MusicPainter.Dress();
        d.frame = GLASS_BOTTOM;
        d.edge = 0xff3a3e46;
        d.title = new int[]{GLASS_TOP, GLASS_BOTTOM};
        d.titleInk = WHITE;
        d.close = new int[]{DIM};
        d.closeInk = WHITE;
        d.body = 0xff0b0c0e;
        d.ink = WHITE;
        d.soft = GREY;
        d.button = GLASS_TOP;
        d.buttonEdge = 0xff4a4f58;
        d.buttonInk = WHITE;
        d.spacing = 0.14f;
        d.corner = 14;
        d.face = Art.BOLD;
        d.titleFace = Art.BOLD;
        return d;
    }

    @Override
    public String hit(float x, float y) {
        for (Map.Entry<String, RectF> e : buttons.entrySet())
            if (e.getValue().contains(x, y)) return e.getKey().equals("status2") ? "status" : e.getKey();
        return null;
    }
}
