package com.strickland.jeepdash.core;

/**
 * Where the music window sits and what a tap on it means: one layout for every look, which only dresses it.
 * Sizes are in dp. It opens next to the music button (above it when the button is in the lower half of the
 * screen, below it otherwise) and always fits on the screen. A tap outside the window is not the window's, so
 * it can be left open while driving.
 */
public final class MusicLayout {
    public static final float W = 440, H = 176, TITLE = 30, CLOSE = 22, BTN_W = 84, BTN_H = 52;
    static final float EDGE = 8, GAP = 8, BORDER = 3, PAD = 12, BTN_GAP = 16, CLOSE_REACH = 11;

    /** A rectangle, left/top/right/bottom. */
    public static final class Box {
        public final float l, t, r, b;
        Box(float l, float t, float r, float b) { this.l = l; this.t = t; this.r = r; this.b = b; }
        public boolean contains(float x, float y) { return x >= l && x <= r && y >= t && y <= b; }
        public float cx() { return (l + r) / 2; }
        public float cy() { return (t + b) / 2; }
    }

    public final Box window, title, close, prev, play, next;
    /** Where the two lines of text go: their left and right ends and the middle of each line. */
    public final float textL, textR, line1Y, line2Y;

    private MusicLayout(float x, float y, float w) {
        window = new Box(x, y, x + w, y + H);
        title = new Box(x + BORDER, y + BORDER, x + w - BORDER, y + BORDER + TITLE);
        float cl = x + w - BORDER - 5 - CLOSE, ct = y + BORDER + (TITLE - CLOSE) / 2;
        close = new Box(cl, ct, cl + CLOSE, ct + CLOSE);
        float row = 3 * BTN_W + 2 * BTN_GAP, bl = x + (w - row) / 2, bt = y + H - PAD - BTN_H;
        prev = new Box(bl, bt, bl + BTN_W, bt + BTN_H);
        play = new Box(prev.r + BTN_GAP, bt, prev.r + BTN_GAP + BTN_W, bt + BTN_H);
        next = new Box(play.r + BTN_GAP, bt, play.r + BTN_GAP + BTN_W, bt + BTN_H);
        textL = x + BORDER + PAD;
        textR = x + w - BORDER - PAD;
        float body = title.b, room = bt - body;
        line1Y = body + room * 0.36f;
        line2Y = body + room * 0.76f;
    }

    /** The window for a music button at l/t/r/b on a screen of w × h. */
    public static MusicLayout place(float screenW, float screenH, float l, float t, float r, float b) {
        float w = Math.min(W, screenW - 2 * EDGE);
        float x = clamp((l + r) / 2 - w / 2, EDGE, screenW - EDGE - w);
        float y = (t + b) / 2 > screenH / 2 ? t - GAP - H : b + GAP;
        y = clamp(y, EDGE, screenH - EDGE - H);
        return new MusicLayout(x, y, w);
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    /** close, prev, play, next; window for anywhere else on it (the tap goes no further); null outside. */
    public String hit(float x, float y) {
        if (x >= close.l - CLOSE_REACH && x <= close.r + CLOSE_REACH && y >= close.t - CLOSE_REACH && y <= close.b + CLOSE_REACH)
            return "close";
        if (prev.contains(x, y)) return "prev";
        if (play.contains(x, y)) return "play";
        if (next.contains(x, y)) return "next";
        return window.contains(x, y) ? "window" : null;
    }
}
