package com.strickland.jeepdash.core;

/**
 * Turns a correction curve (dB per third-octave band, as Calibrate produces) into the filter the lossless
 * player convolves the music with. The filter has exactly the curve's gains at every pitch, and is minimum
 * phase: it acts the moment a note starts and rings only after it, as the cabin's own resonances do, so
 * there is no echo ahead of the beat the way a symmetric (linear-phase) filter would give.
 */
public final class FirDesign {
    private FirDesign() { }

    /** Filter length for a sample rate: long enough for about 6 Hz of detail in the bass at any rate. */
    public static int tapsFor(double rate) {
        int taps = 8192;
        while (rate / taps > 6.5 && taps < 65536) taps <<= 1;
        return taps;
    }

    /** How far to turn the music down before the filter so its biggest boost cannot clip: 0 or negative dB. */
    public static double headroomDb(double[] bandDb) {
        double top = 0;
        for (double d : bandDb) top = Math.max(top, d);
        return -top;
    }

    /**
     * The minimum-phase filter for a band curve at this sample rate, taps long (a power of two). Built the
     * standard way: the curve's log-magnitude is folded in the cepstrum so all of its phase is the least
     * delay that magnitude allows.
     */
    public static double[] minimumPhase(double[] bandDb, double rate, int taps) {
        if (Integer.bitCount(taps) != 1) throw new IllegalArgumentException("taps must be a power of two");
        int m = taps * 4;
        double[] re = new double[m], im = new double[m];
        double ln = Math.log(10) / 20;
        for (int k = 0; k <= m / 2; k++) {
            double v = SoundTune.at(bandDb, k * rate / m) * ln;
            re[k] = v;
            if (k > 0 && k < m / 2) re[m - k] = v;
        }
        Fft.transform(re, im, true);                         // the cepstrum of the magnitude
        for (int i = 1; i < m / 2; i++) re[i] *= 2;          // fold the future onto the past
        for (int i = m / 2 + 1; i < m; i++) re[i] = 0;
        java.util.Arrays.fill(im, 0);
        Fft.transform(re, im, false);                        // log of the minimum-phase response
        for (int k = 0; k < m; k++) {
            double mag = Math.exp(re[k]), ph = im[k];
            re[k] = mag * Math.cos(ph);
            im[k] = mag * Math.sin(ph);
        }
        Fft.transform(re, im, true);
        double[] h = new double[taps];
        int fade = taps / 4;                                 // ease the cut-off end to nothing
        for (int i = 0; i < taps; i++) {
            double w = i < taps - fade ? 1 : 0.5 + 0.5 * Math.cos(Math.PI * (i - (taps - fade)) / fade);
            h[i] = re[i] * w;
        }
        return h;
    }
}
