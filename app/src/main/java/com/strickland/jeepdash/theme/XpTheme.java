package com.strickland.jeepdash.theme;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import com.strickland.jeepdash.LinkService;
import com.strickland.jeepdash.core.GaugeSpec;
import com.strickland.jeepdash.core.GaugeType;
import com.strickland.jeepdash.core.Param;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The XP-era look: every gauge is a little window, with a taskbar along the bottom.
 * Original artwork in the spirit of the period (no Microsoft wallpaper or logos).
 */
public final class XpTheme implements Theme {

    public static final String[] SCHEMES = {"Blue", "Olive", "Silver"};
    static final float TASKBAR = 42;

    private static final int BODY = 0xffece9d8, INK = 0xff1b1b1b, SOFT = 0xff444444, GREEN = 0xff2fb52f, RED = 0xffd9302c;
    private static final int[] FLASH_TITLE = {0xffff8a7a, 0xffd9302c, 0xffb3201c};

    private int[] titleColors;
    private float[] titleStops;
    private int titleInk, frame;
    private final Map<String, RectF> buttons = new LinkedHashMap<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("h:mm a", Locale.US);
    private final Art.DialStyle dial = new Art.DialStyle();

    public XpTheme(String scheme) {
        setScheme(scheme);
        dial.bezel = 0xff1f3f8f;
        dial.faceInner = 0xffffffff;
        dial.faceOuter = 0xffd6e0f0;
        dial.tick = 0xff1b1b1b;
        dial.minorAlpha = 0.75f;
        dial.label = INK;
        dial.needle = RED;
        dial.hub = 0xff1f3f8f;
        dial.red = RED;
    }

    public void setScheme(String scheme) {
        if ("Olive".equals(scheme)) {
            titleColors = new int[]{0xffa3bb78, 0xff7d9356, 0xff6f844a, 0xff7f975a};
            titleStops = new float[]{0, 0.18f, 0.6f, 1};
            titleInk = 0xffffffff;
            frame = 0xff6f844a;
        } else if ("Silver".equals(scheme)) {
            titleColors = new int[]{0xfffbfbfd, 0xffd4d3e0, 0xffbdbccf};
            titleStops = new float[]{0, 0.4f, 1};
            titleInk = INK;
            frame = 0xff9d9cb5;
        } else {
            titleColors = new int[]{0xff0997ff, 0xff0053ee, 0xff0050ee, 0xff0066ff, 0xff0048d8};
            titleStops = new float[]{0, 0.14f, 0.5f, 0.86f, 1};
            titleInk = 0xffffffff;
            frame = 0xff0831d9;
        }
    }

    @Override
    public String id() { return "xp"; }

    @Override
    public void drawBackground(Canvas c, float w, float h) {
        Paint p = Art.paint();
        p.setShader(Art.vertical(0, h, new int[]{0xff245edb, 0xff4f8be8, 0xff86b4f0}, new float[]{0, 0.6f, 1}));
        c.drawRect(0, 0, w, h, p);
    }

    /** Draws the window frame and title; returns the title bar height. */
    private float window(Canvas c, float x, float y, float w, float h, String title, boolean flash) {
        float th = h < 200 ? 28 : 30;
        Paint p = Art.paint();
        p.setColor(0x59000000);
        c.drawRoundRect(Art.rect(x + 2, y + 3, x + w + 2, y + h + 3), 8, 8, p);
        p.setColor(flash ? 0xffb3201c : frame);
        c.drawRoundRect(Art.rect(x, y, x + w, y + h), 8, 8, p);
        p.setColor(flash ? 0xffffe3df : BODY);
        c.drawRect(x + 3, y + 3 + th, x + w - 3, y + h - 3, p);
        if (flash) p.setShader(Art.vertical(y + 3, y + 3 + th, FLASH_TITLE, new float[]{0, 0.5f, 1}));
        else p.setShader(Art.vertical(y + 3, y + 3 + th, titleColors, titleStops));
        c.drawRect(x + 3, y + 3, x + w - 3, y + 3 + th, p);
        float size = th < 30 ? 13 : 14;
        float room = w - 26 - (w > 250 ? 28 : 0);
        Art.text(c, title, x + 13, Art.centre(y + 3 + th / 2, size, Art.BOLD), Art.fit(title, size, room, Art.BOLD),
                flash ? 0xffffffff : titleInk, Art.BOLD, Paint.Align.LEFT);
        if (w > 250) closeBox(c, x + w - 3 - 5 - 22, y + 3 + (th - 22) / 2);
        return th;
    }

