package com.strickland.jeepdash.theme;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.Locale;

/**
 * Start-up for the GT look: a dial draws itself and sweeps in yellow while the checks on the right fill in
 * with what the car actually reports. Original artwork.
 */
public final class GtBoot implements BootArt {
    private static final int WHITE = 0xfff4f5f7, GREY = 0xff8b9099, DIM = 0xff2a2d33, RED = 0xffe0202b,
            GREEN = 0xff62d58a, AMBER = 0xffe8b24a, YELLOW = 0xffffd21e;
    private static final float SWEEP_S = 1.5f;

    private float shownProgress;

    private static int fade(int color, float alpha) {
        int a = Math.round(((color >>> 24) & 0xff) * Math.max(0, Math.min(1, alpha)));
        return (color & 0x00ffffff) | (a << 24);
    }

    private static void spaced(Canvas c, String s, float x, float base, float size, int color, Paint.Align align) {
        Paint p = Art.paint();
        p.setTypeface(Art.BOLD);
        p.setLetterSpacing(0.16f);
        p.setTextSize(size);
        p.setColor(color);
        p.setTextAlign(align);
        c.drawText(s, x, base, p);
    }

    @Override
    public void draw(Canvas c, float w, float h, float seconds, String[] labels, String[] results, int[] states,
                     String verdict, boolean verdictGood) {
        Paint p = Art.paint();
        p.setColor(0xff000000);
        c.drawRect(0, 0, w, h, p);
        p.setShader(Art.radial(w * 0.29f, h * 0.5f, h * 0.9f, 0xff16181d, 0xff000000));
        c.drawRect(0, 0, w, h, p);

        int done = 0, shownLines = 0;
        for (int st : states) {
            if (st == GOOD || st == BAD) done++;
            if (st != NOT_YET) shownLines++;
        }
        float progress = labels.length == 0 ? 1 : (float) done / labels.length;
        shownProgress += (progress - shownProgress) * 0.12f;
        // one full sweep up and back as the dial draws itself, then the needle shows how far the checks are
        float intro = Math.min(1, seconds / SWEEP_S);
        float f = intro < 1 ? (float) Math.sin(Math.PI * intro) : shownProgress;

        float cx = w * 0.29f, cy = h * 0.5f, R = h * 0.37f, r = R * 0.88f, band = R * 0.035f;
        Paint pt = Art.paint();
        pt.setStyle(Paint.Style.STROKE);
        pt.setStrokeWidth(Math.max(1.5f, R * 0.012f));
        pt.setColor(fade(0xff3a3e46, seconds / 0.4f));
        RectF ring = Art.rect(cx - R * 0.97f, cy - R * 0.97f, cx + R * 0.97f, cy + R * 0.97f);
        c.drawArc(ring, 135, 360 * Math.min(1, seconds / 0.7f), false, pt);
        RectF arc = Art.rect(cx - r, cy - r, cx + r, cy + r);
        pt.setStrokeWidth(band);
        pt.setColor(DIM);
        c.drawArc(arc, 135, 270 * Math.min(1, seconds / 0.7f), false, pt);
        pt.setColor(YELLOW);
        if (f > 0.002f) c.drawArc(arc, 135, 270 * f, false, pt);

        // needle first, so the numbers stay readable where it passes over them
        double na = Math.toRadians(135 + 270.0 * f);
        float ncos = (float) Math.cos(na), nsin = (float) Math.sin(na), tip = r - band, from = R * 0.40f;
        pt = Art.paint();
        pt.setStyle(Paint.Style.STROKE);
        pt.setStrokeCap(Paint.Cap.ROUND);
        pt.setStrokeWidth(Math.max(4, R * 0.06f));
        pt.setColor(fade(0x55e0202b, seconds / 0.5f));
        c.drawLine(cx + from * ncos, cy + from * nsin, cx + tip * ncos, cy + tip * nsin, pt);
        pt.setStrokeWidth(Math.max(2, R * 0.022f));
        pt.setColor(fade(RED, seconds / 0.5f));
        c.drawLine(cx + from * ncos, cy + from * nsin, cx + tip * ncos, cy + tip * nsin, pt);

        for (int i = 0; i <= 14; i++) {
            float appear = (seconds - 0.25f - i * 0.04f) / 0.25f;
            if (appear <= 0) continue;
            boolean major = i % 2 == 0;
            double a = Math.toRadians(135 + 270.0 * i / 14);
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            float r0 = r - band * 1.4f, len = R * (major ? 0.075f : 0.04f);
            boolean red = i >= 13;
            pt = Art.paint();
            pt.setStyle(Paint.Style.STROKE);
            pt.setStrokeWidth(Math.max(1, R * (major ? 0.014f : 0.008f)));
            pt.setColor(fade(red ? RED : major ? WHITE : GREY, appear));
            c.drawLine(cx + r0 * cos, cy + r0 * sin, cx + (r0 - len) * cos, cy + (r0 - len) * sin, pt);
            if (major) {
                float size = R * 0.11f, lr = r0 - len - size * 0.85f;
                Art.text(c, String.valueOf(i / 2), cx + lr * cos, Art.centre(cy + lr * sin, size, Art.BOLD), size,
                        fade(red ? RED : WHITE, appear), Art.BOLD, Paint.Align.CENTER);
            }
        }

        float nameIn = (seconds - 0.5f) / 0.6f;
        Art.text(c, "V8", cx, Art.centre(cy - R * 0.02f, R * 0.30f, Art.BOLD), R * 0.30f, fade(WHITE, nameIn), Art.BOLD, Paint.Align.CENTER);
        spaced(c, "TWIN TURBO", cx, cy + R * 0.30f, R * 0.075f, fade(GREY, nameIn), Paint.Align.CENTER);

        // the checks
        float l = w * 0.56f, rgt = w * 0.94f, rowH = h * 0.092f, size = h * 0.034f;
        float top = h * 0.5f - (labels.length * rowH + h * 0.16f) / 2;
        spaced(c, "SYSTEM CHECK", l, top + size, size * 0.8f, fade(GREY, seconds / 0.5f), Paint.Align.LEFT);
        float y = top + h * 0.06f;
        for (int i = 0; i < labels.length; i++, y += rowH) {
            if (states[i] == NOT_YET) continue;
            Paint line = Art.paint();
            line.setColor(DIM);
            c.drawRect(l, y + rowH - 1.5f, rgt, y + rowH, line);
            float base = y + rowH * 0.66f;
            boolean settled = states[i] != WAITING;
            spaced(c, labels[i].toUpperCase(Locale.US), l, base, size, settled ? WHITE : GREY, Paint.Align.LEFT);
            String res = settled ? results[i] : results[i] + new String[]{"", ".", "..", "..."}[(int) (seconds * 3) % 4];
            int ink = states[i] == GOOD ? GREEN : states[i] == BAD ? RED : AMBER;
            Art.text(c, res, rgt, base, size * 1.05f, ink, Art.BOLD, Paint.Align.RIGHT);
        }
        if (verdict != null) {
            float base = top + h * 0.06f + labels.length * rowH + h * 0.085f;
            spaced(c, verdict, l, base, size * 1.15f, verdictGood ? WHITE : AMBER, Paint.Align.LEFT);
        }
    }
}
