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

    public static void movieBlocksKeepSoundInStepWithThePicture() {
        double late = DspBands.blockFor(RATE, DspBands.MOVIE_FRAME_MS) / RATE;
        T.ok(late <= 0.025, "sound no more than 25 ms behind the picture, but it is " + late * 1000 + " ms");
        T.ok(DspBands.frameMs(true) < DspBands.frameMs(false), "movie mode uses the shorter block");
    }

    private static void everyStepSitsInItsBand(float frameMs) {
        int block = DspBands.blockFor(RATE, frameMs);
        float[] cut = new float[31];
        for (int i = 0; i < 31; i++) {
            cut[i] = DspBands.cutoff(i, RATE, frameMs);
            T.ok(cut[i] > 0 && (i == 0 || cut[i] > cut[i - 1]), "cutoffs rise from above zero, band " + i);
        }
        int[] of = DspBands.bandOfBin(cut, RATE, block);
        double step = RATE / block;
        for (int k = 1; k * step <= 20000; k++) {
            double hz = k * step;
            if (hz < 20 / EDGE) continue;
            T.eq(bandHolding(hz), of[k], "the step at " + Math.round(hz) + " Hz, " + frameMs + " ms blocks");
        }
    }

    public static void everyStepTakesTheGainOfTheBandItSitsIn() {
        everyStepSitsInItsBand(DspBands.FRAME_MS);
        everyStepSitsInItsBand(DspBands.MOVIE_FRAME_MS);
    }

    public static void theFineBandsFollowTheCurveWithoutSteps() {
        double[] c = SoundTune.bandCentres();
        double[] f = DspBands.fineCentres();
        T.eq(DspBands.FINE, f.length, "a band every ninth of an octave");
        T.near(20, f[0], 1, "from 20 Hz");
        T.near(20000, f[f.length - 1], 500, "to 20 kHz");
        double[] curve = new double[31];
        curve[7] = -9;                                                   // one 9 dB cut at 100 Hz
        double[] fine = DspBands.fine(curve);
        T.near(c[7], f[21], 0.01, "every third fine band sits on one of the 31");
        T.near(-9, fine[21], 1e-9, "the full cut at its own centre");
        for (int j = 0; j < f.length; j++) {
            if (j > 0) T.ok(Math.abs(fine[j] - fine[j - 1]) <= 3 + 1e-9, "no step bigger than a third of the band's: " + f[j]);
        }
        T.near(0, fine[0], 1e-9, "nothing far away");
        T.near(c.length, 31, 0, "the 31 bands stay the plan's");
    }

    public static void theFineCutoffsRiseAndCoverEveryStep() {
        double rate = 48000;
        float ms = DspBands.FRAME_MS;
        float[] cut = new float[DspBands.FINE];
        for (int j = 0; j < cut.length; j++) {
            cut[j] = DspBands.fineCutoff(j, rate, ms);
            if (j > 0) T.ok(cut[j] > cut[j - 1], "rising at " + j);
        }
        int block = DspBands.blockFor(rate, ms);
        int[] of = DspBands.bandOfBin(cut, rate, block);
        int top = (int) (16000.0 * block / rate);
        for (int k = 1; k <= top; k++) T.ok(of[k] >= 0, "bin " + k + " has a band");
        T.near(1000, DspBands.fineCentres()[of[(int) Math.round(1000.0 * block / rate)]], 40, "1 kHz takes the 1 kHz band's gain");
    }
}
