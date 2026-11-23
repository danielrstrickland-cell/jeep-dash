package com.strickland.jeepdash.core;

/** Where the music window sits and what a tap on it means; the same for every look. */
public class MusicLayoutTest {

    public static void opensAboveAButtonAtTheBottomAndStaysOnScreen() {
        MusicLayout m = MusicLayout.place(1280, 800, 1100, 760, 1140, 800);
        T.near(752, m.window.b, 0.01, "just above the button");
        T.near(MusicLayout.H, m.window.b - m.window.t, 0.01, "full height");
        T.near(1280 - 8, m.window.r, 0.01, "pushed in from the right edge");
        T.near(MusicLayout.W, m.window.r - m.window.l, 0.01, "full width");
    }

    public static void opensBelowAButtonAtTheTop() {
        MusicLayout m = MusicLayout.place(1280, 800, 20, 20, 80, 60);
        T.near(68, m.window.t, 0.01, "just below the button");
        T.near(8, m.window.l, 0.01, "pushed in from the left edge");
    }

    public static void aNarrowScreenGetsANarrowerWindow() {
        MusicLayout m = MusicLayout.place(400, 300, 180, 250, 220, 290);
        T.near(384, m.window.r - m.window.l, 0.01, "8 each side");
        T.ok(m.window.t >= 8, "never off the top: " + m.window.t);
    }

    public static void tapsAreTheCloseBoxThePlayButtonsOrJustTheWindow() {
        MusicLayout m = MusicLayout.place(1280, 800, 1100, 760, 1140, 800);
        T.eq("close", m.hit(m.close.cx(), m.close.cy()), "close box");
        T.eq("close", m.hit(m.close.l - 6, m.close.cy()), "a finger's width around it counts");
        T.eq("prev", m.hit(m.prev.cx(), m.prev.cy()), "previous");
        T.eq("play", m.hit(m.play.cx(), m.play.cy()), "play / pause");
        T.eq("next", m.hit(m.next.cx(), m.next.cy()), "next");
        T.eq("window", m.hit(m.window.l + 20, m.line1Y), "the text: swallowed, nothing happens");
        T.eq(null, m.hit(m.window.l - 20, m.window.t + 20), "outside: not the window's, goes to the dash");
    }

    public static void theButtonsSitInsideTheWindowInARow() {
        MusicLayout m = MusicLayout.place(1280, 800, 600, 760, 680, 800);
        T.ok(m.prev.r < m.play.l && m.play.r < m.next.l, "in order, apart");
        T.ok(m.prev.l > m.window.l && m.next.r < m.window.r && m.play.b < m.window.b, "inside");
        T.ok(m.play.r - m.play.l >= 56 && m.play.b - m.play.t >= 48, "big targets");
        T.ok(m.line1Y < m.line2Y && m.line2Y < m.play.t, "two lines of text above the buttons");
    }
}
