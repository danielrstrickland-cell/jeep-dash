package com.strickland.jeepdash.theme;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import com.strickland.jeepdash.core.MusicLayout;

/**
 * Draws the music window: one layout for every look (core MusicLayout), dressed in the look's own colours and
 * lettering (Dress, from each look's palette). Sizes are in dp; the caller scales the canvas. UI thread only.
 */
public final class MusicPainter {
    private MusicPainter() { }

    /** A look's colours and typeface for the window. */
    public static final class Dress {
        public int shadow = 0x66000000, frame, edge;
        public int[] title;
        public float[] titleStops;
        public int titleInk;
        public int[] close;
        public int closeInk;
        public int body, ink, soft;
        public int button, buttonEdge, buttonInk;
        public int glow;                           // 0: none; otherwise text and icons glow in this colour
        public Typeface face = Typeface.DEFAULT, titleFace = Typeface.DEFAULT_BOLD;
        public float corner = 10, spacing;
    }

    /** The plain dark window, for the visualizer and any look without its own. */
    public static Dress plain() {
        Dress d = new Dress();
        d.frame = 0xF0181C21;
        d.edge = 0x40FFFFFF;
        d.title = new int[]{0xFF262B32, 0xFF181C21};
        d.titleInk = 0xFFE8ECEF;
        d.close = new int[]{0xFF3A3F47, 0xFF2A2E35};
        d.closeInk = 0xFFE8ECEF;
        d.body = 0xF0101418;
        d.ink = 0xFFE8ECEF;
        d.soft = 0xFF9AA3AD;
        d.button = 0xFF242931;
        d.buttonEdge = 0x40FFFFFF;
        d.buttonInk = 0xFFFFFFFF;
        return d;
    }

