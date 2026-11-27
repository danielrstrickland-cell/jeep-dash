package com.strickland.jeepdash.core;

import java.util.Random;

public final class DelayEstimatorTest {
    /** A bass line: a hit roughly every 0.45 s with varied strength, decaying fast. */
    private static double bass(double t, double[] hits) {
        double v = 0;
        for (int i = 0; i < hits.length; i++) {
            double dt = t - i * 0.45;
            if (dt >= 0 && dt < 0.4) v += hits[i] * Math.exp(-dt * 12);
        }
        return v;
    }

    public static void findsABluetoothDelay() {
        Random r = new Random(4);
        double[] hits = new double[40];
        for (int i = 0; i < hits.length; i++) hits[i] = 0.3 + r.nextDouble();
        DelayEstimator e = new DelayEstimator();
        double delay = 0.18;
        long t0 = 1_000_000_000L;
        for (double t = 0; t < 12; t += 0.023) e.add(false, t0 + (long) (t * 1e9), bass(t, hits));       // the tap, every 23 ms
        for (double t = 0; t < 12; t += 0.010)                                                             // the mic, every 10 ms, noisy
            e.add(true, t0 + (long) (t * 1e9), bass(t - delay, hits) * 0.6 + 0.08 * r.nextDouble());
        double[] got = e.estimate(t0 + 12_000_000_000L, 6, 0.5);
        T.ok(got != null, "found a match");
        T.near(delay, got[0], 0.025, "the delay, within 25 ms");
        T.ok(got[1] > 0.6, "a strong match: " + got[1]);
    }

    public static void noAnswerFromNoise() {
        Random r = new Random(5);
        DelayEstimator e = new DelayEstimator();
        long t0 = 1_000_000_000L;
        for (double t = 0; t < 12; t += 0.023) e.add(false, t0 + (long) (t * 1e9), r.nextDouble());
        for (double t = 0; t < 12; t += 0.010) e.add(true, t0 + (long) (t * 1e9), r.nextDouble());
        T.ok(e.estimate(t0 + 12_000_000_000L, 6, 0.5) == null, "unrelated sound gives no answer");
    }
}
