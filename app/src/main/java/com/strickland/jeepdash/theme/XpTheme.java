package com.strickland.jeepdash.theme;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import com.strickland.jeepdash.LinkService;
import com.strickland.jeepdash.core.GaugeSpec;
import com.strickland.jeepdash.core.GaugeType;
import com.strickland.jeepdash.core.NightHours;
import com.strickland.jeepdash.core.Param;

import java.text.SimpleDateFormat;
import java.util.Calendar;
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
    /** Light, Dark, or Auto (dark from 7 pm to 7 am by the tablet's clock; see NightHours). */
    public static final String[] MODES = {NightHours.LIGHT, NightHours.DARK, NightHours.AUTO};
    static final float TASKBAR = 42;

    /**
     * Every colour the look uses. The light set is the original daytime look; the dark set is for night
     * driving: near-black desktop, dark windows with light text, and each scheme's own colours much darker.
     */
    static final class Palette {
        int[] desktop;
        float[] desktopStops;
        int body, ink, soft, shadow, divider, scaleText, green, red;
        int flashFrame, flashBody;
        int[] flashTitle;
        int[] titleColors;
        float[] titleStops;
        int titleInk, frame;
        int[] closeBox;
        int closeInk;
        int lcd, lcdFlash, lcdEdge, lcdShade, lcdInk;
        int barBack, barEdge;
        final Art.DialStyle dial = new Art.DialStyle();
        int[] taskbar;
        int[] start;
        int startInk, startShadow;
        int[] tray;
        int trayEdge, trayInk;
        int[] lookButton;
        int lookEdge;
        int task, taskOn, taskEdge, taskInk, doneFill, doneEdge;
        int balloon, balloonEdge, balloonInk;

        /** The original daytime colours, unchanged. */
        static Palette light(String scheme) {
            Palette p = new Palette();
            p.desktop = new int[]{0xff245edb, 0xff4f8be8, 0xff86b4f0};
            p.desktopStops = new float[]{0, 0.6f, 1};
            p.body = 0xffece9d8;
            p.ink = 0xff1b1b1b;
            p.soft = 0xff444444;
            p.shadow = 0x59000000;
            p.divider = 0xffb9b4a0;
            p.scaleText = 0xff555555;
            p.green = 0xff2fb52f;
            p.red = 0xffd9302c;
            p.flashFrame = 0xffb3201c;
            p.flashBody = 0xffffe3df;
            p.flashTitle = new int[]{0xffff8a7a, 0xffd9302c, 0xffb3201c};
            if ("Olive".equals(scheme)) {
                p.titleColors = new int[]{0xffa3bb78, 0xff7d9356, 0xff6f844a, 0xff7f975a};
                p.titleStops = new float[]{0, 0.18f, 0.6f, 1};
                p.titleInk = 0xffffffff;
                p.frame = 0xff6f844a;
            } else if ("Silver".equals(scheme)) {
                p.titleColors = new int[]{0xfffbfbfd, 0xffd4d3e0, 0xffbdbccf};
                p.titleStops = new float[]{0, 0.4f, 1};
                p.titleInk = p.ink;
                p.frame = 0xff9d9cb5;
            } else {
                p.titleColors = new int[]{0xff0997ff, 0xff0053ee, 0xff0050ee, 0xff0066ff, 0xff0048d8};
                p.titleStops = new float[]{0, 0.14f, 0.5f, 0.86f, 1};
                p.titleInk = 0xffffffff;
                p.frame = 0xff0831d9;
            }
            p.closeBox = new int[]{0xffe8805d, 0xffc4401f};
            p.closeInk = 0xffffffff;
            p.lcd = 0xffb9c99e;
            p.lcdFlash = 0xffe9b0a4;
            p.lcdEdge = 0xff7d8a68;
            p.lcdShade = 0x40000000;
            p.lcdInk = 0xff1e2a12;
            p.barBack = 0xffffffff;
            p.barEdge = 0xff868686;
            Art.DialStyle d = p.dial;
            d.bezel = 0xff1f3f8f;
            d.faceInner = 0xffffffff;
            d.faceOuter = 0xffd6e0f0;
            d.tick = 0xff1b1b1b;
            d.minorAlpha = 0.75f;
            d.label = p.ink;
            d.needle = p.red;
            d.hub = 0xff1f3f8f;
            d.red = p.red;
            // the taskbar stays the classic blue whichever window colour is chosen, as it always has
            p.taskbar = new int[]{0xff3e86f0, 0xff245edb, 0xff1f54cf, 0xff1941a5};
            p.start = new int[]{0xff5fbf5f, 0xff3a9b3a, 0xff2f8a2f};
            p.startInk = 0xffffffff;
            p.startShadow = 0x80000000;
            p.tray = new int[]{0xff0d74c0, 0xff0a63a8};
            p.trayEdge = 0xff08447a;
            p.trayInk = 0xffffffff;
            p.lookButton = new int[]{0xff2f8a2f, 0xff23701f};
            p.lookEdge = 0xff154f12;
            p.task = 0xff1e4fc0;
            p.taskOn = 0xff143a94;
            p.taskEdge = 0xff0e2f7a;
            p.taskInk = 0xffffffff;
            p.doneFill = 0xff2f8a2f;
            p.doneEdge = 0xff154f12;
            p.balloon = 0xffffffe1;
            p.balloonEdge = 0xff000000;
            p.balloonInk = 0xff000000;
            return p;
        }

        /** Night colours: nothing large and bright, warnings still plainly red. */
        static Palette dark(String scheme) {
            Palette day = light(scheme);
            Palette p = new Palette();
            p.desktop = new int[]{0xff07090e, 0xff0b0f18, 0xff111723};
            p.desktopStops = day.desktopStops;
            p.body = 0xff2a2a2d;
            p.ink = 0xffe4e4e4;
            p.soft = 0xffa6a6a6;
            p.shadow = 0x80000000;
            p.divider = 0xff4a4a4e;
            p.scaleText = 0xff9a9a9a;
            p.green = 0xff2e9a3a;
            p.red = 0xffe5483e;
            p.flashFrame = 0xffb3201c;
            p.flashBody = 0xff5a1a16;
            p.flashTitle = new int[]{0xffc94a3e, 0xffb02a22, 0xff8c1c16};
            p.titleColors = new int[day.titleColors.length];
            for (int i = 0; i < day.titleColors.length; i++) p.titleColors[i] = darker(day.titleColors[i], 0.34f);
            p.titleStops = day.titleStops;
            p.titleInk = 0xffe4e4e4;
            p.frame = darker(day.frame, 0.4f);
            p.closeBox = new int[]{0xff8c4630, 0xff6c2c18};
            p.closeInk = 0xffcfcfcf;
            p.lcd = 0xff1d2418;
            p.lcdFlash = 0xff5e1f18;
            p.lcdEdge = 0xff3b4632;
            p.lcdShade = 0x40000000;
            p.lcdInk = 0xffb4cf96;
            p.barBack = 0xff18181a;
            p.barEdge = 0xff58585c;
            Art.DialStyle d = p.dial;
            d.bezel = darker(day.frame, 0.35f);
            d.faceInner = 0xff2c2e33;
            d.faceOuter = 0xff1b1c20;
            d.tick = 0xffd6d6d6;
            d.minorAlpha = 0.7f;
            d.label = 0xffe0e0e0;
            d.needle = p.red;
            d.hub = darker(day.frame, 0.45f);
            d.red = 0xffc0392f;
            // taskbar and tray in a much darker version of the scheme's own colour
            int f = day.frame;
            p.taskbar = new int[]{darker(f, 0.34f), darker(f, 0.26f), darker(f, 0.24f), darker(f, 0.18f)};
            p.start = new int[]{0xff2c6a2c, 0xff215421, 0xff1b471b};
            p.startInk = 0xffd8d8d8;
            p.startShadow = 0x80000000;
            p.tray = new int[]{darker(f, 0.3f), darker(f, 0.22f)};
            p.trayEdge = darker(f, 0.12f);
            p.trayInk = 0xffd8d8d8;
            p.lookButton = new int[]{0xff215421, 0xff1a431a};
            p.lookEdge = 0xff0d2a0b;
            p.task = darker(f, 0.42f);
            p.taskOn = darker(f, 0.28f);
            p.taskEdge = darker(f, 0.16f);
            p.taskInk = 0xffd8d8d8;
            p.doneFill = 0xff215421;
            p.doneEdge = 0xff0d2a0b;
            p.balloon = 0xff3a3828;
            p.balloonEdge = 0xff111111;
            p.balloonInk = 0xffeeeacc;
            return p;
        }

        /** The same colour with its red, green and blue scaled down (k = 0.3 is a third as bright). */
        static int darker(int c, float k) {
            int r = Math.round(((c >> 16) & 0xff) * k), g = Math.round(((c >> 8) & 0xff) * k), b = Math.round((c & 0xff) * k);
            return (c & 0xff000000) | (r << 16) | (g << 8) | b;
        }
    }

    private Palette lightSet, darkSet, pal;
    private String mode = NightHours.LIGHT;
    private static final long PICK_EVERY_MS = 5000;
    private long pickedAt;
    private final Map<String, RectF> buttons = new LinkedHashMap<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("h:mm a", Locale.US);

    public XpTheme(String scheme) { setScheme(scheme); }

    public void setScheme(String scheme) {
        lightSet = Palette.light(scheme);
        darkSet = Palette.dark(scheme);
        pick(true);
    }

    public void setMode(String m) {
        mode = m;
        pick(true);
    }

    /**
     * Light or dark for now. On Auto this follows the tablet's clock: looked at when the dash is drawn, at most
     * every few seconds (DashView draws at least twice a minute even with nothing changing).
     */
    private void pick(boolean now) {
        long t = System.currentTimeMillis();
        if (!now && pal != null && Math.abs(t - pickedAt) < PICK_EVERY_MS) return;
        pickedAt = t;
        pal = NightHours.dark(mode, Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) ? darkSet : lightSet;
    }

    @Override
    public String id() { return "xp"; }

    @Override
    public void drawBackground(Canvas c, float w, float h) {
        pick(false);    // first thing in a frame: Auto turns dark or light here
        Paint p = Art.paint();
        p.setShader(Art.vertical(0, h, pal.desktop, pal.desktopStops));
        c.drawRect(0, 0, w, h, p);
    }

    /** Draws the window frame and title; returns the title bar height. */
    private float window(Canvas c, float x, float y, float w, float h, String title, boolean flash) {
        float th = h < 200 ? 28 : 30;
        Paint p = Art.paint();
        p.setColor(pal.shadow);
        c.drawRoundRect(Art.rect(x + 2, y + 3, x + w + 2, y + h + 3), 8, 8, p);
        p.setColor(flash ? pal.flashFrame : pal.frame);
        c.drawRoundRect(Art.rect(x, y, x + w, y + h), 8, 8, p);
        p.setColor(flash ? pal.flashBody : pal.body);
        c.drawRect(x + 3, y + 3 + th, x + w - 3, y + h - 3, p);
        if (flash) p.setShader(Art.vertical(y + 3, y + 3 + th, pal.flashTitle, new float[]{0, 0.5f, 1}));
        else p.setShader(Art.vertical(y + 3, y + 3 + th, pal.titleColors, pal.titleStops));
        c.drawRect(x + 3, y + 3, x + w - 3, y + 3 + th, p);
        float size = th < 30 ? 13 : 14;
        float room = w - 26 - (w > 250 ? 28 : 0);
        Art.text(c, title, x + 13, Art.centre(y + 3 + th / 2, size, Art.BOLD), Art.fit(title, size, room, Art.BOLD),
                flash ? 0xffffffff : pal.titleInk, Art.BOLD, Paint.Align.LEFT);
        if (w > 250) closeBox(c, x + w - 3 - 5 - 22, y + 3 + (th - 22) / 2);
        return th;
    }

    private void closeBox(Canvas c, float x, float y) {
        Paint p = Art.paint();
        p.setShader(Art.vertical(y, y + 22, pal.closeBox, null));
        c.drawRoundRect(Art.rect(x, y, x + 22, y + 22), 3, 3, p);
        p = Art.paint();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1);
        p.setColor(pal.closeInk);
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
            Art.dial(c, cx, cy, R, p, shown, pal.dial);
            if (Art.scale(p).labelDiv != 1)
                Art.text(c, "× 1000", cx, cy + R * 0.42f, Math.max(9, R * 0.075f), pal.soft, Art.SANS, Paint.Align.CENTER);
            float left = cx + R + 28, room = bx + bw - 20 - left;
            if (room < 60) return;
            float unitSize = Math.min(26, bh * 0.07f + 4);
            float unitW = Art.width(unit, unitSize, Art.BOLD) + 10;
            float size = Art.fit("0000", Math.min(120, bh * 0.34f), room - unitW, Art.MONO);
            float base = cy + size * 0.2f;
            Art.text(c, num, left, base, size, pal.ink, Art.MONO, Paint.Align.LEFT);
            Art.text(c, unit, left + Art.width(num, size, Art.MONO) + 10, base, unitSize, pal.ink, Art.BOLD, Paint.Align.LEFT);
            if (bh > 250) {
                Paint pt = Art.paint();
                pt.setColor(pal.divider);
                c.drawRect(left, base + 14, left + room, base + 16, pt);
                String from = "From the Jeep engine computer";
                Art.text(c, from, left, base + 40, Art.fit(from, 15, room, Art.SANS), pal.soft, Art.SANS, Paint.Align.LEFT);
            }
        } else {
            float R = Math.min(bw, bh) / 2 - 8;
            float cx = bx + bw / 2, cy = by + bh / 2;
            Art.dial(c, cx, cy, R, p, shown, pal.dial);
            String t = num + " " + unit;
            float size = Art.fit(t, Math.max(10, R * 0.2f), R * 1.1f, Art.MONO);
            Art.text(c, t, cx, cy + R * 0.66f, size, pal.ink, Art.MONO, Paint.Align.CENTER);
        }
    }

    private void lcdBody(Canvas c, Param p, Double value, float bx, float by, float bw, float bh, boolean flash) {
        float pad = Math.min(18, Math.min(bw, bh) * 0.1f);
        float l = bx + pad, t = by + pad, r = bx + bw - pad, b = by + bh - pad;
        Paint pt = Art.paint();
        pt.setColor(flash ? pal.lcdFlash : pal.lcd);
        c.drawRoundRect(Art.rect(l, t, r, b), 6, 6, pt);
        pt.setShader(Art.vertical(t, t + 10, new int[]{pal.lcdShade, 0x00000000}, null));
        c.drawRoundRect(Art.rect(l, t, r, t + 10), 6, 6, pt);
        pt = Art.paint();
        pt.setStyle(Paint.Style.STROKE);
        pt.setStrokeWidth(2);
        pt.setColor(pal.lcdEdge);
        c.drawRoundRect(Art.rect(l, t, r, b), 6, 6, pt);

        int ink = pal.lcdInk;
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
        Art.text(c, num, l, base, size, pal.ink, Art.MONO, Paint.Align.LEFT);
        Art.text(c, p.unit, l + Art.width(num, size, Art.MONO) + 6, base, unitSize, pal.soft, Art.SANS, Paint.Align.LEFT);

        float labelBase = by + bh - pad;
        float barB = labelBase - 16, barT = barB - 22;
        if (barT < base + 6) return;
        Paint pt = Art.paint();
        pt.setColor(pal.barBack);
        c.drawRoundRect(Art.rect(l, barT, r, barB), 3, 3, pt);
        pt.setStyle(Paint.Style.STROKE);
        pt.setStrokeWidth(1);
        pt.setColor(pal.barEdge);
        c.drawRoundRect(Art.rect(l, barT, r, barB), 3, 3, pt);
        if (shown != null)
            Art.segments(c, l + 3, barT + 3, r - 3, barB - 3, Art.frac(p, shown), 10, 2, flash ? pal.red : pal.green);
        Art.text(c, Art.compact(p.min), l, labelBase, 12, pal.scaleText, Art.SANS, Paint.Align.LEFT);
        Art.text(c, Art.compact(p.max), r, labelBase, 12, pal.scaleText, Art.SANS, Paint.Align.RIGHT);
    }

    @Override
    public void drawPanel(Canvas c, float w, float h, String title, String message) {
        float pw = Math.min(560, w - 40), ph = 200;
        float x = (w - pw) / 2, y = (h - TASKBAR - ph) / 2;
        float th = window(c, x, y, pw, ph, title, false);
        float size = Art.fit(message, 20, pw - 60, Art.SANS);
        Art.text(c, message, x + pw / 2, Art.centre(y + th + (ph - th) / 2, size, Art.SANS), size, pal.ink, Art.SANS, Paint.Align.CENTER);
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
        Art.text(c, s, x + 12, Art.centre(y + h / 2, size, tf), size, pal.taskInk, tf, Paint.Align.LEFT);
        buttons.put(action, new RectF(x, y - 5, x + w, y + h + 5));
    }

    static String statusText(Chrome s) {
        switch (s.status) {
            case LIVE: return s.detail.isEmpty() ? "Dongle connected" : "Waiting for engine";
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
        p.setShader(Art.vertical(top, h, pal.taskbar, new float[]{0, 0.12f, 0.88f, 1}));
        c.drawRect(0, top, w, h, p);

        // the green "Dash" button (settings menu)
        p = Art.paint();
        p.setShader(Art.vertical(top, h, pal.start, new float[]{0, 0.5f, 1}));
        c.drawRoundRect(Art.rect(-14, top, 124, h), 14, 14, p);
        Art.text(c, "Dash", 62 + 1, Art.centre(top + TASKBAR / 2, 21, Art.ITALIC_BOLD) + 1, 21, pal.startShadow, Art.ITALIC_BOLD, Paint.Align.CENTER);
        Art.text(c, "Dash", 62, Art.centre(top + TASKBAR / 2, 21, Art.ITALIC_BOLD), 21, pal.startInk, Art.ITALIC_BOLD, Paint.Align.CENTER);
        buttons.put("menu", new RectF(0, top, 124, h));

        // tray on the right: link light, status, clock
        String st = statusText(s);
        if (s.status == LinkService.Status.LIVE && s.detail.isEmpty()) st += "  " + s.readsPerSec + "/s";
        String time = clock.format(new Date());
        float stW = Art.width(st, 14, Art.SANS), timeW = Art.width(time, 14, Art.SANS);
        float noteW = s.music ? 34 : 0;                    // the music button sits in the tray, left of the clock
        float trayW = 16 + 10 + 7 + stW + 16 + noteW + timeW + 16;
        float trayX = w - trayW;
        p = Art.paint();
        p.setShader(Art.vertical(top, h, pal.tray, null));
        c.drawRect(trayX, top, w, h, p);
        p = Art.paint();
        p.setColor(pal.trayEdge);
        c.drawRect(trayX, top, trayX + 1, h, p);
        p.setColor(statusLight(s));
        c.drawCircle(trayX + 16 + 5, top + TASKBAR / 2, 5, p);
        float tb = Art.centre(top + TASKBAR / 2, 14, Art.SANS);
        Art.text(c, st, trayX + 16 + 10 + 7, tb, 14, pal.trayInk, Art.SANS, Paint.Align.LEFT);
        Art.text(c, time, w - 16, tb, 14, pal.trayInk, Art.SANS, Paint.Align.RIGHT);
        if (s.music) {
            float nx = w - 16 - timeW - 10 - 24;
            if (s.musicOpen) {
                p = Art.paint();
                p.setColor(0x33ffffff);
                c.drawRoundRect(Art.rect(nx - 4, top + 5, nx + 28, h - 5), 3, 3, p);
            }
            MusicPainter.note(c, nx + 12, top + TASKBAR / 2, 22, pal.trayInk);
            buttons.put("music", new RectF(nx - 8, top, nx + 32, h));     // before status, so it wins
        }
        buttons.put("status", new RectF(trayX, top, w, h));

        // look switch, just left of the tray
        float by = top + 5, bh = 32;
        float swW = 132, swX = trayX - 10 - swW;
        p = Art.paint();
        p.setShader(Art.vertical(by, by + bh, pal.lookButton, null));
        c.drawRoundRect(Art.rect(swX, by, swX + swW, by + bh), 3, 3, p);
        p = Art.paint();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1);
        p.setColor(pal.lookEdge);
        c.drawRoundRect(Art.rect(swX, by, swX + swW, by + bh), 3, 3, p);
        Art.swapIcon(c, swX + 12, by + 8, 16, pal.taskInk);
        Art.text(c, s.nextLook == null ? "Jeep style" : s.nextLook, swX + 36, Art.centre(by + bh / 2, 14, Art.BOLD), 14, pal.taskInk, Art.BOLD, Paint.Align.LEFT);
        buttons.put("switch", new RectF(swX, top, swX + swW, h));

        // task buttons between the Dash button and the switch
        float x = 134, room = swX - 10 - x;
        if (s.editing) {
            float bw = Math.min(170, (room - 12) / 3);
            button(c, "add", x, by, bw, bh, "+ Add gauge", pal.task, pal.taskEdge, false);
            button(c, "snap", x + bw + 6, by, bw, bh, s.snap ? "Snap to grid: on" : "Snap to grid: off", pal.task, pal.taskEdge, false);
            button(c, "done", x + 2 * (bw + 6), by, bw, bh, "Done", pal.doneFill, pal.doneEdge, true);
        } else {
            int n = s.canEdit ? 4 : 3;
            float bw = Math.min(170, (room - 6 * (n - 1)) / n);
            button(c, "tab:gauges", x, by, bw, bh, "Gauges", "gauges".equals(s.tab) ? pal.taskOn : pal.task, pal.taskEdge, false);
            button(c, "tab:viz", x + bw + 6, by, bw, bh, "Visualizer", "viz".equals(s.tab) ? pal.taskOn : pal.task, pal.taskEdge, false);
            button(c, "logs", x + 2 * (bw + 6), by, bw, bh, "Drive log", pal.task, pal.taskEdge, false);
            if (s.canEdit) button(c, "edit", x + 3 * (bw + 6), by, bw, bh, "Edit gauges", pal.task, pal.taskEdge, false);
        }

        // a balloon above the tray when there is something to tell the driver
        if (!s.detail.isEmpty()) {
            float size = Art.fit(s.detail, 14, w - 60, Art.SANS);
            float tw = Art.width(s.detail, size, Art.SANS);
            float bx2 = w - 10, bx1 = bx2 - tw - 24, bb = top - 8, bt = bb - 34;
            p = Art.paint();
            p.setColor(pal.balloon);
            c.drawRoundRect(Art.rect(bx1, bt, bx2, bb), 6, 6, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1);
            p.setColor(pal.balloonEdge);
            c.drawRoundRect(Art.rect(bx1, bt, bx2, bb), 6, 6, p);
            Art.text(c, s.detail, bx1 + 12, Art.centre((bt + bb) / 2, size, Art.SANS), size, pal.balloonInk, Art.SANS, Paint.Align.LEFT);
            buttons.put("status2", new RectF(bx1, bt, bx2, bb));
        }
    }

    @Override
    public RectF buttonRect(String action) { return buttons.get(action); }

    /** A real window of this look: its title bar, frame, close box and body, light or dark as it is now. */
    @Override
    public MusicPainter.Dress musicDress() {
        MusicPainter.Dress d = new MusicPainter.Dress();
        d.shadow = pal.shadow;
        d.frame = pal.frame;
        d.edge = 0;
        d.title = pal.titleColors;
        d.titleStops = pal.titleStops;
        d.titleInk = pal.titleInk;
        d.close = pal.closeBox;
        d.closeInk = pal.closeInk;
        d.body = pal.body;
        d.ink = pal.ink;
        d.soft = pal.soft;
        d.button = pal.body;
        d.buttonEdge = pal.divider;
        d.buttonInk = pal.ink;
        d.face = Art.SANS;
        d.titleFace = Art.BOLD;
        d.corner = 8;
        return d;
    }

    @Override
    public String hit(float x, float y) {
        for (Map.Entry<String, RectF> e : buttons.entrySet())
            if (e.getValue().contains(x, y)) return e.getKey().equals("status2") ? "status" : e.getKey();
        return null;
    }
}
