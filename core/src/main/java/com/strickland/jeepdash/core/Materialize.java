package com.strickland.jeepdash.core;

/**
 * A hologram panel or menu appearing: it stretches open from a thin line, then a scanline wipes its contents in
 * from the top (about 250 ms in all). Closing runs the same backwards, and the panel is gone at the end.
 * A duration of 0 (effects off, or calm while moving) means no show at all. Times in milliseconds.
 */
public final class Materialize {
    public static final long MS = 250;
    /** The thin line it starts from, as a share of the full height. */
    public static final double LINE = 0.02;
    static final double STRETCH = 0.55;

    private final long start, ms;
    private final boolean opening;

    private Materialize(long start, long ms, boolean opening) { this.start = start; this.ms = ms; this.opening = opening; }

    public static Materialize opening(long nowMs, long ms) { return new Materialize(nowMs, ms, true); }

    public static Materialize closing(long nowMs, long ms) { return new Materialize(nowMs, ms, false); }

    public boolean isOpening() { return opening; }

    /** How open, 0..1. */
    private double p(long nowMs) {
        double f = ms <= 0 ? 1 : Math.max(0, Math.min(1, (nowMs - start) / (double) ms));
        return opening ? f : 1 - f;
    }

    /** Height as a share of the full height. */
    public double height(long nowMs) {
        double s = Math.min(1, p(nowMs) / STRETCH), e = 1 - (1 - s) * (1 - s) * (1 - s);
        return LINE + (1 - LINE) * e;
    }

    /** How much of the contents the scanline has uncovered, from the top, 0..1. */
    public double reveal(long nowMs) {
        double p = p(nowMs);
        if (p <= STRETCH) return 0;
        double s = (p - STRETCH) / (1 - STRETCH);
        return s * s * (3 - 2 * s);
    }

    public boolean done(long nowMs) { return ms <= 0 || nowMs - start >= ms; }

    /** A closing panel that has finished: stop drawing it. */
    public boolean gone(long nowMs) { return !opening && done(nowMs); }

    /** Menus unfold in layers: each row a little after the one above, the whole list within 300 ms. */
    public static long rowDelay(int row, boolean animate) { return animate ? Math.min(300, row * 35L) : 0; }
}
