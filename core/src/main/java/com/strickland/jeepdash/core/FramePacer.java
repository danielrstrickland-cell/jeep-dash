package com.strickland.jeepdash.core;

/**
 * Paces drawing to a frame-rate cap on a screen that refreshes faster (the tablet's runs at 90 Hz and up): at each
 * screen refresh it says whether to draw, keeping an even rhythm (two of every three refreshes at 90 Hz for 60).
 * Times in nanoseconds.
 */
public final class FramePacer {
    private long next;
    private boolean started;

    /** Draw at this refresh? */
    public boolean due(long frameNs, long vsyncNs, int fps) {
        long interval = 1_000_000_000L / Math.max(1, fps);
        if (!started || frameNs - next > interval || next - frameNs > interval) {
            started = true;
            next = frameNs + interval;
            return true;
        }
        if (frameNs >= next - vsyncNs / 2) {
            next += interval;
            return true;
        }
        return false;
    }

    /** Something changed: draw at the next refresh. */
    public void reset() { started = false; }
}
