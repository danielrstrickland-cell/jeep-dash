package com.strickland.jeepdash;

import android.graphics.Canvas;
import android.graphics.Paint;

import com.strickland.jeepdash.core.VizTune;

/**
 * '90s head-unit dolphins: pixel dolphins swimming and leaping over a rolling wave on a glowing segmented
 * display, with a dot-matrix spectrum underneath. They swim faster when the music is loud and leap on bass
 * hits, higher the harder the hit. Green like an old display, or in the colour profile. Plain drawing, so it
 * runs anywhere.
 */
final class DolphinLook implements VizPainter.Extra {
    /** An original dolphin, nose to the right: # body, o eye. */
    private static final String[] SPRITE = {
            "......##........",
            ".....###........",
            "..##########....",
            ".############...",
            "#############o##",
            ".############...",
            "##..#######.....",
            "#.....##........",
    };
    private static final int DOLPHINS = 2, COLS = 24, ROWS = 12;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] x = {0.2f, 0.65f}, jump = new float[DOLPHINS], high = new float[DOLPHINS];
    private float wave, lastBeat;
    private int next;

    @Override
    public void draw(Canvas c, int w, int h, float dt, float seconds, VizPainter p) {
        int look = VizTune.DOLPHINS;
        int on = p.tune.on(look, "profile") ? p.color(1) : 0xFF39FF8A;
        int dim = (on & 0x00FFFFFF) | 0x1F000000, mid = (on & 0x00FFFFFF) | 0x66000000;
        float u = h / 800f, px = Math.max(4, Math.round(9 * u));       // one display pixel
        float left = w * 0.05f, right = w * 0.95f, top = h * 0.14f, bottom = h * 0.9f;

        c.drawColor(0xFF020A06);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF041A0E);
        c.drawRoundRect(left - px, top - px, right + px, bottom + px, px, px, paint);
        if (p.tune.on(look, "grid")) {                                  // the unlit LCD cells
            paint.setColor(dim);
            for (float y = top; y < bottom; y += px * 1.5f)
                for (float gx = left; gx < right; gx += px * 1.5f) c.drawRect(gx, y, gx + px * 0.8f, y + px * 0.8f, paint);
        }

        // the sea: a rolling wave of lit cells
        float water = top + (bottom - top) * 0.6f;
        wave += dt * (1 + 2 * p.vu);
        paint.setColor(mid);
        for (float gx = left; gx < right; gx += px * 1.5f) {
            float y = water + (float) Math.sin(gx / w * 18 + wave) * px * 1.2f;
            c.drawRect(gx, y, gx + px * 1.2f, y + px * 1.2f, paint);
        }

        // dolphins: swim right and wrap round; a bass hit sends the next one leaping
        boolean hit = p.beat > 0.9f && lastBeat <= 0.9f;
        lastBeat = p.beat;
        if (hit) {
            for (int k = 0; k < DOLPHINS; k++) {
                int i = (next + k) % DOLPHINS;
                if (jump[i] <= 0) {
                    jump[i] = 0.0001f;
                    high[i] = (0.5f + p.bass) * p.tune.get(look, "jump");
                    next = (i + 1) % DOLPHINS;
                    break;
                }
            }
        }
        float span = right - left;
        for (int i = 0; i < DOLPHINS; i++) {
            x[i] += dt * (0.05f + 0.12f * p.vu);
            if (x[i] > 1.1f) x[i] -= 1.2f;
            float lift = 0, tilt = 0;
            if (jump[i] > 0) {
                jump[i] += dt / 1.1f;
                if (jump[i] >= 1) jump[i] = 0;
                else {
                    lift = (float) Math.sin(Math.PI * jump[i]) * high[i] * (bottom - top) * 0.45f;
                    tilt = (float) Math.cos(Math.PI * jump[i]) * -35;           // nose up, then nose down
                }
            }
            float dx = left + x[i] * span, dy = water - px * 4 - lift;
            boolean under = lift < px * 3;
            paint.setColor(under ? mid : on);
            int saved = c.save();
            c.rotate(tilt, dx + px * 8, dy + px * 4);
            for (int r = 0; r < SPRITE.length; r++) {
                String row = SPRITE[r];
                for (int k = 0; k < row.length(); k++) {
                    char ch = row.charAt(k);
                    if (ch == '.') continue;
                    if (ch == 'o') paint.setColor(0xFF041A0E);
                    c.drawRect(dx + k * px, dy + r * px, dx + (k + 0.85f) * px, dy + (r + 0.85f) * px, paint);
                    if (ch == 'o') paint.setColor(under ? mid : on);
                }
            }
            c.restoreToCount(saved);
        }

        // a dot-matrix spectrum along the bottom
        float colW = (right - left) / COLS, rowH = (bottom - water - px * 6) / ROWS, base = bottom - px;
        paint.setColor(on);
        for (int col = 0; col < COLS; col++) {
            int lit = Math.round(p.bar(col * p.shown / COLS) * ROWS);
            float cx = left + (col + 0.5f) * colW;
            for (int r = 0; r < lit; r++) c.drawCircle(cx, base - r * rowH, px * 0.4f, paint);
        }
        paint.setTextSize(22 * u);
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setColor(on);
        c.drawText(p.bass > 0.6f ? "BASS" : "", right, top + 26 * u, paint);
    }
}
