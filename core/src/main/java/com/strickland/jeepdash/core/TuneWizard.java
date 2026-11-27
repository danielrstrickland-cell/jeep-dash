package com.strickland.jeepdash.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The sums behind the stereo tuning wizard: speaker delays from measured arrival times, a crossover choice from
 * the stereo's own list, the sub phase that measures louder, and the list of changes to show before anything is
 * written. Pure: measuring and writing belong to the app. Nothing here talks to the stereo.
 */
public final class TuneWizard {
    /** Feet the sound travels in one millisecond (1125 ft/s). */
    public static final double FEET_PER_MS = 1.125;
    /** Speakers in the stereo's order: front left, front right, rear left, rear right, sub. */
    public static final int SPEAKERS = 5;

    private TuneWizard() { }

    /**
     * How much nearer each speaker is than the farthest, in feet, as the manual's method asks for: measure to each
     * speaker and enter its difference from the farthest. Speakers with no measurement (NaN) get 0. Each result is
     * kept within the stereo's range, 0 to 20.01 ft, and rounded to 0.01 ft.
     */
    public static double[] distances(double[] arrivalMs) {
        double[] out = new double[SPEAKERS];
        double far = Double.NaN;
        for (int i = 0; i < SPEAKERS && i < arrivalMs.length; i++)
            if (!Double.isNaN(arrivalMs[i]) && (Double.isNaN(far) || arrivalMs[i] > far)) far = arrivalMs[i];
        if (Double.isNaN(far)) return out;
        for (int i = 0; i < SPEAKERS && i < arrivalMs.length; i++) {
            if (Double.isNaN(arrivalMs[i])) continue;
            double d = (far - arrivalMs[i]) * FEET_PER_MS;
            d = Math.min(KenwoodManual.DTA_MAX_FEET, Math.max(0, d));
            out[i] = Math.round(d * 100.0) / 100.0;
        }
        return out;
    }

    /** The stereo's crossover choice closest to the wanted frequency (compared on a log scale), in Hz. */
    public static int nearestCrossover(double wantedHz) {
        int best = KenwoodManual.XOVER_HZ[0];
        double bestGap = Double.MAX_VALUE;
        for (int hz : KenwoodManual.XOVER_HZ) {
            double gap = Math.abs(Math.log(hz / Math.max(1.0, wantedHz)));
            if (gap < bestGap) { bestGap = gap; best = hz; }
        }
        return best;
    }

    /** The stereo's slope choice closest to the wanted one, in dB per octave (-6, -12, -18 or -24). */
    public static int nearestSlope(int wantedDb) {
        int best = KenwoodManual.SLOPES_DB[0];
        for (int s : KenwoodManual.SLOPES_DB) if (Math.abs(s - wantedDb) < Math.abs(best - wantedDb)) best = s;
        return best;
    }

    /**
     * Whether the sub should be phase-reversed: true when reversed measured clearly louder at the crossover
     * point. Within 1 dB the stereo is left as it is, since the difference is inside measuring error.
     */
    public static boolean reversePhase(double normalDb, double reversedDb) {
        return !Double.isNaN(normalDb) && !Double.isNaN(reversedDb) && reversedDb - normalDb > 1.0;
    }

    /** One change the wizard proposes: what, from what, to what. */
    public static final class Change {
        public final String what, from, to;
        Change(String what, String from, String to) { this.what = what; this.from = from; this.to = to; }
        @Override public String toString() { return what + ": " + from + " to " + to; }
    }

    /** The changes between what the stereo has now and what the wizard suggests; unchanged settings are left out. */
    public static List<Change> plan(double[] nowFeet, double[] newFeet, boolean nowReversed, boolean newReversed,
                                    int nowSubLevel, int newSubLevel) {
        List<Change> out = new ArrayList<>();
        for (int i = 0; i < SPEAKERS; i++) {
            double a = i < nowFeet.length ? nowFeet[i] : 0, b = i < newFeet.length ? newFeet[i] : 0;
            if (Math.abs(a - b) >= 0.005)
                out.add(new Change(KenwoodManual.DTA_SPEAKERS[i] + " distance", ft(a), ft(b)));
        }
        if (nowReversed != newReversed) out.add(new Change("Sub phase", phase(nowReversed), phase(newReversed)));
        int lo = KenwoodManual.SUB_LEVEL_MIN, hi = KenwoodManual.SUB_LEVEL_MAX;
        int clamped = Math.max(lo, Math.min(hi, newSubLevel));
        if (nowSubLevel != clamped) out.add(new Change("Sub level", String.valueOf(nowSubLevel), String.valueOf(clamped)));
        return out;
    }

    private static String ft(double v) { return String.format(Locale.US, "%.2f ft", v); }

    private static String phase(boolean reversed) { return reversed ? "Reverse (180)" : "Normal (0)"; }

    /**
     * The volume to drop to before changing crossovers or sub phase: the lower of where it is now and 6, so a
     * change never plays louder than it was, and never from a high setting.
     */
    public static int safeVolume(int nowVolume) { return Math.min(Math.max(nowVolume, 0), 6); }
}
