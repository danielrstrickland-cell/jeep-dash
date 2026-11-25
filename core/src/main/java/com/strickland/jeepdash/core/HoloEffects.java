package com.strickland.jeepdash.core;

/**
 * The Holo look's effects at each level (the owner's Effects setting: Full, Lite or Off, for the tablet's
 * battery and heat), and what "calm while moving" takes away: flicker and the big animations, while readings
 * keep updating. Also a watch on the frame rate that steps Full down to Lite once if the tablet cannot keep up.
 */
public final class HoloEffects {
    public static final String FULL = "Full", LITE = "Lite", OFF = "Off";
    public static final String[] LEVELS = {FULL, LITE, OFF};

    public final String level;
    public final boolean bloom, fringe, flicker, idleMotion, sweep;
    /** dp between scanlines; 0 for none. */
    public final float scanlineGap;
    /** How long panels take to appear or go; 0 for at once. */
    public final long animateMs;
    public final int maxFps;
    /** Turns a minute of the slow rings round the truck. */
    public final float ringSpeed;

    private HoloEffects(String level, boolean bloom, boolean fringe, boolean flicker, boolean idleMotion, boolean sweep,
                        float scanlineGap, long animateMs, int maxFps, float ringSpeed) {
        this.level = level; this.bloom = bloom; this.fringe = fringe; this.flicker = flicker; this.idleMotion = idleMotion;
        this.sweep = sweep; this.scanlineGap = scanlineGap; this.animateMs = animateMs; this.maxFps = maxFps; this.ringSpeed = ringSpeed;
    }

    /** The saved choice, or Full. */
    public static String pick(String saved) {
        for (String l : LEVELS) if (l.equals(saved)) return l;
        return FULL;
    }

    public static HoloEffects of(String level, boolean calm) {
        String l = pick(level);
        boolean full = FULL.equals(l), off = OFF.equals(l);
        float rings = off ? 0 : calm ? 0.6f : 2f;
        if (off) return new HoloEffects(l, false, false, false, false, false, 0, 0, 30, 0);
        return new HoloEffects(l, full, full, !calm, true, !calm, full ? 3 : 6, calm ? 0 : Materialize.MS, 60, rings);
    }

    /** Calm while moving: on from 5 mph, off again below 3 mph (so it does not flick on and off at 4). */
    public static final class Calm {
        public static final double ON_MPH = 5, OFF_MPH = 3;
        private boolean on;

        public boolean update(double mph, boolean enabled) {
            if (!enabled || Double.isNaN(mph)) on = false;
            else if (mph >= ON_MPH) on = true;
            else if (mph < OFF_MPH) on = false;
            return on;
        }

        public boolean on() { return on; }
    }

    /**
     * Frame times of the Holo look at Full: true once, when two seconds of frames averaged slower than about
     * 50 a second. A gap (screen off, another screen) is not counted as slowness.
     */
    public static final class FrameWatch {
        static final long WINDOW_MS = 2000, GAP_MS = 250;
        static final double SLOW_MS = 20;
        private long windowStart = Long.MIN_VALUE, last;
        private int frames;
        private boolean stepped;

        public boolean frame(long nowMs) {
            if (windowStart == Long.MIN_VALUE || nowMs - last > GAP_MS || nowMs < last) {
                windowStart = nowMs;
                last = nowMs;
                frames = 0;
                return false;
            }
            last = nowMs;
            frames++;
            if (nowMs - windowStart < WINDOW_MS) return false;
            double avg = (nowMs - windowStart) / (double) frames;
            windowStart = nowMs;
            frames = 0;
            if (avg > SLOW_MS && !stepped) {
                stepped = true;
                return true;
            }
            return false;
        }
    }
}
