package com.strickland.jeepdash.core;

public class SpectrumTest {
    static final int RATE = 44100, N = 2048, BANDS = 32;

    static short[] tone(double hz, double amp) {
        short[] s = new short[N];
        for (int i = 0; i < N; i++) s[i] = (short) (amp * Math.sin(2 * Math.PI * hz * i / RATE));
        return s;
    }

    public static void fftFindsATone() {
        float[] re = new float[64], im = new float[64];
        for (int i = 0; i < 64; i++) re[i] = (float) Math.cos(2 * Math.PI * 5 * i / 64);
        Spectrum.fft(re, im);
        int best = 0;
        for (int k = 1; k < 32; k++) if (Math.hypot(re[k], im[k]) > Math.hypot(re[best], im[best])) best = k;
        T.eq(5, best, "the tone's bin");
        T.near(32, Math.hypot(re[5], im[5]), 0.01, "with half the length as its size");
    }

    public static void aToneLightsItsOwnBar() {
        Spectrum s = new Spectrum(RATE, N, BANDS);
        s.feed(tone(1000, 8000), 0.023f);
        float[] lv = new float[BANDS], pk = new float[BANDS];
        s.copy(lv, pk);
        int b = s.bandOf(1000);
        T.ok(lv[b] > 0.95f, "the 1 kHz bar is full: " + lv[b]);
        T.ok(lv[s.bandOf(8000)] < 0.1f, "a bar far away stays dark: " + lv[s.bandOf(8000)]);
        T.ok(lv[s.bandOf(60)] < 0.1f, "so does the bass: " + lv[s.bandOf(60)]);
        T.near(lv[b], pk[b], 0.001, "the peak cap sits on the bar");
    }

    public static void quietMusicStillFillsTheScreen() {
        Spectrum s = new Spectrum(RATE, N, BANDS);
        s.feed(tone(1000, 300), 0.023f);            // about 40 dB below full scale
        float[] lv = new float[BANDS], pk = new float[BANDS];
        s.copy(lv, pk);
        T.ok(lv[s.bandOf(1000)] > 0.7f, "levels follow the music's own loudness: " + lv[s.bandOf(1000)]);
    }

    public static void silenceShowsNothing() {
        Spectrum s = new Spectrum(RATE, N, BANDS);
        s.feed(new short[N], 0.023f);
        float[] lv = new float[BANDS], pk = new float[BANDS];
        s.copy(lv, pk);
        for (int b = 0; b < BANDS; b++) T.near(0, lv[b], 0.0001, "bar " + b);
        T.near(0, s.vu(), 0.0001, "meter at rest");
        T.near(0, s.beat(), 0.0001, "no beat");
    }

    public static void barsFallSmoothlyAndPeakCapsHang() {
        Spectrum s = new Spectrum(RATE, N, BANDS);
        s.feed(tone(1000, 8000), 0.023f);
        s.feed(new short[N], 0.1f);
        float[] lv = new float[BANDS], pk = new float[BANDS];
        s.copy(lv, pk);
        int b = s.bandOf(1000);
        T.near(0.82, lv[b], 0.02, "the bar falls, it does not vanish");
        T.ok(pk[b] > 0.99f, "the cap hangs for a moment");
        for (int i = 0; i < 20; i++) s.feed(new short[N], 0.1f);
        s.copy(lv, pk);
        T.near(0, lv[b], 0.0001, "bar gone after two seconds of silence");
        T.near(0, pk[b], 0.0001, "and the cap has come down");
    }

    public static void fallSpeedSetsHowFastBarsDrop() {
        int b = new Spectrum(RATE, N, BANDS).bandOf(1000);
        float[] lv = new float[BANDS], pk = new float[BANDS];
        Spectrum fast = new Spectrum(RATE, N, BANDS);
        fast.fallSpeed = 2;
        fast.feed(tone(1000, 8000), 0.023f);
        fast.feed(new short[N], 0.1f);
        fast.copy(lv, pk);
        T.near(0.64, lv[b], 0.02, "twice as fast");
        Spectrum slow = new Spectrum(RATE, N, BANDS);
        slow.fallSpeed = 0.5f;
        slow.feed(tone(1000, 8000), 0.023f);
        slow.feed(new short[N], 0.1f);
        slow.copy(lv, pk);
        T.near(0.91, lv[b], 0.02, "half as fast");
    }

    public static void aBassHitIsABeatAndASteadyNoteIsNot() {
        Spectrum s = new Spectrum(RATE, N, BANDS);
        for (int i = 0; i < 20; i++) s.feed(tone(2000, 6000), 0.023f);
        T.near(0, s.beat(), 0.0001, "no bass yet");
        s.feed(tone(60, 12000), 0.023f);
        T.near(1, s.beat(), 0.0001, "the kick lands");
        T.ok(s.bass() > 0.5f, "bass is up: " + s.bass());
        for (int i = 0; i < 80; i++) s.feed(tone(60, 12000), 0.023f);
        T.near(0, s.beat(), 0.0001, "a held bass note stops counting as a beat");
    }

    public static void everyBarHasItsOwnSliceInOrder() {
        Spectrum s = new Spectrum(RATE, N, BANDS);
        int last = -1;
        for (double hz = 45; hz < 13000; hz *= 1.2) {
            int b = s.bandOf(hz);
            T.ok(b >= last, "bars go up with pitch at " + hz);
            last = b;
        }
        T.eq(BANDS - 1, s.bandOf(13900), "the top bar reaches the top of the range");
        T.eq(0, s.bandOf(40), "the bottom bar starts at the lowest bass");
    }
}
