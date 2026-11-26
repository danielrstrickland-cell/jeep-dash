package com.strickland.jeepdash.core;

/** The double-precision transform behind the box-tune measurement and the room-correction filter. */
public class FftTest {

    public static void aToneLandsInItsOwnBin() {
        int n = 1024;
        double[] re = new double[n], im = new double[n];
        for (int i = 0; i < n; i++) re[i] = Math.cos(2 * Math.PI * 37 * i / n);
        Fft.transform(re, im, false);
        T.near(n / 2.0, Math.hypot(re[37], im[37]), 1e-6, "all of the tone in bin 37");
        T.near(0, Math.hypot(re[36], im[36]), 1e-6, "none next door");
    }

    public static void forwardThenInverseGivesTheSignalBack() {
        int n = 256;
        double[] re = new double[n], im = new double[n], keep = new double[n];
        java.util.Random r = new java.util.Random(4);
        for (int i = 0; i < n; i++) keep[i] = re[i] = r.nextDouble() - 0.5;
        Fft.transform(re, im, false);
        Fft.transform(re, im, true);
        for (int i = 0; i < n; i++) {
            T.near(keep[i], re[i], 1e-9, "sample " + i + " survives");
            T.near(0, im[i], 1e-9, "and stays real");
        }
    }

    public static void sizeMustBeAPowerOfTwo() {
        try {
            Fft.transform(new double[100], new double[100], false);
            T.ok(false, "100 points should be refused");
        } catch (IllegalArgumentException expected) { }
    }
}
