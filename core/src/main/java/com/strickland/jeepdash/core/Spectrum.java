package com.strickland.jeepdash.core;

/**
 * Turns sound samples into what the visualizer draws: frequency bars with falling peak caps, loudness,
 * bass and beats. Levels adjust themselves to how loud the music is, so a quiet phone speaker and a
 * full sound system both fill the screen.
 */
public final class Spectrum {
    static final float RANGE_DB = 40, FLOOR_DB = -30, CEIL_FALL_DB = 2;      // automatic level
    static final float BAR_FALL = 1.8f, PEAK_HOLD = 0.35f, PEAK_FALL = 0.6f; // per second

    private final int n, bands;
    private final float[] re, im, window, rawDb, level, peak, peakAge;
    private final int[] edge;                       // band b is bins edge[b] .. edge[b+1]-1
    private final double binHz;
    /** 1 = normal; 2 makes bars and caps drop twice as fast. Set by the screen, read while analysing. */
    public volatile float fallSpeed = 1;
    private float ceilDb = FLOOR_DB, vuCeilDb = FLOOR_DB, vu, bass, bassAvg, beat;

    public Spectrum(int sampleRate, int fftSize, int bands) {
        if (Integer.bitCount(fftSize) != 1) throw new IllegalArgumentException("size must be a power of two");
        n = fftSize;
        this.bands = bands;
        re = new float[n];
        im = new float[n];
        window = new float[n];
        for (int i = 0; i < n; i++) window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1)));
        rawDb = new float[bands];
        level = new float[bands];
        peak = new float[bands];
        peakAge = new float[bands];
        edge = new int[bands + 1];
        binHz = (double) sampleRate / n;
        double lo = 40, hi = Math.min(14000, sampleRate * 0.475);
        int prev = 0;
        for (int b = 0; b <= bands; b++) {          // evenly spaced by pitch, at least one bin each
            int bin = (int) Math.round(lo * Math.pow(hi / lo, (double) b / bands) / binHz);
            prev = edge[b] = Math.min(Math.max(bin, prev + 1), n / 2);
        }
    }

    public int bands() { return bands; }

    public int size() { return n; }

    /** The bar a pure tone of this pitch lands in. */
    public int bandOf(double hz) {
        int bin = (int) Math.round(hz / binHz);
        for (int b = 0; b < bands; b++) if (bin < edge[b + 1]) return b;
        return bands - 1;
    }

    /** Takes size() samples; dt is the time since the last call, in seconds. */
    public synchronized void feed(short[] pcm, float dt) {
        double sum = 0;
        for (int i = 0; i < n; i++) {
            float s = pcm[i] / 32768f;
            sum += s * s;
            re[i] = s * window[i];
            im[i] = 0;
        }
        fft(re, im);

        float rmsDb = db((float) Math.sqrt(sum / n));
        vuCeilDb = Math.max(Math.max(rmsDb, FLOOR_DB), vuCeilDb - CEIL_FALL_DB * dt);
        float vuNow = clamp((rmsDb - (vuCeilDb - 30)) / 30);
        vu += (vuNow - vu) * Math.min(1, dt * 12);

        float frameMax = -200;
        for (int b = 0; b < bands; b++) {
            float m = 0;
            for (int k = edge[b]; k < edge[b + 1]; k++) m = Math.max(m, (float) Math.hypot(re[k], im[k]));
            rawDb[b] = db(m / (n / 4f));            // a full-scale tone reads 0 dB
            frameMax = Math.max(frameMax, rawDb[b]);
        }
        ceilDb = Math.max(Math.max(frameMax, FLOOR_DB), ceilDb - CEIL_FALL_DB * dt);

        int lows = Math.max(1, bands / 6);
        float fall = fallSpeed;
        float lowSum = 0;
        for (int b = 0; b < bands; b++) {
            float t = clamp((rawDb[b] - (ceilDb - RANGE_DB)) / RANGE_DB);
            level[b] = Math.max(t, level[b] - BAR_FALL * fall * dt);
            if (level[b] >= peak[b]) {
                peak[b] = level[b];
                peakAge[b] = 0;
            } else if ((peakAge[b] += dt) > PEAK_HOLD) {
                peak[b] = Math.max(level[b], peak[b] - PEAK_FALL * fall * dt);
            }
            if (b < lows) lowSum += t;
        }
        bass = lowSum / lows;
        if (bass > bassAvg * 1.3f + 0.08f) beat = 1;
        else beat = Math.max(0, beat - 4 * dt);
        bassAvg += (bass - bassAvg) * Math.min(1, dt * 1.5f);
    }

    /** Copies the bar heights and peak caps (0..1) for drawing. */
    public synchronized void copy(float[] levels, float[] peaks) {
        System.arraycopy(level, 0, levels, 0, bands);
        System.arraycopy(peak, 0, peaks, 0, bands);
    }

    /** Overall loudness 0..1, moving like a meter needle. */
    public synchronized float vu() { return vu; }

    /** How strong the lowest bars are right now, 0..1. */
    public synchronized float bass() { return bass; }

    /** Jumps to 1 on a bass hit and fades within a quarter second. */
    public synchronized float beat() { return beat; }

    private static float db(float x) { return (float) (20 * Math.log10(Math.max(x, 1e-9f))); }

    private static float clamp(float x) { return x < 0 ? 0 : Math.min(x, 1); }

    /** In-place FFT; the length must be a power of two. */
    static void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = a + len / 2;
                    float xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    float nr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = nr;
                }
            }
        }
    }
}
