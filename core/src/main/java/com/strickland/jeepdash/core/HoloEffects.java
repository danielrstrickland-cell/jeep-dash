package com.strickland.jeepdash.core;

/**
 * The hologram effects in force: from the Quality mode, the owner's "Hologram effects" switch (off for a clean
 * look) and "Calm while moving", which takes away flicker and the big animations while readings keep updating.
 */
public final class HoloEffects {
    public final boolean bloom, multiBloom, fringe, flicker, sweep, idleMotion;
    /** dp between scanlines; 0 for none. */
    public final float scanlineGap;
    /** How long panels take to appear or go; 0 for at once. */
    public final long animateMs;
    /** Turns a minute of the slow rings round the truck. */
    public final float ringSpeed;

    private HoloEffects(boolean bloom, boolean multiBloom, boolean fringe, boolean flicker, boolean sweep,
                        float scanlineGap, long animateMs, float ringSpeed) {
        this.bloom = bloom; this.multiBloom = multiBloom; this.fringe = fringe; this.flicker = flicker; this.sweep = sweep;
        this.scanlineGap = scanlineGap; this.animateMs = animateMs; this.ringSpeed = ringSpeed;
        this.idleMotion = true;
    }

    public static HoloEffects of(QualityMode q, boolean effectsOn, boolean calm) {
        float rings = calm ? 0.6f : 2f;
        if (!effectsOn) return new HoloEffects(false, false, false, false, false, 0, 0, rings);
        return new HoloEffects(q.bloom, q.bloom && q.multiPassBloom, q.fringe, !calm, !calm, q.scanlineGap,
                calm ? 0 : Materialize.MS, rings);
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
}
