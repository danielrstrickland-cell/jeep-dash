package com.strickland.jeepdash.theme;

import android.graphics.Canvas;
import android.graphics.Paint;

import java.util.Locale;

/**
 * Start-up for the 1980s digital look: every bar and segment lights at once, the way those dashes tested
 * their lamps, then the band falls back and climbs again as each check comes in. Original artwork.
 */
public final class RetroBoot implements BootArt {
    private static final float LAMP_TEST_S = 0.9f, FALL_S = 0.5f;
    private float shownProgress;

    @Override
    public void draw(Canvas c, float w, float h, float seconds, String[] labels, String[] results, int[] states,
                     String verdict, boolean verdictGood) {
        Paint p = Art.paint();
        p.setColor(0xff000000);
        c.drawRect(0, 0, w, h, p);
        p.setShader(Art.radial(w / 2, h * 0.45f, Math.max(w, h) * 0.6f, 0xff06211c, 0xff000000));
        c.drawRect(0, 0, w, h, p);

        int done = 0;
        for (int st : states) if (st == GOOD || st == BAD) done++;
        float progress = labels.length == 0 ? 1 : (float) done / labels.length;
        shownProgress += (progress - shownProgress) * 0.12f;
        boolean lampTest = seconds < LAMP_TEST_S;
        float fall = Math.min(1, Math.max(0, (seconds - LAMP_TEST_S) / FALL_S));
        float f = lampTest ? 1 : Math.max(shownProgress, 1 - fall);

        // the band across the top, as on the dash
        float l = w * 0.06f, r = w * 0.94f, bottom = h * 0.36f, H = h * 0.30f;
        int n = 48;
        float slot = (r - l) / n, thick = slot * 0.52f;
        for (int i = 0; i < n; i++) {
            float t = (i + 0.5f) / n;
            float base = bottom - H * 0.42f * (float) Math.pow(t, 1.7), tall = H * (0.16f + 0.40f * t);
            float x = l + (i + 0.5f) * slot;
            int color = t >= 0.92f ? RetroTheme.RED : RetroTheme.MAIN;
            if ((i + 1f) / n <= f + 0.5f / n) RetroTheme.lit(c, x, base, x, base - tall, thick, color);
            else RetroTheme.unlit(c, x, base, x, base - tall, thick, color);
        }
        float size = h * 0.034f;
        RetroTheme.label(c, "SYSTEM CHECK", l, h * 0.075f, size * 1.15f, RetroTheme.AMBER, w * 0.4f, Paint.Align.LEFT);
        String figure = lampTest ? "888" : String.valueOf(Math.round(shownProgress * 100));
        float dh = h * 0.085f;
        RetroTheme.numerals(c, figure, l + w * 0.36f, h * 0.035f, dh, RetroTheme.MAIN);
        RetroTheme.label(c, "%", l + w * 0.36f + size * 0.5f, h * 0.035f + dh, size, RetroTheme.dim(RetroTheme.AMBER, 0xc0), 40, Paint.Align.LEFT);

        // the checks
        float left = w * 0.14f, right = w * 0.86f, rowH = h * 0.078f, y = h * 0.44f;
        for (int i = 0; i < labels.length; i++, y += rowH) {
            if (states[i] == NOT_YET && !lampTest) continue;
            Paint line = Art.paint();
            line.setColor(RetroTheme.FRAME);
            c.drawRect(left, y + rowH - 1.5f, right, y + rowH, line);
            float base = y + rowH * 0.66f;
            boolean settled = states[i] == GOOD || states[i] == BAD;
            RetroTheme.label(c, labels[i].toUpperCase(Locale.US), left, base, size,
                    settled || lampTest ? RetroTheme.AMBER : RetroTheme.dim(RetroTheme.AMBER, 0xa0), w * 0.4f, Paint.Align.LEFT);
            if (lampTest || states[i] == NOT_YET) continue;
            String dots = new String[]{"", ".", "..", "..."}[(int) (seconds * 3) % 4];
            String res = settled ? results[i] : results[i] + dots;
            RetroTheme.label(c, res, right, base, size * 1.05f, states[i] == BAD ? RetroTheme.RED : RetroTheme.MAIN,
                    w * 0.4f, Paint.Align.RIGHT);
        }
        if (verdict != null)
            RetroTheme.label(c, verdict, w / 2, h * 0.44f + labels.length * rowH + h * 0.085f, size * 1.25f,
                    verdictGood ? RetroTheme.MAIN : RetroTheme.AMBER, w * 0.8f, Paint.Align.CENTER);
    }
}
