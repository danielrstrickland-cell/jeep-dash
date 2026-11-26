package com.strickland.jeepdash.core;

import java.util.Arrays;

/**
 * Box-tune estimation: finds a ported sub box's tuning frequency with nothing but the tablet. A slow sine
 * sweep from 20 to 80 Hz is played through the system while the mic is held right at the sub's cone. At the
 * box's tuning the port does the work and the cone almost stops, so what the mic hears there has a sharp
 * dip; the bottom of that dip is the tuning. A dip stays where it is whatever the mic's own bass roll-off,
 * which is why the cone is measured and not the port's (broader, shifted) peak.
 *
 * Nothing is timed against the playback, so Bluetooth's delay does not matter: the whole recording is
 * averaged into one spectrum, and a sweep that spends the same time at every pitch makes that spectrum the
 * system's response.
 */
public final class BoxTune {
    public static final double LO_HZ = 20, HI_HZ = 80, SWEEP_S = 12;
    static final double FADE_S = 0.25, CLEAR_DB = 6, HEARD_DB = 12;

    private BoxTune() { }

    /** Sample i of the sweep at this sample rate, -1..1: silence before the start and after the end. */
    public static double sweepSample(long i, double rate) {
        double t = i / rate;
        if (t < 0 || t >= SWEEP_S) return 0;
        double edge = Math.min(1, Math.min(t, SWEEP_S - t) / FADE_S);
        double fade = 0.5 - 0.5 * Math.cos(Math.PI * edge);
        return fade * Math.sin(2 * Math.PI * (LO_HZ * t + (HI_HZ - LO_HZ) * t * t / (2 * SWEEP_S)));
    }

    /** What the mic heard, as level against pitch, from 0 to 300 Hz. */
    public static final class Response {
        public final double[] hz, db;
        Response(double[] hz, double[] db) { this.hz = hz; this.db = db; }
    }

    /** The averaged spectrum of the first n samples of a recording (well under 1 Hz a point at 48 kHz). */
    public static Response response(short[] pcm, int n, double rate) {
        int size = Integer.highestOneBit((int) (rate * 1.5));
        int keep = (int) Math.min(size / 2, Math.ceil(300 * size / rate));
        double[] power = new double[keep], re = new double[size], im = new double[size];
        int windows = 0;
        for (int start = 0; windows == 0 || start + size <= n; start += size / 4) {   // 75 % overlap: every moment counts the same
            for (int i = 0; i < size; i++) {
                int at = start + i;
                double s = at < n ? pcm[at] / 32768.0 : 0;
                re[i] = s * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (size - 1)));
                im[i] = 0;
            }
            Fft.transform(re, im, false);
            for (int k = 0; k < keep; k++) power[k] += re[k] * re[k] + im[k] * im[k];
            windows++;
        }
        double[] hz = new double[keep], db = new double[keep];
        for (int k = 0; k < keep; k++) {
            hz[k] = k * rate / size;
            db[k] = 10 * Math.log10(power[k] / windows / ((double) size * size / 16) + 1e-20);
        }
        return new Response(hz, db);
    }

    /** The answer: the tuning, how deep the dip was, and whether to believe it. */
    public static final class Result {
        public final double hz, depthDb;
        public final boolean heard;     // the sweep came back well above the room's own noise
        public final boolean clear;     // heard, and the dip is deep enough to be the tuning
        Result(double hz, double depthDb, boolean heard, boolean clear) {
            this.hz = hz; this.depthDb = depthDb; this.heard = heard; this.clear = clear;
        }
    }

    /**
     * Finds the tuning dip: the dip between 22 and 75 Hz that stands deepest below the level on both sides
     * of it. Under 6 dB is not called clear (a sealed box, the mic too far from the cone, or a noisy cabin).
     */
    public static Result estimate(Response r) {
        int n = r.db.length;
        double[] s = new double[n];
        for (int k = 0; k < n; k++) {
            double sum = 0;
            int c = 0;
            for (int j = Math.max(0, k - 1); j <= Math.min(n - 1, k + 1); j++) { sum += r.db[j]; c++; }
            s[k] = sum / c;
        }
        double top = -200;
        int lo = -1, hi = -1, floorCount = 0;
        double[] floor = new double[n];
        for (int k = 0; k < n; k++) {
            if (r.hz[k] >= LO_HZ && r.hz[k] <= HI_HZ) {
                if (lo < 0) lo = k;
                hi = k;
                top = Math.max(top, s[k]);
            } else if (r.hz[k] >= 150 && r.hz[k] <= 250) floor[floorCount++] = s[k];
        }
        if (lo < 0 || hi - lo < 4 || floorCount == 0) return new Result(0, 0, false, false);
        Arrays.sort(floor, 0, floorCount);
        boolean heard = top - floor[floorCount / 2] >= HEARD_DB;

        double[] leftMax = new double[n], rightMax = new double[n];
        double m = -200;
        for (int k = lo; k <= hi; k++) { leftMax[k] = m; m = Math.max(m, s[k]); }
        m = -200;
        for (int k = hi; k >= lo; k--) { rightMax[k] = m; m = Math.max(m, s[k]); }
        int best = -1;
        double depth = 0;
        for (int k = lo + 1; k < hi; k++) {
            if (r.hz[k] < 22 || r.hz[k] > 75 || s[k] > s[k - 1] || s[k] > s[k + 1]) continue;
            double d = Math.min(leftMax[k], rightMax[k]) - s[k];
            if (d > depth) { depth = d; best = k; }
        }
        if (best < 0) return new Result(0, 0, heard, false);
        double a = s[best - 1], b = s[best], c = s[best + 1], bend = a - 2 * b + c;
        double shift = bend > 1e-9 ? 0.5 * (a - c) / bend : 0;
        double hz = r.hz[best] + Math.max(-0.5, Math.min(0.5, shift)) * (r.hz[1] - r.hz[0]);
        return new Result(hz, depth, heard, heard && depth >= CLEAR_DB);
    }
}
