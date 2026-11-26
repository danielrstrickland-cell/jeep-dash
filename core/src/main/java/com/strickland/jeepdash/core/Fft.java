package com.strickland.jeepdash.core;

/**
 * A plain double-precision FFT for the sound work that needs more accuracy than the visualizer's float one:
 * the box-tune measurement, the room-correction filter design and the convolution itself. In place, radix 2.
 */
public final class Fft {
    private Fft() { }

    /** Transforms re/im in place (a power-of-two length). The inverse divides by the length. */
    public static void transform(double[] re, double[] im, boolean inverse) {
        int n = re.length;
        if (n == 0 || Integer.bitCount(n) != 1 || im.length != n)
            throw new IllegalArgumentException("length must be a power of two");
        for (int i = 1, j = 0; i < n; i++) {                 // bit-reversal
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2 * Math.PI / len * (inverse ? 1 : -1);
            double wr = Math.cos(ang), wi = Math.sin(ang);
            int half = len >> 1;
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < half; k++) {
                    int a = i + k, b = a + half;
                    double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    double t = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = t;
                }
            }
        }
        if (inverse) for (int i = 0; i < n; i++) { re[i] /= n; im[i] /= n; }
    }
}