    private static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG), LINE = new Paint(Paint.ANTI_ALIAS_FLAG),
            TEXT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF R = new RectF();
    private static final Path PATH = new Path();

    public static void draw(Canvas c, MusicLayout m, Theme.Chrome s, Dress d) {
        MusicLayout.Box w = m.window;
        fill(d.shadow, null);
        c.drawRoundRect(rect(w.l + 2, w.t + 3, w.r + 2, w.b + 3), d.corner, d.corner, FILL);
        fill(d.frame, null);
        c.drawRoundRect(rect(w.l, w.t, w.r, w.b), d.corner, d.corner, FILL);
        fill(d.body, null);
        c.drawRect(w.l + 3, m.title.b, w.r - 3, w.b - 3, FILL);
        MusicLayout.Box t = m.title;
        fill(d.title[0], gradient(t.t, t.b, d.title, d.titleStops));
        c.drawRect(t.l, t.t, t.r, t.b, FILL);
        if (d.edge != 0) {
            line(d.edge, 1);
            c.drawRoundRect(rect(w.l, w.t, w.r, w.b), d.corner, d.corner, LINE);
        }
        text(c, "Now playing", t.l + 10, t.cy(), 14, d.titleInk, d.titleFace, d, m.close.l - t.l - 20);

        MusicLayout.Box x = m.close;
        fill(d.close[0], gradient(x.t, x.b, d.close, null));
        c.drawRoundRect(rect(x.l, x.t, x.r, x.b), 3, 3, FILL);
        line(d.closeInk, 1);
        c.drawRoundRect(rect(x.l, x.t, x.r, x.b), 3, 3, LINE);
        line(d.closeInk, 2.2f);
        LINE.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(x.l + 7, x.t + 7, x.r - 7, x.b - 7, LINE);
        c.drawLine(x.r - 7, x.t + 7, x.l + 7, x.b - 7, LINE);

        float room = m.textR - m.textL;
        text(c, s.musicTitle, m.textL, m.line1Y, 20, d.ink, d.face, d, room);
        text(c, s.musicArtist, m.textL, m.line2Y, 16, d.soft, d.face, d, room);

        button(c, m.prev, d, -1, false);
        button(c, m.play, d, 0, s.musicPlaying);
        button(c, m.next, d, 1, false);
    }

    private static void button(Canvas c, MusicLayout.Box b, Dress d, int kind, boolean playing) {
        fill(d.button, null);
        c.drawRoundRect(rect(b.l, b.t, b.r, b.b), 8, 8, FILL);
        line(d.buttonEdge, 1.5f);
        c.drawRoundRect(rect(b.l, b.t, b.r, b.b), 8, 8, LINE);
        fill(d.buttonInk, null);
        glow(FILL, d);
        float cx = b.cx(), cy = b.cy(), s = 11;
        if (kind == 0 && playing) {
            c.drawRect(cx - 9, cy - s, cx - 3, cy + s, FILL);
            c.drawRect(cx + 3, cy - s, cx + 9, cy + s, FILL);
        } else if (kind == 0) {
            triangle(c, cx - 7, cy, s * 1.6f, true);
        } else {
            float dir = kind;                          // -1 previous, 1 next
            triangle(c, cx - dir * 9, cy, s * 1.3f, dir > 0);
            triangle(c, cx + dir * 3, cy, s * 1.3f, dir > 0);
            float bar = cx + dir * 14;
            c.drawRect(Math.min(bar, bar + dir * 3), cy - s, Math.max(bar, bar + dir * 3), cy + s, FILL);
        }
        FILL.clearShadowLayer();
    }

    /** A triangle pointing right (or left) from its back edge at x. */
    private static void triangle(Canvas c, float x, float cy, float len, boolean right) {
        float h = len * 0.6f, tip = right ? x + len * 0.85f : x - len * 0.85f;
        PATH.reset();
        PATH.moveTo(x, cy - h);
        PATH.lineTo(tip, cy);
        PATH.lineTo(x, cy + h);
        PATH.close();
        c.drawPath(PATH, FILL);
    }

    /** Two beamed notes, the music button's mark, in a box size × size centred on cx, cy. */
    public static void note(Canvas c, float cx, float cy, float size, int color) {
        fill(color, null);
        float r = size * 0.15f, l = cx - size * 0.3f, rt = cx + size * 0.3f, top = cy - size * 0.38f, bottom = cy + size * 0.28f;
        c.save();
        c.rotate(-20, l - r * 0.2f, bottom);
        c.drawOval(rect(l - r * 1.3f - r * 0.2f, bottom - r, l + r * 0.7f - r * 0.2f, bottom + r), FILL);
        c.restore();
        c.save();
        c.rotate(-20, rt - r * 0.2f, bottom - size * 0.08f);
        c.drawOval(rect(rt - r * 1.5f, bottom - size * 0.08f - r, rt + r * 0.5f, bottom - size * 0.08f + r), FILL);
        c.restore();
        float stem = Math.max(1.5f, size * 0.08f);
        c.drawRect(l + r * 0.3f, top, l + r * 0.3f + stem, bottom, FILL);
        c.drawRect(rt + r * 0.3f, top - size * 0.06f, rt + r * 0.3f + stem, bottom - size * 0.08f, FILL);
        PATH.reset();
        PATH.moveTo(l + r * 0.3f, top);
        PATH.lineTo(rt + r * 0.3f + stem, top - size * 0.06f);
        PATH.lineTo(rt + r * 0.3f + stem, top + size * 0.12f);
        PATH.lineTo(l + r * 0.3f, top + size * 0.18f);
        PATH.close();
        c.drawPath(PATH, FILL);
    }

    private static void text(Canvas c, String s, float x, float cy, float size, int color, Typeface tf, Dress d, float maxW) {
        if (s == null || s.isEmpty()) return;
        TEXT.reset();
        TEXT.setAntiAlias(true);
        TEXT.setTypeface(tf);
        TEXT.setLetterSpacing(d.spacing);
        TEXT.setTextSize(size);
        float w = TEXT.measureText(s);
        if (w > maxW && w > 0) TEXT.setTextSize(Math.max(size * 0.7f, size * maxW / w));
        TEXT.setColor(color);
        glow(TEXT, d);
        Paint.FontMetrics fm = TEXT.getFontMetrics();
        String shown = s;
        while (TEXT.measureText(shown) > maxW && shown.length() > 1) shown = shown.substring(0, shown.length() - 2) + "…";
        c.drawText(shown, x, cy - (fm.ascent + fm.descent) / 2, TEXT);
    }

    private static void glow(Paint p, Dress d) {
        if (d.glow != 0) p.setShadowLayer(6, 0, 0, (d.glow & 0x00ffffff) | 0x8c000000);
        else p.clearShadowLayer();
    }

    private static void fill(int color, Shader shader) {
        FILL.reset();
        FILL.setAntiAlias(true);
        FILL.setStyle(Paint.Style.FILL);
        FILL.setColor(color);
        FILL.setShader(shader);
    }

    private static void line(int color, float width) {
        LINE.reset();
        LINE.setAntiAlias(true);
        LINE.setStyle(Paint.Style.STROKE);
        LINE.setStrokeWidth(width);
        LINE.setColor(color);
    }

    private static Shader gradient(float y0, float y1, int[] colors, float[] stops) {
        return colors.length < 2 ? null : Art.vertical(y0, y1, colors, stops);
    }

    private static RectF rect(float l, float t, float r, float b) {
        R.set(l, t, r, b);
        return R;
    }
}
