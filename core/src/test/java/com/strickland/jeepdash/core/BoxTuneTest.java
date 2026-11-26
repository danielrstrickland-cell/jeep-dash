package com.strickland.jeepdash.core;

/** Box-tune estimation: the sweep it plays and finding the cone's dip in what the mic heard. */
public class BoxTuneTest {
    private static final double RATE = 48000;

    /** The sweep as the mic would hear it at the cone of a box tuned at tuneHz (a notch there); 0 = no notch. */
    private static short[] heard(double tuneHz, double level, long seed) {
        int n = (int) (RATE * (BoxTune.SWEEP_S + 1));
        short[] out = new short[n];
        double w = 2 * Math.PI * tuneHz / RATE, q = 4, alpha = Math.sin(w) / (2 * q);
        double b0 = 1 / (1 + alpha), b1 = -2 * Math.cos(w) / (1 + alpha), a2 = (1 - alpha) / (1 + alpha);
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        java.util.Random r = new java.util.Random(seed);
        for (int i = 0; i < n; i++) {
            double x = level * BoxTune.sweepSample(i, RATE), y = x;
            if (tuneHz > 0) {
                y = b0 * x + b1 * x1 + b0 * x2 - b1 * y1 - a2 * y2;
                x2 = x1; x1 = x; y2 = y1; y1 = y;
            }
            out[i] = (short) Math.round(8000 * y + 20 * (r.nextDouble() - 0.5));
        }
        return out;
    }

    public static void sweepRunsFromLowToHighAndStaysInRange() {
        int crossings = 0;
        double prev = 0;
        for (int i = (int) (RATE * 5.75); i < RATE * 6.25; i++) {       // half a second around the middle
            double s = BoxTune.sweepSample(i, RATE);
            T.ok(Math.abs(s) <= 1.0, "inside full scale");
            if (prev < 0 && s >= 0) crossings++;
            prev = s;
        }
        T.near(25, crossings, 1.5, "50 Hz half way through a 20 to 80 Hz sweep");
        T.near(0, BoxTune.sweepSample(0, RATE), 1e-9, "starts from silence");
        T.near(0, BoxTune.sweepSample((long) (RATE * (BoxTune.SWEEP_S + 0.5)), RATE), 1e-9, "silent after the end");
    }

    public static void findsTheDipAtTheBoxTuning() {
        short[] pcm = heard(34, 1, 1);
        BoxTune.Result r = BoxTune.estimate(BoxTune.response(pcm, pcm.length, RATE));
        T.ok(r.heard, "the sweep was heard");
        T.ok(r.clear, "the dip is clear");
        T.near(34, r.hz, 1.0, "tuning found within 1 Hz");
        T.ok(r.depthDb > 6, "a deep dip");
    }

    public static void aDifferentBoxGivesADifferentAnswer() {
        short[] pcm = heard(41.5, 1, 2);
        T.near(41.5, BoxTune.estimate(BoxTune.response(pcm, pcm.length, RATE)).hz, 1.0, "41.5 Hz box");
    }

    public static void noDipMeansNoClearAnswer() {
        short[] pcm = heard(0, 1, 3);
        BoxTune.Result r = BoxTune.estimate(BoxTune.response(pcm, pcm.length, RATE));
        T.ok(r.heard, "the sweep was heard");
        T.ok(!r.clear, "but nothing to call a tuning dip");
        T.ok(r.depthDb < 1, "an even response reads even, not rippled by the averaging: " + r.depthDb);
    }

    public static void silenceIsNotHeard() {
        short[] pcm = heard(34, 0, 4);
        BoxTune.Result r = BoxTune.estimate(BoxTune.response(pcm, pcm.length, RATE));
        T.ok(!r.heard, "only room noise came back");
        T.ok(!r.clear, "so there is no answer");
    }
}
