package com.strickland.jeepdash.core;

/**
 * Works out the Bluetooth speaker delay by comparing two loudness traces of the same music: the bass as the
 * tablet sends it out (before Bluetooth) and the bass the tablet's microphone hears from the speakers. Both are
 * laid on a 10 ms grid and slid against each other; the shift where they match best is the delay. A match
 * only counts when the traces really line up (correlation 0.45 or better), so road noise or silence gives no
 * answer rather than a wrong one.
 */
public final class DelayEstimator {
    private static final int KEEP = 2000;
    private static final double STEP = 0.010;
    private final long[] refT = new long[KEEP], micT = new long[KEEP];
    private final double[] refV = new double[KEEP], micV = new double[KEEP];
    private int refN, refAt, micN, micAt;

    /** One loudness reading: from the sound going out (mic false) or from the microphone (mic true). */
    public synchronized void add(boolean mic, long timeNs, double value) {
        if (mic) {
            micT[micAt] = timeNs;
            micV[micAt] = value;
            micAt = (micAt + 1) % KEEP;
            micN = Math.min(KEEP, micN + 1);
        } else {
            refT[refAt] = timeNs;
            refV[refAt] = value;
            refAt = (refAt + 1) % KEEP;
            refN = Math.min(KEEP, refN + 1);
        }
    }

    public synchronized void clear() { refN = micN = refAt = micAt = 0; }

    /** The latest reading at or before time t (sample and hold), or NaN before the first. */
    private static double at(long[] t, double[] v, int n, int head, long when) {
        double out = Double.NaN;
        long best = Long.MIN_VALUE;
        for (int k = 0; k < n; k++) {
            int i = Math.floorMod(head - 1 - k, KEEP);
            if (t[i] <= when && t[i] > best) {
                best = t[i];
                out = v[i];
                break;                                          // newest first: the first one found is it
            }
        }
        return out;
    }

    /**
     * The delay in seconds and how well it matched, {delay, correlation}, over the last {@code windowS} seconds,
     * trying shifts up to {@code maxLagS}; null when there is too little to go on or no clear match.
     */
    public synchronized double[] estimate(long nowNs, double windowS, double maxLagS) {
        int lags = (int) Math.round(maxLagS / STEP), cells = (int) Math.round(windowS / STEP);
        if (cells < 100 || refN < 50 || micN < 100) return null;
        long end = nowNs - (long) (maxLagS * 1e9) - 20_000_000L;
        double[] a = new double[cells], b = new double[cells + lags];
        for (int i = 0; i < cells + lags; i++) {
            long when = end - (long) ((cells - 1 - i) * STEP * 1e9);
            if (i < cells) a[i] = at(refT, refV, refN, refAt, when);
            b[i] = at(micT, micV, micN, micAt, when);
        }
        double bestR = -1;
        int bestLag = -1;
        for (int lag = 0; lag <= lags; lag++) {
            double sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0;
            int n = 0;
            for (int i = 0; i < cells; i++) {
                double x = a[i], y = b[i + lag];
                if (Double.isNaN(x) || Double.isNaN(y)) continue;
                sa += x;
                sb += y;
                saa += x * x;
                sbb += y * y;
                sab += x * y;
                n++;
            }
            if (n < cells / 2) continue;
            double cov = sab - sa * sb / n, va = saa - sa * sa / n, vb = sbb - sb * sb / n;
            if (va <= 1e-12 || vb <= 1e-12) continue;
            double r = cov / Math.sqrt(va * vb);
            if (r > bestR) {
                bestR = r;
                bestLag = lag;
            }
        }
        if (bestLag < 0 || bestR < 0.45) return null;
        return new double[]{bestLag * STEP, bestR};
    }
}
