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
 * The classic look: a black instrument cluster with lit markings and orange needles.
 * Original artwork in the style of a 90s cluster (no Jeep logo artwork).
 */
public final class JeepTheme implements Theme {

    public static final String[] BACKLIGHTS = {"White", "Green", "Amber"};

    private static final int NEEDLE = 0xffff5a1a, RED = 0xffe0301e, READOUT = 0xff5dff9a;

    private int light = 0xfff2f2f2;
    private final Map<String, RectF> buttons = new LinkedHashMap<>();
    private final Art.DialStyle dial = new Art.DialStyle();

    public JeepTheme(String backlight) {
        dial.faceInner = 0xff1a1a1a;
        dial.faceOuter = 0xff040404;
        dial.minorAlpha = 0.7f;
        dial.needle = NEEDLE;
        dial.hub = 0xff222222;
        dial.hubRing = 0xff3a3a3a;
        dial.red = RED;
        dial.font = Art.COND;
        dial.labelSize = 0.15f;
        setBacklight(backlight);
    }

    public void setBacklight(String name) {
        light = "Green".equals(name) ? 0xff8cff9e : "Amber".equals(name) ? 0xffffb347 : 0xfff2f2f2;
        dial.tick = light;
        dial.label = light;
    }

    @Override
    public String id() { return "jeep"; }

