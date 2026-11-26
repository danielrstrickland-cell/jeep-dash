package com.strickland.jeepdash.core;

/** The tuning sums: band centres, folding a spectrum into bands, targets, the correction, and mic cal files. */
public class SoundTuneTest {

    public static void bandCentresSpanTheAudioRange() {
        double[] c = SoundTune.bandCentres();
        T.eq(31, c.length, "31 third-octave bands");
        T.near(19.7, c[0], 0.1, "starts near 20 Hz");
        T.near(1000, c[17], 1e-6, "1 kHz is a centre");
        T.near(20159, c[30], 1, "ends near 20 kHz");
    }

    public static void aToneLandsInItsBand() {
        int n = 8192;
        double rate = 48000;
        double[] mag = new double[n / 2];
        mag[(int) Math.round(1000 / (rate / n))] = 1;          // a 1 kHz tone
        double[] b = SoundTune.bandLevelsDb(mag, rate, n);
        T.near(0, b[17], 0.01, "the 1 kHz band holds the tone at 0 dB");
        T.near(-120, b[10], 1e-9, "a silent band reads -120");
        T.near(-120, b[25], 1e-9, "and so does another");
    }

    public static void harmanTargetShape() {
        T.near(7, SoundTune.targetDb(SoundTune.Target.HARMAN, 40), 1e-9, "bass shelf");
        T.near(0, SoundTune.targetDb(SoundTune.Target.HARMAN, 500), 1e-9, "level mids");
        T.near(-6, SoundTune.targetDb(SoundTune.Target.HARMAN, 20000), 1e-9, "eased treble");
        double mid = SoundTune.targetDb(SoundTune.Target.HARMAN, 110);
        T.ok(mid > 0 && mid < 7, "shelf eases between 60 and 200 Hz");
        T.near(0, SoundTune.targetDb(SoundTune.Target.FLAT, 40), 1e-9, "flat is flat");
    }

    public static void correctionIgnoresOverallLevel() {
        double[] target = SoundTune.targetBands(SoundTune.Target.FLAT);
        double[] loud = new double[31];
        java.util.Arrays.fill(loud, 23.0);                       // a flat measurement, just louder
        for (double g : SoundTune.correction(loud, target, 6, 12)) T.near(0, g, 1e-9, "nothing to correct");
    }

    public static void correctionCutsAPeakAndLimitsBoost() {
        double[] target = SoundTune.targetBands(SoundTune.Target.FLAT);
        double[] m = new double[31];
        m[5] = 9; m[6] = 9; m[7] = 9;                            // a 9 dB hump around 80 Hz
        m[24] = -30; m[25] = -30; m[26] = -30;                   // a deep hole in the treble
        double[] g = SoundTune.correction(m, target, 6, 12);
        T.near(-9, g[6], 1e-9, "the hump is cut by its height");
        T.near(6, g[25], 1e-9, "the hole is only boosted to the limit");
        T.near(0, g[15], 1e-9, "untouched bands stay put");
    }

    public static void curveReadsBetweenBands() {
        double[] g = new double[31];
        g[17] = 4;                                               // +4 at 1 kHz only
        T.near(4, SoundTune.at(g, 1000), 1e-9, "on a centre");
        double between = SoundTune.at(g, 1120);
        T.ok(between > 0 && between < 4, "between centres it is in between");
        T.near(0, SoundTune.at(g, 5), 1e-9, "flat past the low end");
    }

    public static void micCalFileParsesAndApplies() {
        String file = "\"Sens Factor =-1.2dB, SERNO: 7001234\"\n* comment\n20.0\t-2.0\n1000.0\t0.0\n20000.0, 3.0\nbad line\n";
        SoundTune.MicCal cal = SoundTune.parseMicCal(file);
        T.eq(3, cal.hz.length, "three points, header and junk skipped");
        T.near(-2, cal.at(10), 1e-9, "held below the first point");
        T.near(0, cal.at(1000), 1e-9, "on a point");
        T.near(1.5, cal.at(10500), 1e-9, "between points");
        double[] flat = new double[31];
        double[] fixed = SoundTune.applyMicCal(flat, cal);
        T.near(0, fixed[17], 1e-9, "1 kHz unchanged");
        T.ok(fixed[0] > 1.9, "the mic's bass droop is added back");
        T.eq(0, SoundTune.parseMicCal(null).hz.length, "no file, no points");
    }

    /** What the tablet's own mic heard of the tablet's own speakers (the owner's log, build 79). */
    private static final double[] TABLET_SPEAKERS = {-86.15, -86.15, -85.56, -85.56, -84.16, -83.86, -86.22, -73.47,
            -63.29, -73.77, -71.32, -68.39, -64.05, -63.46, -59.88, -56.79, -55.91, -55.81, -49.17, -50.19, -47.27,
            -49.93, -46.53, -49.64, -52.15, -45.23, -45.97, -46.29, -51.19, -57.6, -58.17};

    public static void smallSpeakersAreLevelledOnWhatTheyPlayAndNotBoostedBelowIt() {
        double[] target = SoundTune.targetBands(SoundTune.Target.HARMAN);
        int[] reach = SoundTune.reach(TABLET_SPEAKERS, target);
        T.near(1250, SoundTune.bandCentres()[reach[0]], 20, "these speakers start at about 1.25 kHz");
        double[] c = SoundTune.correction(TABLET_SPEAKERS, target, 6, 12);
        for (int i = 0; i < reach[0]; i++)
            T.near(0, c[i], 1e-9, "no boost at " + Math.round(SoundTune.bandCentres()[i]) + " Hz, below what they play");
        double top = 0, bottom = 0;
        for (int i = 0; i <= 28; i++) {                                 // the built-in mic is trusted up to 12.5 kHz
            top = Math.max(top, c[i]);
            bottom = Math.min(bottom, c[i]);
        }
        T.ok(top <= 2, "nothing much to boost in what they do play, so little headroom is spent: " + top);
        T.ok(bottom >= -8, "and the treble is trimmed, not buried (build 79 took 12 dB off): " + bottom);
        T.ok(c[26] < -3, "the Harman target still eases the top down: " + c[26]);
    }

    public static void aFullRangeSystemWithHeavyBassIsStillLevelledOnItsMids() {
        double[] target = SoundTune.targetBands(SoundTune.Target.FLAT);
        double[] m = new double[31];
        for (int i = 0; i <= 7; i++) m[i] = 10;                         // a big sub: 10 dB hot up to 100 Hz
        int[] reach = SoundTune.reach(m, target);
        T.eq(0, reach[0], "it plays from the bottom");
        T.eq(30, reach[1], "to the top");
        double[] c = SoundTune.correction(m, target, 6, 12);
        T.near(-10, c[3], 1e-9, "the bass is brought down to the mids");
        T.near(0, c[17], 1e-9, "the mids are the reference and stay put");
    }
}
