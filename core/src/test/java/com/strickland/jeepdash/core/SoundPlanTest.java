package com.strickland.jeepdash.core;

/** The one place the tablet DSP's gains are added up. */
public class SoundPlanTest {

    private static int band(double hz) {
        double[] c = SoundTune.bandCentres();
        int best = 0;
        for (int i = 1; i < c.length; i++) if (Math.abs(c[i] - hz) < Math.abs(c[best] - hz)) best = i;
        return best;
    }

    public static void offMeansNoGains() {
        SoundPlan.Settings s = new SoundPlan.Settings();
        s.enabled = false;
        s.bassDrop = true;
        SoundPlan.Gains g = SoundPlan.gains(s, new SoundPlan.Live());
        for (double d : g.bandDb) T.near(0, d, 1e-9, "nothing applied");
        T.near(0, g.headroomDb, 1e-9, "no headroom needed");
    }

    public static void parkedCruiseWithNothingStoredIsFlat() {
        SoundPlan.Gains g = SoundPlan.gains(new SoundPlan.Settings(), new SoundPlan.Live());
        for (double d : g.bandDb) T.near(0, d, 1e-9, "flat");
    }

    public static void correctionAndHeadroom() {
        SoundPlan.Settings s = new SoundPlan.Settings();
        s.correction = new double[31];
        s.correction[band(1000)] = 4;
        s.correction[band(80)] = -5;
        SoundPlan.Gains g = SoundPlan.gains(s, new SoundPlan.Live());
        T.near(4, g.bandDb[band(1000)], 1e-9, "boost carried through");
        T.near(-5, g.bandDb[band(80)], 1e-9, "cut carried through");
        T.near(-4, g.headroomDb, 1e-9, "signal pulled down by the biggest boost");
    }

    public static void highwayAddsNoiseBoostUnlessHandsOff() {
        SoundPlan.Settings s = new SoundPlan.Settings();
        s.noiseStrength = 1;
        SoundPlan.Live l = new SoundPlan.Live();
        l.mph = 80;
        l.scene = SoundAdapt.Scene.HIGHWAY;
        T.near(6, SoundPlan.gains(s, l).bandDb[band(100)], 1e-9, "full bass boost at 80 mph");
        l.mayAdjust = false;
        T.near(0, SoundPlan.gains(s, l).bandDb[band(100)], 1e-9, "parked-only mode leaves it alone while moving");
    }

    public static void protectionAppliesEvenWhenHandsOff() {
        SoundPlan.Settings s = new SoundPlan.Settings();
        s.tuneHz = 34;
        SoundPlan.Live l = new SoundPlan.Live();
        l.mayAdjust = false;
        l.volts = 11.8;
        SoundPlan.Gains g = SoundPlan.gains(s, l);
        T.near(-3, g.bandDb[band(63)], 1e-9, "sub trimmed on a low battery");
        T.near(0, g.bandDb[band(500)], 1e-9, "mids untouched by the sub trim");
        T.ok(g.bandDb[band(20)] < -10, "below the port's tuning is pulled down hard");
    }

    public static void idleSceneCutsTheBoom() {
        SoundPlan.Live l = new SoundPlan.Live();
        l.scene = SoundAdapt.Scene.IDLE;
        T.near(-3, SoundPlan.gains(new SoundPlan.Settings(), l).bandDb[band(50)], 0.05, "50 Hz cut at idle");
    }

    public static void bassDropAndTheCeiling() {
        SoundPlan.Settings s = new SoundPlan.Settings();
        s.bassDrop = true;
        s.correction = new double[31];
        java.util.Arrays.fill(s.correction, 6);
        s.noiseStrength = 1;
        SoundPlan.Live l = new SoundPlan.Live();
        l.mph = 80;
        SoundPlan.Gains g = SoundPlan.gains(s, l);
        T.near(12, g.bandDb[band(50)], 1e-9, "6 + 6 + 6 is held at the 12 dB ceiling");
        T.near(-12, g.headroomDb, 1e-9, "and the headroom matches");
        T.near(6, SoundPlan.bassDropDb(40), 1e-9, "full lift low down");
        T.near(0, SoundPlan.bassDropDb(200), 1e-9, "none above 160 Hz");
    }

    public static void textRoundTrip() {
        double[] g = {0, 1.5, -3.25, 12};
        double[] back = SoundPlan.fromText(SoundPlan.toText(g));
        T.eq(4, back.length, "same length");
        T.near(-3.25, back[2], 1e-9, "values survive");
        T.ok(SoundPlan.fromText("") == null, "empty is nothing");
        T.ok(SoundPlan.fromText("1,x,3") == null, "junk is nothing");
    }

    public static void customTargetAndSpectrum() {
        double[] t = SoundTune.customBands(10, -3);
        T.near(10, t[band(40)], 1e-9, "custom bass shelf");
        T.near(-3, t[30], 0.05, "custom treble end");
        int n = 4096;
        short[] tone = new short[n];
        for (int i = 0; i < n; i++) tone[i] = (short) (16384 * Math.sin(2 * Math.PI * 1000 * i / 48000.0));
        double[] bands = SoundTune.bandLevelsDb(SoundTune.magnitudes(tone), 48000, n);
        int loudest = 0;
        for (int i = 1; i < bands.length; i++) if (bands[i] > bands[loudest]) loudest = i;
        T.eq(band(1000), loudest, "a 1 kHz tone is loudest in the 1 kHz band");
        T.ok(bands[loudest] - bands[band(4000)] > 40, "and far above a distant band");
    }
}