    private void closeBox(Canvas c, float x, float y) {
        Paint p = Art.paint();
        p.setShader(Art.vertical(y, y + 22, new int[]{0xffe8805d, 0xffc4401f}, null));
        c.drawRoundRect(Art.rect(x, y, x + 22, y + 22), 3, 3, p);
        p = Art.paint();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1);
        p.setColor(0xffffffff);
        c.drawRoundRect(Art.rect(x, y, x + 22, y + 22), 3, 3, p);
        p.setStrokeWidth(2.2f);
        p.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(x + 7, y + 7, x + 15, y + 15, p);
        c.drawLine(x + 15, y + 7, x + 7, y + 15, p);
    }

    @Override
    public void drawGauge(Canvas c, GaugeSpec g, Param p, Double value, Double shown, boolean flashOn, boolean editing) {
        float th = window(c, g.x, g.y, g.w, g.h, p.name, flashOn);
        float bx = g.x + 3, by = g.y + 3 + th, bw = g.w - 6, bh = g.h - 6 - th;
        if (bw < 20 || bh < 20) return;
        if (g.type == GaugeType.DIAL) dialBody(c, p, value, shown, bx, by, bw, bh);
        else if (g.type == GaugeType.DIGITAL) lcdBody(c, p, value, bx, by, bw, bh, flashOn);
        else barBody(c, p, value, shown, bx, by, bw, bh, flashOn);
    }

    private void dialBody(Canvas c, Param p, Double value, Double shown, float bx, float by, float bw, float bh) {
        String num = Art.reading(p, value);
        String unit = p.unit.equals("rpm") ? "RPM" : p.unit;
        if (bw >= bh * 1.6f) {
            // dial on the left, big number beside it (the Engine RPM window in the mockup)
            float R = Math.min(bh - 24, bw * 0.5f) / 2;
            float cx = bx + 24 + R, cy = by + bh / 2;
            Art.dial(c, cx, cy, R, p, shown, dial);
            if (Art.scale(p).labelDiv != 1)
                Art.text(c, "× 1000", cx, cy + R * 0.42f, Math.max(9, R * 0.075f), SOFT, Art.SANS, Paint.Align.CENTER);
            float left = cx + R + 28, room = bx + bw - 20 - left;
            if (room < 60) return;
            float unitSize = Math.min(26, bh * 0.07f + 4);
            float unitW = Art.width(unit, unitSize, Art.BOLD) + 10;
            float size = Art.fit("0000", Math.min(120, bh * 0.34f), room - unitW, Art.MONO);
            float base = cy + size * 0.2f;
            Art.text(c, num, left, base, size, INK, Art.MONO, Paint.Align.LEFT);
            Art.text(c, unit, left + Art.width(num, size, Art.MONO) + 10, base, unitSize, INK, Art.BOLD, Paint.Align.LEFT);
            if (bh > 250) {
                Paint pt = Art.paint();
                pt.setColor(0xffb9b4a0);
                c.drawRect(left, base + 14, left + room, base + 16, pt);
                String from = p.slow() ? "Generic reading: updates every 10 seconds" : "From the Jeep engine computer";
                Art.text(c, from, left, base + 40, Art.fit(from, 15, room, Art.SANS), SOFT, Art.SANS, Paint.Align.LEFT);
            }
        } else {
            float R = Math.min(bw, bh) / 2 - 8;
            float cx = bx + bw / 2, cy = by + bh / 2;
            Art.dial(c, cx, cy, R, p, shown, dial);
            String t = num + " " + unit;
            float size = Art.fit(t, Math.max(10, R * 0.2f), R * 1.1f, Art.MONO);
            Art.text(c, t, cx, cy + R * 0.66f, size, INK, Art.MONO, Paint.Align.CENTER);
        }
    }

    private void lcdBody(Canvas c, Param p, Double value, float bx, float by, float bw, float bh, boolean flash) {
        float pad = Math.min(18, Math.min(bw, bh) * 0.1f);
        float l = bx + pad, t = by + pad, r = bx + bw - pad, b = by + bh - pad;
        Paint pt = Art.paint();
        pt.setColor(flash ? 0xffe9b0a4 : 0xffb9c99e);
        c.drawRoundRect(Art.rect(l, t, r, b), 6, 6, pt);
        pt.setShader(Art.vertical(t, t + 10, new int[]{0x40000000, 0x00000000}, null));
        c.drawRoundRect(Art.rect(l, t, r, t + 10), 6, 6, pt);
        pt = Art.paint();
        pt.setStyle(Paint.Style.STROKE);
        pt.setStrokeWidth(2);
        pt.setColor(0xff7d8a68);
        c.drawRoundRect(Art.rect(l, t, r, b), 6, 6, pt);

        int ink = 0xff1e2a12;
        String num = Art.reading(p, value);
        String unit = p.unit.toUpperCase(Locale.US);
        float lw = r - l, lh = b - t, cx = (l + r) / 2;
        if (lh < 110 || unit.length() <= 1) {
            String s = unit.length() <= 1 ? num + unit : num + " " + unit;
            float size = Art.fit(s, Math.min(210, lh * 0.6f), lw * 0.9f, Art.MONO);
            Art.text(c, s, cx, Art.centre((t + b) / 2, size, Art.MONO), size, ink, Art.MONO, Paint.Align.CENTER);
        } else {
            float size = Art.fit("000", Math.min(210, lh * 0.62f), lw * 0.8f, Art.MONO);
            size = Art.fit(num, size, lw * 0.9f, Art.MONO);
            float unitSize = Math.max(14, size * 0.145f);
            float base = Art.centre((t + b) / 2 - unitSize * 0.7f, size, Art.MONO);
            Art.text(c, num, cx, base, size, ink, Art.MONO, Paint.Align.CENTER);
            Paint up = Art.paint();
            up.setTypeface(Art.BOLD);
            up.setTextSize(unitSize);
            up.setColor(ink);
            up.setTextAlign(Paint.Align.CENTER);
            up.setLetterSpacing(0.15f);
            c.drawText(unit, cx, base + unitSize * 1.5f, up);
        }
    }

    private void barBody(Canvas c, Param p, Double value, Double shown, float bx, float by, float bw, float bh, boolean flash) {
        float pad = Math.min(14, bw * 0.08f);
        float l = bx + pad, r = bx + bw - pad;
        String num = Art.reading(p, value);
        float unitSize = 17;
        float unitW = Art.width(p.unit, unitSize, Art.SANS) + 6;
        float size = Art.fit("000.0", Math.min(54, bh * 0.32f), r - l - unitW, Art.MONO);
        float base = by + pad + size * 0.85f;
        Art.text(c, num, l, base, size, INK, Art.MONO, Paint.Align.LEFT);
        Art.text(c, p.unit, l + Art.width(num, size, Art.MONO) + 6, base, unitSize, SOFT, Art.SANS, Paint.Align.LEFT);

        float labelBase = by + bh - pad;
        float barB = labelBase - 16, barT = barB - 22;
        if (barT < base + 6) return;
        Paint pt = Art.paint();
        pt.setColor(0xffffffff);
        c.drawRoundRect(Art.rect(l, barT, r, barB), 3, 3, pt);
        pt.setStyle(Paint.Style.STROKE);
        pt.setStrokeWidth(1);
        pt.setColor(0xff868686);
        c.drawRoundRect(Art.rect(l, barT, r, barB), 3, 3, pt);
        if (shown != null)
            Art.segments(c, l + 3, barT + 3, r - 3, barB - 3, Art.frac(p, shown), 10, 2, flash ? RED : GREEN);
        Art.text(c, Art.compact(p.min), l, labelBase, 12, 0xff555555, Art.SANS, Paint.Align.LEFT);
        Art.text(c, Art.compact(p.max), r, labelBase, 12, 0xff555555, Art.SANS, Paint.Align.RIGHT);
    }

    @Override
    public void drawPanel(Canvas c, float w, float h, String title, String message) {
        float pw = Math.min(560, w - 40), ph = 200;
        float x = (w - pw) / 2, y = (h - TASKBAR - ph) / 2;
        float th = window(c, x, y, pw, ph, title, false);
        float size = Art.fit(message, 20, pw - 60, Art.SANS);
        Art.text(c, message, x + pw / 2, Art.centre(y + th + (ph - th) / 2, size, Art.SANS), size, INK, Art.SANS, Paint.Align.CENTER);
    }

    // ---------------- taskbar ----------------

    private void button(Canvas c, String action, float x, float y, float w, float h, String label, int fill, int border, boolean bold) {
        Paint p = Art.paint();
        p.setColor(fill);
        c.drawRoundRect(Art.rect(x, y, x + w, y + h), 3, 3, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1);
        p.setColor(border);
        c.drawRoundRect(Art.rect(x, y, x + w, y + h), 3, 3, p);
        java.lang.String s = label;
        android.graphics.Typeface tf = bold ? Art.BOLD : Art.SANS;
        float size = Art.fit(s, 14, w - 20, tf);
        Art.text(c, s, x + 12, Art.centre(y + h / 2, size, tf), size, 0xffffffff, tf, Paint.Align.LEFT);
        buttons.put(action, new RectF(x, y - 5, x + w, y + h + 5));
    }

    static String statusText(Chrome s) {
        switch (s.status) {
            case LIVE: return s.detail.isEmpty() ? "Dongle connected" : "Key off?";
            case CONNECTING: return "Connecting";
            case SETTING_UP: return "Setting up";
            case RECONNECTING: return "Reconnecting";
            case BUSY: return "Can't reach dongle";
            case NO_PERMISSION: return "Needs permission";
            default: return "Looking for dongle";
        }
    }

    static int statusLight(Chrome s) {
        switch (s.status) {
            case LIVE: return s.detail.isEmpty() ? 0xff7cfc7c : 0xffffc04a;
            case BUSY:
            case NO_PERMISSION: return 0xffff5d5d;
            default: return 0xffffc04a;
        }
    }

    @Override
    public void drawChrome(Canvas c, float w, float h, Chrome s) {
        buttons.clear();
        float top = h - TASKBAR;
        Paint p = Art.paint();
        p.setShader(Art.vertical(top, h, new int[]{0xff3e86f0, 0xff245edb, 0xff1f54cf, 0xff1941a5}, new float[]{0, 0.12f, 0.88f, 1}));
        c.drawRect(0, top, w, h, p);

        // the green "Dash" button (settings menu)
        p = Art.paint();
        p.setShader(Art.vertical(top, h, new int[]{0xff5fbf5f, 0xff3a9b3a, 0xff2f8a2f}, new float[]{0, 0.5f, 1}));
        c.drawRoundRect(Art.rect(-14, top, 124, h), 14, 14, p);
        Art.text(c, "Dash", 62 + 1, Art.centre(top + TASKBAR / 2, 21, Art.ITALIC_BOLD) + 1, 21, 0x80000000, Art.ITALIC_BOLD, Paint.Align.CENTER);
        Art.text(c, "Dash", 62, Art.centre(top + TASKBAR / 2, 21, Art.ITALIC_BOLD), 21, 0xffffffff, Art.ITALIC_BOLD, Paint.Align.CENTER);
        buttons.put("menu", new RectF(0, top, 124, h));

        // tray on the right: link light, status, clock
        String st = statusText(s);
        if (s.status == LinkService.Status.LIVE && s.detail.isEmpty()) st += "  " + s.readsPerSec + "/s";
        String time = clock.format(new Date());
        float stW = Art.width(st, 14, Art.SANS), timeW = Art.width(time, 14, Art.SANS);
        float trayW = 16 + 10 + 7 + stW + 16 + timeW + 16;
        float trayX = w - trayW;
        p = Art.paint();
        p.setShader(Art.vertical(top, h, new int[]{0xff0d74c0, 0xff0a63a8}, null));
        c.drawRect(trayX, top, w, h, p);
        p = Art.paint();
        p.setColor(0xff08447a);
        c.drawRect(trayX, top, trayX + 1, h, p);
        p.setColor(statusLight(s));
        c.drawCircle(trayX + 16 + 5, top + TASKBAR / 2, 5, p);
        float tb = Art.centre(top + TASKBAR / 2, 14, Art.SANS);
        Art.text(c, st, trayX + 16 + 10 + 7, tb, 14, 0xffffffff, Art.SANS, Paint.Align.LEFT);
        Art.text(c, time, w - 16, tb, 14, 0xffffffff, Art.SANS, Paint.Align.RIGHT);
        buttons.put("status", new RectF(trayX, top, w, h));

        // look switch, just left of the tray
        float by = top + 5, bh = 32;
        float swW = 132, swX = trayX - 10 - swW;
        p = Art.paint();
        p.setShader(Art.vertical(by, by + bh, new int[]{0xff2f8a2f, 0xff23701f}, null));
        c.drawRoundRect(Art.rect(swX, by, swX + swW, by + bh), 3, 3, p);
        p = Art.paint();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1);
        p.setColor(0xff154f12);
        c.drawRoundRect(Art.rect(swX, by, swX + swW, by + bh), 3, 3, p);
        Art.swapIcon(c, swX + 12, by + 8, 16, 0xffffffff);
        Art.text(c, "Jeep style", swX + 36, Art.centre(by + bh / 2, 14, Art.BOLD), 14, 0xffffffff, Art.BOLD, Paint.Align.LEFT);
        buttons.put("switch", new RectF(swX, top, swX + swW, h));

        // task buttons between the Dash button and the switch
        float x = 134, room = swX - 10 - x;
        if (s.editing) {
            float bw = Math.min(170, (room - 12) / 3);
            button(c, "add", x, by, bw, bh, "+ Add gauge", 0xff1e4fc0, 0xff0e2f7a, false);
            button(c, "snap", x + bw + 6, by, bw, bh, s.snap ? "Snap to grid: on" : "Snap to grid: off", 0xff1e4fc0, 0xff0e2f7a, false);
            button(c, "done", x + 2 * (bw + 6), by, bw, bh, "Done", 0xff2f8a2f, 0xff154f12, true);
        } else {
            int n = s.canEdit ? 4 : 3;
            float bw = Math.min(170, (room - 6 * (n - 1)) / n);
            button(c, "tab:gauges", x, by, bw, bh, "Gauges", "gauges".equals(s.tab) ? 0xff143a94 : 0xff1e4fc0, 0xff0e2f7a, false);
            button(c, "tab:viz", x + bw + 6, by, bw, bh, "Visualizer", "viz".equals(s.tab) ? 0xff143a94 : 0xff1e4fc0, 0xff0e2f7a, false);
            button(c, "logs", x + 2 * (bw + 6), by, bw, bh, "Drive log", 0xff1e4fc0, 0xff0e2f7a, false);
            if (s.canEdit) button(c, "edit", x + 3 * (bw + 6), by, bw, bh, "Edit gauges", 0xff1e4fc0, 0xff0e2f7a, false);
        }

        // a balloon above the tray when there is something to tell the driver
        if (!s.detail.isEmpty()) {
            float size = Art.fit(s.detail, 14, w - 60, Art.SANS);
            float tw = Art.width(s.detail, size, Art.SANS);
            float bx2 = w - 10, bx1 = bx2 - tw - 24, bb = top - 8, bt = bb - 34;
            p = Art.paint();
            p.setColor(0xffffffe1);
            c.drawRoundRect(Art.rect(bx1, bt, bx2, bb), 6, 6, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1);
            p.setColor(0xff000000);
            c.drawRoundRect(Art.rect(bx1, bt, bx2, bb), 6, 6, p);
            Art.text(c, s.detail, bx1 + 12, Art.centre((bt + bb) / 2, size, Art.SANS), size, 0xff000000, Art.SANS, Paint.Align.LEFT);
            buttons.put("status2", new RectF(bx1, bt, bx2, bb));
        }
    }

    @Override
    public String hit(float x, float y) {
        for (Map.Entry<String, RectF> e : buttons.entrySet())
            if (e.getValue().contains(x, y)) return e.getKey().equals("status2") ? "status" : e.getKey();
        return null;
    }
}
