package com.strickland.jeepdash.core;

/** The lossless player's room correction: the filter made from the correction curve, and the convolution. */
public class RoomFilterTest {
    private static final double RATE = 48000;

    /** The filter's gain in dB at a pitch, straight from its taps. */
    private static double gainDb(double[] taps, double hz) {
        double re = 0, im = 0, w = 2 * Math.PI * hz / RATE;
        for (int i = 0; i < taps.length; i++) { re += taps[i] * Math.cos(w * i); im -= taps[i] * Math.sin(w * i); }
        return 20 * Math.log10(Math.hypot(re, im));
    }

    private static double[] curve() {
        double[] c = SoundTune.bandCentres(), g = new double[c.length];
        for (int i = 0; i < c.length; i++) g[i] = c[i] <= 125 ? 6 : c[i] >= 4000 ? -4 : 0;
        return g;
    }

    public static void aFlatCurveIsAStraightWire() {
        double[] h = FirDesign.minimumPhase(new double[31], RATE, 1024);
        T.near(1, h[0], 1e-6, "all of the signal in the first tap");
        for (int i = 1; i < h.length; i++) T.near(0, h[i], 1e-6, "nothing after it");
    }

    public static void theFilterHasTheCurvesGains() {
        double[] h = FirDesign.minimumPhase(curve(), RATE, FirDesign.tapsFor(RATE));
        T.near(6, gainDb(h, 50), 0.3, "bass lift where the curve asks");
        T.near(0, gainDb(h, 1000), 0.3, "level in the middle");
        T.near(-4, gainDb(h, 8000), 0.3, "treble cut where the curve asks");
    }

    public static void minimumPhaseKeepsItsEnergyAtTheFront() {
        double[] h = FirDesign.minimumPhase(curve(), RATE, 8192);
        double front = 0, all = 0;
        for (int i = 0; i < h.length; i++) {
            all += h[i] * h[i];
            if (i < h.length / 4) front += h[i] * h[i];
            T.ok(Math.abs(h[i]) <= Math.abs(h[0]), "the first tap is the biggest: nothing rings before the note");
        }
        T.ok(front / all > 0.999, "and the energy is at the front, " + front / all);
    }

    public static void longerFiltersForHigherSampleRates() {
        T.eq(8192, FirDesign.tapsFor(44100), "CD rate");
        T.eq(8192, FirDesign.tapsFor(48000), "48 kHz");
        T.eq(16384, FirDesign.tapsFor(96000), "96 kHz keeps the same bass detail");
        T.eq(32768, FirDesign.tapsFor(192000), "192 kHz too");
    }

    public static void headroomMatchesTheBiggestBoost() {
        T.near(-6, FirDesign.headroomDb(curve()), 1e-9, "pulled down by the 6 dB lift");
        T.near(0, FirDesign.headroomDb(new double[]{-3, -1, 0}), 1e-9, "cuts need no headroom");
    }

    public static void convolutionMatchesTheLongHandSum() {
        java.util.Random r = new java.util.Random(7);
        double[] h = new double[37];
        for (int i = 0; i < h.length; i++) h[i] = r.nextDouble() - 0.5;
        Convolver c = new Convolver(h);
        int b = c.block(), n = b * 5;
        T.eq(64, b, "block is the next power of two over the filter");
        float[] x = new float[n], y = new float[n];
        for (int i = 0; i < n; i++) x[i] = (float) (r.nextDouble() - 0.5);
        float[] in = new float[b], out = new float[b];
        for (int at = 0; at < n; at += b) {
            System.arraycopy(x, at, in, 0, b);
            c.process(in, out);
            System.arraycopy(out, 0, y, at, b);
        }
        for (int i = 0; i < n; i++) {
            double want = 0;
            for (int k = 0; k < h.length && k <= i; k++) want += h[k] * x[i - k];
            T.near(want, y[i], 1e-5, "sample " + i);
        }
    }

    public static void resetForgetsTheLastSong() {
        Convolver c = new Convolver(new double[]{0, 0, 0, 1});          // a three-sample delay
        float[] loud = new float[c.block()], quiet = new float[c.block()], out = new float[c.block()];
        java.util.Arrays.fill(loud, 1f);
        c.process(loud, out);
        c.reset();
        c.process(quiet, out);
        for (float v : out) T.near(0, v, 1e-6, "nothing left over after a seek or a new song");
    }
}
