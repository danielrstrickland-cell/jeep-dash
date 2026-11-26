package com.strickland.jeepdash.core;

/**
 * Convolves a stream of audio with a long filter, one block at a time, by multiplying spectra (overlap-save)
 * instead of summing thousands of taps per sample. One instance per channel; it remembers the previous
 * block, so blocks must be fed in order and {@link #reset()} called on a seek or a new song.
 */
public final class Convolver {
    private final int block;
    private final double[] hRe, hIm, re, im;
    private final float[] prev;

    public Convolver(double[] taps) {
        int b = 1;
        while (b < taps.length) b <<= 1;
        block = b;
        hRe = new double[2 * b];
        hIm = new double[2 * b];
        System.arraycopy(taps, 0, hRe, 0, taps.length);
        Fft.transform(hRe, hIm, false);
        re = new double[2 * b];
        im = new double[2 * b];
        prev = new float[b];
    }

    /** How many samples each {@link #process} call takes and gives. */
    public int block() { return block; }

    /** Filters one block; in and out are block() long and may be the same array. */
    public void process(float[] in, float[] out) {
        for (int i = 0; i < block; i++) {
            re[i] = prev[i];
            re[block + i] = in[i];
            prev[i] = in[i];
        }
        java.util.Arrays.fill(im, 0);
        Fft.transform(re, im, false);
        for (int k = 0; k < 2 * block; k++) {
            double r = re[k] * hRe[k] - im[k] * hIm[k];
            im[k] = re[k] * hIm[k] + im[k] * hRe[k];
            re[k] = r;
        }
        Fft.transform(re, im, true);
        for (int i = 0; i < block; i++) out[i] = (float) re[block + i];
    }

    /** Forgets the previous block. */
    public void reset() { java.util.Arrays.fill(prev, 0); }
}
