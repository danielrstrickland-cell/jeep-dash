package com.strickland.jeepdash.core;

/**
 * How the 31 third-octave bands are handed to Android's DynamicsProcessing effect so each gain lands on the
 * pitch it was worked out for. The effect works on blocks of sound and can only tell pitches apart in steps
 * of sampleRate / block; it gives each step the gain of the band whose cutoff is the first at or above the
 * step's lower edge (see {@link #bandOfBin}, its own rule).
 *
 * At the effect's own 10 ms default a step is 94 Hz wide, wider than every band under 400 Hz: the 50 Hz
 * band's gain was applied from 47 to 141 Hz and the 160 Hz band's from 141 to 234 Hz, so a bass correction
 * landed in the low mids (build 75 sounded muddy for it). Two things put that right here: a longer block, so
 * a step is about 12 Hz, and each cutoff moved down half a step, so a step takes the band its centre is in.
 */
public final class DspBands {
    /** The block length asked of the effect, in milliseconds: 4096 samples at 44.1 and 48 kHz. */
    public static final float FRAME_MS = 80;

    private DspBands() { }

    /** The block the effect uses for a frame length: the next power of two, as Android rounds it. */
    public static int blockFor(double rate, double frameMs) {
        int want = (int) (frameMs * rate / 1000);
        if (want < 8) return 8;
        return Integer.bitCount(want) == 1 ? want : Integer.highestOneBit(want) << 1;
    }

    /**
     * The cutoff to give the effect for band i: the band's top edge, less half a step, which makes Android's
     * rounding give each step to the band that holds the step's centre.
     */
    public static float cutoff(int band, double rate) {
        double step = rate / blockFor(rate, FRAME_MS);
        return (float) (SoundTune.bandCentres()[band] * Math.pow(2, 1 / 6.0) - step / 2);
    }

    /**
     * Android's own rule for which band each step (FFT bin) of a block takes its gain from, given the bands'
     * cutoffs: a band covers the bins from the one after the previous band's last up to round(cutoff / step).
     * -1 for a bin no band covers.
     */
    static int[] bandOfBin(float[] cutoffs, double rate, int block) {
        int[] of = new int[block / 2 + 1];
        java.util.Arrays.fill(of, -1);
        int start = 0;
        for (int b = 0; b < cutoffs.length; b++) {
            int stop = (int) (0.5 + cutoffs[b] * block / rate);
            for (int k = start; k <= stop && k < of.length; k++) of[k] = b;
            start = stop + 1;
        }
        return of;
    }
}
