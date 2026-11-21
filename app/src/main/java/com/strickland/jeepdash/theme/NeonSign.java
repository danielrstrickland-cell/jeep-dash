package com.strickland.jeepdash.theme;

import android.graphics.Canvas;
import android.graphics.Paint;

/**
 * The Cayenne's opening joke: a neon sign that stutters on, glows for a moment, and makes way for the real
 * start-up checks. Own lettering in plain type; no restaurant's logo is copied.
 */
public final class NeonSign {
    /** How long the sign is on screen before the checks take over. */
    public static final float SECONDS = 2.6f;
    private static final int TUBE = 0xffff3d5a, WARM = 0xffffb13b, COOL = 0xff3df2d0;

    private NeonSign() { }

    /** 0 = dark, 1 = fully lit: a few false starts, then steady, then out. */
    private static float power(float t) {
        if (t < 0.25f) return 0;
        if (t < 0.32f) return 0.9f;
        if (t < 0.48f) return 0.05f;
        if (t < 0.56f) return 1;
        if (t < 0.66f) return 0.15f;
        if (t > SECONDS - 0.35f) return Math.max(0, (SECONDS - t) / 0.35f);
        return 1;
    }

    private static void glow(Canvas c, String s, float x, float base, float size, int color, float power, float spacing) {
        if (power <= 0.01f) return;
        Paint p = Art.paint();
        p.setTypeface(Art.ITALIC_BOLD);
        p.setLetterSpacing(spacing);
        p.setTextSize(size);
        p.setTextAlign(Paint.Align.CENTER);
        int a = Math.round(255 * power);
        p.setColor((color & 0x00ffffff) | (a << 24));
        p.setShadowLayer(size * 0.35f, 0, 0, (color & 0x00ffffff) | (Math.round(200 * power) << 24));
        c.drawText(s, x, base, p);
        // the white-hot middle of the tube
        p = Art.paint();
        p.setTypeface(Art.ITALIC_BOLD);
        p.setLetterSpacing(spacing);
        p.setTextSize(size);
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor((Math.round(150 * power) << 24) | 0xffffff);
        c.drawText(s, x, base, p);
    }

    public static void draw(Canvas c, float w, float h, float t) {
        Paint p = Art.paint();
        p.setColor(0xff000000);
        c.drawRect(0, 0, w, h, p);
        float on = power(t), cx = w / 2;
        if (on > 0.01f) {
            p.setShader(Art.radial(cx, h * 0.46f, h * 0.9f, (Math.round(60 * on) << 24) | 0x7a1030, 0x00000000));
            c.drawRect(0, 0, w, h, p);
        }
        // the frame of the sign: one tube all the way round
        Paint tube = Art.paint();
        tube.setStyle(Paint.Style.STROKE);
        float l = w * 0.14f, r = w * 0.86f, top = h * 0.22f, bottom = h * 0.74f, round = h * 0.09f;
        tube.setStrokeWidth(h * 0.022f);
        tube.setColor((Math.round(70 * on) << 24) | (COOL & 0xffffff));
        c.drawRoundRect(Art.rect(l, top, r, bottom), round, round, tube);
        tube.setStrokeWidth(h * 0.008f);
        tube.setColor((Math.round(255 * on) << 24) | (COOL & 0xffffff));
        c.drawRoundRect(Art.rect(l, top, r, bottom), round, round, tube);

        float size = Math.min(h * 0.17f, w * 0.085f);
        // one letter never quite behaves
        float lazy = t > 1.3f && t < 1.42f || t > 1.9f && t < 1.96f ? 0.25f : 1;
        glow(c, "GAPPLEBEES", cx, h * 0.50f, size, TUBE, on * lazy, 0.06f);
        glow(c, "TWIN TURBO BAR  +  GRILL", cx, h * 0.62f, size * 0.26f, WARM, t > 0.75f ? on : 0, 0.2f);
        glow(c, "OPEN LATE", cx, h * 0.86f, size * 0.22f, COOL, t > 1.0f ? on * 0.9f : 0, 0.3f);
    }
}
