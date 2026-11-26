package com.strickland.jeepdash.core;

/**
 * The cabin's own noise, measured: what the mic hears in each third-octave band with the music paused, kept
 * by road speed in groups of 10 mph. Once it holds the stopped level and at least one speed, noise
 * compensation uses it in place of {@link SoundAdapt}'s starting model: the boost at a speed follows how
 * far the noise there rose above the stopped level, band by band, so it has this Jeep's shape (its tyres,
 * its exhaust, its deadening) and not a guess.
 *
 * Levels are whatever the mic reads (not true SPL); only the rise between stopped and moving is used, so
 * the mic's own sensitivity cancels out. Fed by the mic thread and read by the DSP tick, so synchronized.
 */
public final class NoiseProfile {
    public static final int STEP_MPH = 10, GROUPS = 10, MIN_SAMPLES = 24;
    static final int BANDS = 31;
    static final double DB_PER_DB = 0.5;

    private final double[][] sumDb = new double[GROUPS][BANDS];
    private final int[] count = new int[GROUPS];

    /** The group a road speed falls in: 0 is stopped (under 10 mph), 9 is 90 mph and up. */
    public static int bucket(double mph) {
        if (!(mph > 0)) return 0;
        return Math.min(GROUPS - 1, (int) (mph / STEP_MPH));
    }

    /** One moment of cabin noise at this speed: the mic's level in each band, in dB. */
    public synchronized void add(double mph, double[] bandsDb) {
        if (bandsDb == null || bandsDb.length < BANDS) return;
        int g = bucket(mph);
        for (int i = 0; i < BANDS; i++) sumDb[g][i] += bandsDb[i];
        count[g]++;
    }

    public synchronized int samples(int group) { return group < 0 || group >= GROUPS ? 0 : count[group]; }

    public synchronized int total() {
        int n = 0;
        for (int c : count) n += c;
        return n;
    }

    private boolean has(int g) { return count[g] >= MIN_SAMPLES; }

    /** Usable once the stopped level and at least one moving speed have each been heard for a couple of seconds. */
    public synchronized boolean ready() {
        if (!has(0)) return false;
        for (int g = 1; g < GROUPS; g++) if (has(g)) return true;
        return false;
    }

    /** What has been learned so far, for the screen: "stopped, 30, 60 mph". */
    public synchronized String summary() {
        StringBuilder b = new StringBuilder();
        boolean moving = false;
        for (int g = 0; g < GROUPS; g++) {
            if (!has(g)) continue;
            if (b.length() > 0) b.append(", ");
            b.append(g == 0 ? "stopped" : String.valueOf(g * STEP_MPH));
            moving |= g > 0;
        }
        if (b.length() == 0) return "nothing yet";
        return moving ? b + " mph" : b.toString();
    }

    private double rise(int g, int band) {
        return Math.max(0, sumDb[g][band] / count[g] - sumDb[0][band] / count[0]);
    }

    /**
     * How far the noise at this speed is above the stopped level, per band, in dB. Slides between the speeds
     * that were heard (from nothing at 10 mph up to the first), holds past the fastest one, and each band is
     * averaged with its neighbours. All zeros until {@link #ready()}.
     */
    public synchronized double[] riseBands(double mph) {
        double[] raw = new double[BANDS];
        if (!ready() || bucket(mph) == 0) return raw;
        int below = 0, above = -1;                    // the heard groups either side of this speed, by centre
        for (int g = 1; g < GROUPS; g++) {
            if (!has(g)) continue;
            if (g * STEP_MPH + STEP_MPH / 2.0 <= mph) below = g;
            else if (above < 0) above = g;
        }
        for (int i = 0; i < BANDS; i++) {
            double x0 = below == 0 ? STEP_MPH : below * STEP_MPH + STEP_MPH / 2.0;
            double y0 = below == 0 ? 0 : rise(below, i);
            if (above < 0) { raw[i] = y0; continue; }
            double x1 = above * STEP_MPH + STEP_MPH / 2.0, y1 = rise(above, i);
            raw[i] = y0 + (y1 - y0) * (mph - x0) / (x1 - x0);
        }
        double[] out = new double[BANDS];
        for (int i = 0; i < BANDS; i++) {
            double sum = raw[i];
            int k = 1;
            if (i > 0) { sum += raw[i - 1]; k++; }
            if (i < BANDS - 1) { sum += raw[i + 1]; k++; }
            out[i] = sum / k;
        }
        return out;
    }

    /**
     * Noise compensation from the measurement: half a dB of boost for each dB the noise rose, times the
     * owner's strength (0..1), and never more than {@link SoundAdapt#MAX_BOOST_DB}.
     */
    public double[] boostBands(double mph, double strength) {
        double[] r = riseBands(mph);
        double k = SoundAdapt.clamp(strength, 0, 1) * DB_PER_DB;
        for (int i = 0; i < r.length; i++) r[i] = SoundAdapt.clamp(k * r[i], 0, SoundAdapt.MAX_BOOST_DB);
        return r;
    }

    /** The profile as text for storing: each group's sample count and its mean level per band. */
    public synchronized String toText() {
        StringBuilder b = new StringBuilder("N1");
        for (int g = 0; g < GROUPS; g++) {
            b.append(';').append(count[g]);
            if (count[g] == 0) continue;
            b.append(':');
            for (int i = 0; i < BANDS; i++) {
                if (i > 0) b.append(',');
                b.append(Math.round(sumDb[g][i] / count[g] * 100) / 100.0);
            }
        }
        return b.toString();
    }

    /** Reads {@link #toText()} back; null for nothing stored or anything that is not a profile. */
    public static NoiseProfile fromText(String text) {
        if (text == null) return null;
        String[] parts = text.split(";");
        if (parts.length != GROUPS + 1 || !"N1".equals(parts[0])) return null;
        NoiseProfile p = new NoiseProfile();
        try {
            for (int g = 0; g < GROUPS; g++) {
                String[] kv = parts[g + 1].split(":");
                int n = Integer.parseInt(kv[0].trim());
                if (n <= 0) continue;
                String[] v = kv.length > 1 ? kv[1].split(",") : new String[0];
                if (v.length != BANDS) return null;
                for (int i = 0; i < BANDS; i++) p.sumDb[g][i] = Double.parseDouble(v[i].trim()) * n;
                p.count[g] = n;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return p;
    }
}
