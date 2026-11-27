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
        T.near(3, g[25], 1e-9, "the hole is above the bass, so it only gets the gentle limit");
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
        T.ok(bottom >= -3, "and the treble is trimmed by 3 dB at most (build 79 took 12 dB off): " + bottom);
        T.ok(c[26] < 0, "the Harman target still eases the top down: " + c[26]);
    }

    public static void correctionStrengthScalesTheWholeCorrection() {
        double[] c = {6, -12, 0, 3};
        double[] half = SoundTune.scaled(c, 0.5);
        T.near(3, half[0], 1e-9, "half the boost");
        T.near(-6, half[1], 1e-9, "half the cut");
        T.near(0, SoundTune.scaled(c, 0)[1], 1e-9, "none at 0");
        T.near(-12, SoundTune.scaled(c, 4)[1], 1e-9, "never more than the full correction");
        T.ok(SoundTune.scaled(null, 0.5) == null, "no correction stays no correction");
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

    /** What Calibrate measured in the owner's Jeep with the tablet's own mic (build 94 log), 20 Hz to 20 kHz. */
    static final double[] JEEP = {-45.51, -45.51, -38.39, -38.39, -33.96, -34.41, -36.05, -34.69, -34.43, -35.76, -36.79,
            -32.4, -37.62, -33.02, -43.29, -47.14, -46.1, -45.83, -38.93, -39.99, -43.51, -43.97, -42.54, -41.72, -38.01,
            -37.22, -35.69, -27.8, -23.93, -38.32, -43.97};

    public static void aboveTheBassOnlyTheBroadShapeIsCorrected() {
        double[] target = SoundTune.targetBands(SoundTune.Target.FLAT);
        double[] m = new double[31];
        m[20] = 9;                                                      // one narrow 9 dB peak at 2 kHz
        double[] g = SoundTune.correction(m, target, 6, 12);
        T.ok(g[20] < 0 && g[20] > -2, "a narrow peak up there is barely touched, not notched out: " + g[20]);
        double[] bright = new double[31];
        for (int i = 21; i < 31; i++) bright[i] = 8;                    // everything above 2 kHz 8 dB hot
        double[] t = SoundTune.correction(bright, target, 6, 12);
        T.near(-3, t[26], 1e-9, "a broad excess is eased by the gentle limit, no more");
    }

    public static void theBassIsStillCorrectedInFull() {
        double[] target = SoundTune.targetBands(SoundTune.Target.FLAT);
        double[] m = new double[31];
        m[5] = 10; m[6] = 10; m[7] = 10;                                // a 10 dB cabin boom around 80 Hz
        T.near(-10, SoundTune.correction(m, target, 6, 12)[6], 1e-9, "a bass peak is cut by its full height");
    }

    public static void theJeepKeepsItsTreble() {
        double[] c = SoundTune.bandCentres();
        double[] fix = SoundTune.fadedAbove(SoundTune.correction(JEEP, SoundTune.targetBands(SoundTune.Target.HARMAN), 6, 12),
                SoundTune.BUILT_IN_TOP_HZ);
        for (int i = 0; i < 31; i++) {
            if (c[i] >= 600) T.ok(Math.abs(fix[i]) <= 3 + 1e-9, "no more than 3 dB either way at " + Math.round(c[i]) + " Hz: " + fix[i]);
            if (c[i] >= 7900) T.near(0, fix[i], 1e-9, "nothing at all where the tablet's mic cannot be trusted, " + Math.round(c[i]) + " Hz");
        }
        T.ok(fix[10] < -3, "the bass that sits above the target is still brought down: " + fix[10]);
    }

    public static void aCorrectionFadesOutBelowTheTopOfWhatTheMicCanBeTrustedOver() {
        double[] flat = new double[31];
        java.util.Arrays.fill(flat, -6.0);
        double[] f = SoundTune.fadedAbove(flat, 8000);
        T.near(-6, f[23], 1e-9, "whole at 4 kHz, an octave below the top");
        T.ok(f[24] > -6 && f[24] < f[25] && f[25] < 0, "easing out through 5 and 6.3 kHz: " + f[24] + ", " + f[25]);
        T.near(0, f[26], 1e-9, "gone by 8 kHz");
        T.near(0, f[30], 1e-9, "and above");
        T.ok(SoundTune.fadedAbove(null, 8000) == null, "no correction stays no correction");
    }

    public static void theCabinsBassZoneRunsTo300Hz() {
        double[] target = SoundTune.targetBands(SoundTune.Target.FLAT);
        double[] m = new double[31];
        m[10] = 10; m[11] = 10; m[12] = 10;                             // a 10 dB hump around 250 Hz
        // the 315 Hz band is in the mids the level is set from, so the reference rises 1 dB: a full cut is 9 dB
        T.near(-9, SoundTune.correction(m, target, 6, 12)[11], 1e-9, "250 Hz is still the bass zone: cut in full");
    }

    public static void aBandTheCorrectionMadeWorseIsFound() {
        double[] target = SoundTune.targetBands(SoundTune.Target.FLAT);
        double[] before = new double[31], after = new double[31];
        before[8] = 6;                                                   // 125 Hz was 6 dB hot
        after[8] = 0.5;                                                  // and is now nearly right
        before[20] = 1;                                                  // 2 kHz was close
        after[20] = 5;                                                   // and is now 5 dB out
        boolean[] worse = SoundTune.worse(before, after, target, 1.5);
        T.ok(!worse[8], "the band it fixed is kept");
        T.ok(worse[20], "the band it spoiled is found");
        T.ok(!worse[25], "an untouched band is left alone");
        double[] fix = {1, 2, 3};
        T.ok(java.util.Arrays.equals(new double[]{1, 0, 3}, SoundTune.without(fix, new boolean[]{false, true, false})), "found bands are left out");
        T.ok(SoundTune.without(null, new boolean[3]) == null, "no correction stays none");
        T.ok(java.util.Arrays.equals(new double[]{1, 2, 3}, SoundTune.without(fix, null)), "no check, nothing left out");
    }
}