    @Override
    public void drawBackground(Canvas c, float w, float h) {
        Paint p = Art.paint();
        p.setColor(0xff050505);
        c.drawRect(0, 0, w, h, p);
        // the cluster housing, where the mockup has it
        float sx = w / 1280f, sy = h / 800f;
        float l = 40 * sx, t = 40 * sy, r = 1240 * sx, b = 600 * sy;
        p.setShader(Art.vertical(t, b, new int[]{0xff1d1d1d, 0xff0c0c0c}, null));
        c.drawRoundRect(Art.rect(l, t, r, b), 48, 48, p);
        p = Art.paint();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2);
        p.setColor(0xff2b2b2b);
        c.drawRoundRect(Art.rect(l, t, r, b), 48, 48, p);
    }

    private static String caption(Param p) {
        if (p.id.equals("rpm")) return "RPM × 1000";
        if (p.id.equals("speed")) return p.unit.toUpperCase(Locale.US);
        return p.name.toUpperCase(Locale.US);
    }

    private static String withUnit(Param p, Double v) {
        String s = Art.reading(p, v);
        if (p.unit.equals("rpm") || p.unit.equals("mph") || p.unit.equals("km/h")) return s;
        return p.unit.startsWith("°") && p.unit.length() == 1 ? s + p.unit : s + " " + p.unit;
    }

    private void spaced(Canvas c, String s, float x, float base, float size, int color, float maxW) {
        Paint p = Art.paint();
        p.setTypeface(Art.COND);
        p.setLetterSpacing(0.12f);
        p.setTextSize(size);
        float w = p.measureText(s);
        if (w > maxW && w > 0) p.setTextSize(Math.max(6, size * maxW / w));
        p.setColor(color);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(s, x, base, p);
    }

    @Override
    public void drawGauge(Canvas c, GaugeSpec g, Param p, Double value, Double shown, boolean flashOn, boolean editing) {
        if (g.type == GaugeType.DIAL) dialGauge(c, g, p, value, shown, flashOn);
        else if (g.type == GaugeType.DIGITAL) readout(c, g, p, value, flashOn);
        else bar(c, g, p, value, shown, flashOn);
    }

    private void dialGauge(Canvas c, GaugeSpec g, Param p, Double value, Double shown, boolean flash) {
        dial.bezel = flash ? RED : 0xff2c2c2c;
        int ink = flash ? RED : light;
        float cx = g.x + g.w / 2;
        if (g.h >= g.w * 1.2f) {
            // small dial with its name and reading underneath
            float D = Math.min(g.w, g.h - 48);
            float R = D / 2, cy = g.y + R;
            Art.dial(c, cx, cy, R, p, shown, dial);
            spaced(c, caption(p), cx, g.y + D + 18, 15, ink, g.w);
            String v = withUnit(p, value);
            Art.text(c, v, cx, g.y + D + 43, Art.fit(v, 22, g.w, Art.MONO), ink, Art.MONO, Paint.Align.CENTER);
        } else {
            // big dial: caption above the hub, reading below it
            float R = Math.min(g.w, g.h) / 2, cy = g.y + g.h / 2;
            Art.dial(c, cx, cy, R, p, shown, dial);
            spaced(c, caption(p), cx, cy - R * 0.25f, Math.max(8, R * 0.09f), ink, R * 0.9f);
            String v = withUnit(p, value);
            float size = Art.fit(v, Math.max(10, R * 0.21f), R * 0.8f, Art.MONO);
            Art.text(c, v, cx, cy + R * 0.47f, size, ink, Art.MONO, Paint.Align.CENTER);
        }
    }

    private void glowText(Canvas c, String s, float x, float base, float size, int color, Paint.Align align) {
        Paint p = Art.paint();
        p.setTypeface(Art.MONO);
        p.setTextSize(size);
        p.setColor(color);
        p.setTextAlign(align);
        p.setShadowLayer(6, 0, 0, (color & 0x00ffffff) | 0x8c000000);
        c.drawText(s, x, base, p);
    }

    private void strip(Canvas c, float l, float t, float r, float b, boolean flash) {
        Paint p = Art.paint();
        p.setColor(flash ? 0xff1c0705 : 0xff06140c);
        c.drawRoundRect(Art.rect(l, t, r, b), 10, 10, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2);
        p.setColor(flash ? RED : 0xff1f2a22);
        c.drawRoundRect(Art.rect(l, t, r, b), 10, 10, p);
    }

    private void readout(Canvas c, GaugeSpec g, Param p, Double value, boolean flash) {
        strip(c, g.x, g.y, g.x + g.w, g.y + g.h, flash);
        int ink = flash ? 0xffff6a5a : READOUT;
        String name = p.name.toUpperCase(Locale.US);
        String v = Art.reading(p, value) + (p.unit.equals("%") ? "%" : " " + p.unit.toUpperCase(Locale.US));
        float pad = Math.min(28, g.w * 0.08f);
        if (g.h < 140) {
            String s = name + " " + v;
            float size = Art.fit(s, Math.min(26, g.h * 0.33f), g.w - 2 * pad, Art.MONO);
            glowText(c, s, g.x + pad, Art.centre(g.y + g.h / 2, size, Art.MONO), size, ink, Paint.Align.LEFT);
        } else {
            float nameSize = Art.fit(name, Math.min(22, g.h * 0.12f), g.w - 2 * pad, Art.MONO);
            glowText(c, name, g.x + g.w / 2, g.y + pad + nameSize, nameSize, ink, Paint.Align.CENTER);
            float size = Art.fit(v, g.h * 0.42f, g.w - 2 * pad, Art.MONO);
            glowText(c, v, g.x + g.w / 2, Art.centre(g.y + g.h * 0.58f, size, Art.MONO), size, ink, Paint.Align.CENTER);
        }
    }

    private void bar(Canvas c, GaugeSpec g, Param p, Double value, Double shown, boolean flash) {
        int ink = flash ? RED : light;
        float pad = Math.min(12, g.w * 0.06f);
        float l = g.x + pad, r = g.x + g.w - pad, cx = g.x + g.w / 2;
        float nameSize = Math.min(18, g.h * 0.12f + 4);
        float nameBase = g.y + pad + nameSize;
        spaced(c, caption(p), cx, nameBase, nameSize, ink, r - l);
        String v = withUnit(p, value);
        float size = Art.fit(v, Math.min(40, g.h * 0.2f), r - l, Art.MONO);
        float base = nameBase + size * 1.25f;
        Art.text(c, v, cx, base, size, ink, Art.MONO, Paint.Align.CENTER);

        float labelBase = g.y + g.h - pad;
        float barB = labelBase - 16, barT = Math.max(base + 10, barB - Math.min(40, g.h * 0.3f));
        if (barB - barT < 6) return;
        Paint p2 = Art.paint();
        p2.setColor(0xff161616);
        c.drawRect(l, barT, r, barB, p2);
        if (shown != null) Art.segments(c, l, barT, r, barB, Art.frac(p, shown), 8, 3, flash ? RED : light);
        Art.text(c, Art.compact(p.min), l, labelBase, 12, ink, Art.COND, Paint.Align.LEFT);
        Art.text(c, Art.compact(p.max), r, labelBase, 12, ink, Art.COND, Paint.Align.RIGHT);
    }

    @Override
    public void drawPanel(Canvas c, float w, float h, String title, String message) {
        float pw = Math.min(620, w - 40), ph = 160;
        float x = (w - pw) / 2, y = (h - ph) / 2;
        strip(c, x, y, x + pw, y + ph, false);
        glowText(c, title.toUpperCase(Locale.US), w / 2, y + 56, 26, READOUT, Paint.Align.CENTER);
        float size = Art.fit(message, 20, pw - 48, Art.MONO);
        glowText(c, message, w / 2, y + 104, size, READOUT, Paint.Align.CENTER);
    }

    // ---------------- buttons along the bottom ----------------

    /** A pill button whose right edge is at {@code right}; returns its left edge. */
    private float pill(Canvas c, String action, float right, float top, String label, boolean swap, boolean strong) {
        float h = 44, textSize = 18;
        Paint m = Art.paint();
        m.setTypeface(Art.COND);
        m.setLetterSpacing(0.06f);
        m.setTextSize(textSize);
        float tw = m.measureText(label);
        float w = tw + 36 + (swap ? 28 : 0);
        float l = right - w;
        Paint p = Art.paint();
        p.setColor(strong ? 0xff2a1a12 : 0xff141414);
        c.drawRoundRect(Art.rect(l, top, right, top + h), h / 2, h / 2, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2);
        p.setColor(strong ? NEEDLE : 0xff3a3a3a);
        c.drawRoundRect(Art.rect(l, top, right, top + h), h / 2, h / 2, p);
        if (swap) Art.swapIcon(c, l + 16, top + 13, 18, light);
        Paint t = Art.paint();
        t.setTypeface(Art.COND);
        t.setLetterSpacing(0.06f);
        t.setTextSize(textSize);
        t.setColor(light);
        Paint.FontMetrics fm = t.getFontMetrics();
        c.drawText(label, l + 18 + (swap ? 28 : 0), top + h / 2 - (fm.ascent + fm.descent) / 2, t);
        buttons.put(action, new RectF(l - 4, top - 8, right + 4, top + h + 8));
        return l;
    }

    static String linkText(Chrome s) {
        switch (s.status) {
            case LIVE: return s.detail.isEmpty() ? "LINK ● " + s.readsPerSec + "/s" : "KEY OFF";
            case CONNECTING: return "CONNECTING";
            case SETTING_UP: return "SETTING UP";
            case RECONNECTING: return "RECONNECTING";
            case BUSY: return "NO DONGLE";
            case NO_PERMISSION: return "NEEDS PERMISSION";
            default: return "SEARCHING";
        }
    }

    @Override
    public void drawChrome(Canvas c, float w, float h, Chrome s) {
        buttons.clear();
        float top = h - 22 - 44;
        float x = w - 28;
        if (s.editing) {
            x = pill(c, "done", x, top, "DONE", false, true) - 10;
            x = pill(c, "snap", x, top, s.snap ? "SNAP ON" : "SNAP OFF", false, false) - 10;
            pill(c, "add", x, top, "+ GAUGE", false, false);
        } else {
            x = pill(c, "switch", x, top, s.nextLook == null ? "XP STYLE" : s.nextLook, true, false) - 10;
            x = pill(c, "menu", x, top, "SETUP", false, false) - 10;
            x = pill(c, "logs", x, top, "LOGS", false, false) - 10;
            if (s.canEdit) pill(c, "edit", x, top, "EDIT", false, false);
        }

        // link status, bottom left, as a small green readout
        boolean good = s.status == LinkService.Status.LIVE && s.detail.isEmpty();
        boolean bad = s.status == LinkService.Status.BUSY || s.status == LinkService.Status.NO_PERMISSION;
        int ink = good ? READOUT : bad ? 0xffff6a5a : 0xffffc04a;
        String lt = linkText(s);
        float lw = Art.width(lt, 20, Art.MONO) + 36;
        strip(c, 28, top, 28 + lw, top + 44, false);
        glowText(c, lt, 28 + 18, Art.centre(top + 22, 20, Art.MONO), 20, ink, Paint.Align.LEFT);
        buttons.put("status", new RectF(20, top - 8, 28 + lw + 8, top + 52));

        // anything the driver should know goes across the top edge
        if (!s.detail.isEmpty()) {
            float size = Art.fit(s.detail, 16, w - 80, Art.COND);
            Art.text(c, s.detail, w / 2, 27, size, ink, Art.COND, Paint.Align.CENTER);
            buttons.put("status2", new RectF(40, 0, w - 40, 38));
        }
    }

    @Override
    public String hit(float x, float y) {
        for (Map.Entry<String, RectF> e : buttons.entrySet())
            if (e.getValue().contains(x, y)) return e.getKey().equals("status2") ? "status" : e.getKey();
        return null;
    }
}
