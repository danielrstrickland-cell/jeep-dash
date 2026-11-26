package com.strickland.jeepdash.core;

/** The band layout handed to Android's effect: every gain has to land on the pitch it was meant for. */
public class DspBandsTest {
    private static final double RATE = 48000, EDGE = Math.pow(2, 1 / 6.0);

    private static int bandHolding(double hz) {
        double[] c = SoundTune.bandCentres();
        for (int i = 0; i < c.length; i++) if (hz < c[i] * EDGE) return i;
        return c.length - 1;
    }

    public static void blockIsTheNextPowerOfTwoAsAndroidRoundsIt() {
        T.eq(512, DspBands.blockFor(48000, 10), "Android's own 10 ms default is 512 samples");
        T.eq(4096, DspBands.blockFor(48000, 80), "80 ms at 48 kHz");
        T.eq(4096, DspBands.blockFor(44100, 80), "and at 44.1 kHz");
    }

    public static void theShortDefaultBlockPutBassGainsInTheLowMids() {
        float[] plain = new float[31];
        double[] c = SoundTune.bandCentres();
        for (int i = 0; i < 31; i++) plain[i] = (float) (c[i] * EDGE);
        int[] of = DspBands.bandOfBin(plain, RATE, 512);
        T.eq(4, of[1], "what build 75 did: everything from 47 to 141 Hz took the 50 Hz band's gain");
        T.eq(9, of[2], "and 141 to 234 Hz took the 160 Hz band's");
        T.eq(0, of[0], "while everything under 47 Hz took the 20 Hz band's");
    }

    public static void theEffectCanTellTheBassBandsApart() {
        double step = RATE / DspBands.blockFor(RATE, DspBands.FRAME_MS);
        T.ok(step <= 12, "steps no wider than the 50 Hz band (11.6 Hz), but they are " + step + " Hz");
    }

    public static void everyStepTakesTheGainOfTheBandItSitsIn() {
        int block = DspBands.blockFor(RATE, DspBands.FRAME_MS);
        float[] cut = new float[31];
        for (int i = 0; i < 31; i++) cut[i] = DspBands.cutoff(i, RATE);
        int[] of = DspBands.bandOfBin(cut, RATE, block);
        double step = RATE / block;
        for (int k = 1; k * step <= 20000; k++) {
            double hz = k * step;
            if (hz < 20 / EDGE) continue;
            T.eq(bandHolding(hz), of[k], "the step at " + Math.round(hz) + " Hz");
        }
    }
}
