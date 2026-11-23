package com.strickland.jeepdash.core;

/**
 * Corrects the car's road-speed reading, which is off when the tyres are not the stock size (the Jeep reads
 * about 3.6% under GPS). The owner sets a percent per car, -15 to +15; LiveValues applies it where readings come
 * in, so every gauge and figure made from speed gets the corrected one, while Speedo vs GPS keeps comparing the
 * raw reading. Gps measures the difference from the tablet's GPS, for the "Set from GPS" button.
 */
public final class SpeedCorrection {
    private SpeedCorrection() { }

    public static final double MIN_PCT = -15, MAX_PCT = 15;

    public static double clamp(double pct) {
        if (Double.isNaN(pct)) return 0;
        return Math.max(MIN_PCT, Math.min(MAX_PCT, pct));
    }

    /** What a raw reading is multiplied by. */
    public static double factor(double pct) { return 1 + clamp(pct) / 100; }

    /** True when a measured difference can be used as the correction (inside the range). */
    public static boolean usable(Double pct) { return pct != null && pct >= MIN_PCT && pct <= MAX_PCT; }

    /**
     * The gear indicator's learned rpm per mph, for a new correction: corrected mph grows with the factor, so
     * the same engine speed is fewer rpm per mph. Saves relearning (and wrong gears meanwhile).
     */
    public static double rescaleTop(double rpmPerMph, double oldPct, double newPct) {
        return rpmPerMph * factor(oldPct) / factor(newPct);
    }

    /**
     * Collects GPS speed against the car's raw speed. Only time above 40 mph counts, with fixes no more than
     * 2 s apart and the two speeds within a quarter of each other (anything further is a glitch, not a tyre).
     * A minute of that is enough. Thread-safe: fed by the screen, read by the menu.
     */
    public static final class Gps {
        public static final double FROM_MPH = 40, MAX_APART = 0.25;
        public static final long NEED_MS = 60_000, GAP_MS = 2000;
        private static final long NEVER = Long.MIN_VALUE / 2;

        private double gpsMiles, carMiles;          // speed × time, so a longer stretch weighs more
        private long counted, lastMs = NEVER;

        public synchronized void sample(double gpsMph, double rawCarMph, long nowMs) {
            long dt = nowMs - lastMs;
            lastMs = nowMs;
            if (dt <= 0 || dt > GAP_MS) return;
            if (gpsMph < FROM_MPH || rawCarMph <= 0 || Math.abs(gpsMph / rawCarMph - 1) > MAX_APART) return;
            counted += dt;
            gpsMiles += gpsMph * dt;
            carMiles += rawCarMph * dt;
        }

        public synchronized long countedMs() { return counted; }

        /** Percent GPS reads above the car's raw speed, to a tenth; null before a minute has been counted. */
        public synchronized Double measuredPct() {
            if (counted < NEED_MS || carMiles <= 0) return null;
            return Math.round((gpsMiles / carMiles - 1) * 1000) / 10.0;
        }
    }
}
