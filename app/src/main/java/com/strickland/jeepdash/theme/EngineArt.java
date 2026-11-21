package com.strickland.jeepdash.theme;

import android.graphics.Canvas;
import android.graphics.Paint;

/**
 * A little twin-turbo V8 seen from the front, for the middle of the GT tachometer. The harder it is revved
 * the more it shakes, the faster its turbos spin, and the hotter its exhaust headers glow. A generic engine
 * drawn from lines and circles; not a model of any particular car's.
 */
final class EngineArt {
    private static final int STEEL = 0xff70757f, BLOCK = 0xff33363d, COVER = 0xff565a63, SILVER = 0xffb9bdc6,
            ORANGE = 0xffff7a1a, RED_HOT = 0xffff2d1a;

    private EngineArt() { }

    private static int mix(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int out = 0xff000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            int ca = (a >> shift) & 0xff, cb = (b >> shift) & 0xff;
            out |= Math.round(ca + (cb - ca) * t) << shift;
        }
        return out;
    }

    private static void line(Canvas c, float x0, float y0, float x1, float y1, float width, int color, boolean round) {
        Paint p = Art.paint();
        p.setStyle(Paint.Style.STROKE);
        if (round) p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(width);
        p.setColor(color);
        c.drawLine(x0, y0, x1, y1, p);
    }

    /**
     * @param u       half the width the engine may take
     * @param revs    how hard it is being revved, 0..1
     * @param heat    how hot the headers are, 0..1 (builds and fades slowly; the caller keeps it)
     * @param seconds a running clock, for the shake and the turbos
     */
    static void draw(Canvas c, float cx, float cy, float u, float revs, float heat, float seconds) {
        float shake = u * 0.012f * (0.25f + 1.6f * revs);
        cx += shake * (float) Math.sin(seconds * (38 + 90 * revs));
        cy += shake * (float) Math.cos(seconds * (47 + 110 * revs));

        // the glow of hot pipes on everything around them
        if (heat > 0.25f) {
            Paint g = Art.paint();
            int a = Math.round(120 * (heat - 0.25f) / 0.75f);
            for (int side = -1; side <= 1; side += 2) {
                g.setShader(Art.radial(cx + side * u * 0.62f, cy + u * 0.18f, u * 0.5f, (a << 24) | 0xff3a1a, 0x00ff3a1a));
                c.drawCircle(cx + side * u * 0.62f, cy + u * 0.18f, u * 0.5f, g);
            }
        }

        int pipe = heat < 0.5f ? mix(STEEL, ORANGE, heat * 2) : mix(ORANGE, RED_HOT, heat * 2 - 1);
        for (int side = -1; side <= 1; side += 2) {
            // exhaust headers: three pipes sweeping out of the head and down to the turbo
            for (int k = 0; k < 3; k++) {
                float y0 = cy - u * (0.06f - k * 0.13f), xo = cx + side * u * (0.70f - k * 0.05f);
                line(c, cx + side * u * 0.50f, y0, xo, y0 + u * 0.05f, u * 0.075f, pipe, true);
                line(c, xo, y0 + u * 0.05f, cx + side * u * 0.66f, cy + u * 0.40f, u * 0.075f, pipe, true);
            }
            // the bank of cylinders, leaning out, with its valve cover on top
            line(c, cx + side * u * 0.11f, cy + u * 0.30f, cx + side * u * 0.40f, cy - u * 0.16f, u * 0.27f, BLOCK, false);
            line(c, cx + side * u * 0.36f, cy - u * 0.10f, cx + side * u * 0.47f, cy - u * 0.27f, u * 0.31f, COVER, false);
            line(c, cx + side * u * 0.33f, cy - u * 0.22f, cx + side * u * 0.52f, cy - u * 0.10f, u * 0.03f, SILVER, true);
            // intake runner from the plenum to the head
            line(c, cx + side * u * 0.10f, cy - u * 0.16f, cx + side * u * 0.30f, cy - u * 0.06f, u * 0.07f, SILVER, true);

            // turbo: a housing with a wheel that spins with the revs
            float tx = cx + side * u * 0.68f, ty = cy + u * 0.46f, tr = u * 0.17f;
            Paint p = Art.paint();
            p.setColor(mix(0xff4a4e57, RED_HOT, Math.max(0, heat - 0.6f) * 1.2f));
            c.drawCircle(tx, ty, tr, p);
            p.setColor(0xff14161a);
            c.drawCircle(tx, ty, tr * 0.62f, p);
            double spin = seconds * (2 + 55 * revs) * side;
            for (int b = 0; b < 6; b++) {
                double a = spin + b * Math.PI / 3;
                line(c, tx, ty, tx + (float) Math.cos(a) * tr * 0.55f, ty + (float) Math.sin(a) * tr * 0.55f, u * 0.03f, SILVER, true);
            }
        }

        // crankcase, plenum between the banks, and the crank pulley on the front
        Paint p = Art.paint();
        p.setColor(BLOCK);
        c.drawRoundRect(Art.rect(cx - u * 0.26f, cy + u * 0.16f, cx + u * 0.26f, cy + u * 0.52f), u * 0.06f, u * 0.06f, p);
        p.setColor(SILVER);
        c.drawRoundRect(Art.rect(cx - u * 0.17f, cy - u * 0.30f, cx + u * 0.17f, cy - u * 0.08f), u * 0.06f, u * 0.06f, p);
        p.setColor(0xff7d828c);
        c.drawRoundRect(Art.rect(cx - u * 0.12f, cy - u * 0.26f, cx + u * 0.12f, cy - u * 0.20f), u * 0.02f, u * 0.02f, p);
        p.setColor(0xff1a1c20);
        c.drawCircle(cx, cy + u * 0.36f, u * 0.13f, p);
        Paint ring = Art.paint();
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(u * 0.03f);
        ring.setColor(SILVER);
        c.drawCircle(cx, cy + u * 0.36f, u * 0.13f, ring);
        double turn = seconds * (3 + 40 * revs);
        line(c, cx, cy + u * 0.36f, cx + (float) Math.cos(turn) * u * 0.11f, cy + u * 0.36f + (float) Math.sin(turn) * u * 0.11f, u * 0.03f, SILVER, true);
    }
}
