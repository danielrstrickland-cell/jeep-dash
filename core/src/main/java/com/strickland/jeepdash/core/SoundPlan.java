package com.strickland.jeepdash.core;

/**
 * Adds up everything the Sound hub wants done to the tablet's output into one gain per third-octave band:
 * the room correction from Calibrate, the scene's EQ, noise compensation, the voltage-safe sub trim, the port
 * guard and Bass drop. The audio code only ever applies this result, so what the tablet plays is decided in
 * one tested place.
 */
public final class SoundPlan {

    /** What the owner has switched on. */
    public static final class Settings {
        public boolean enabled = true;          // the whole tablet DSP
        public double[] correction;             // per band, from Calibrate; null until it has run
        public boolean sceneEq = true, noiseComp = true, voltSafeSub = true, portGuard = true, bassDrop;
        public double noiseStrength = 0.6;      // 0..1
        public double tuneHz;                   // the ported box's tuning; 0 = not known
        public boolean movie;                   // a movie or a show is on: lift voices
        public NoiseProfile noise;              // the cabin's measured noise; null or not ready = starting model
    }

    /** What the Jeep is doing right now; zeros when the dongle is not linked. */
    public static final class Live {
        public double mph, rpm, volts;
        public SoundAdapt.Scene scene = SoundAdapt.Scene.CRUISE;
        public boolean mayAdjust = true;        // false: the auto mode says hands off while moving
    }

    static final double SUB_TOP_HZ = 120, MAX_DB = 12, MIN_DB = -24, DROP_DB = 6;

    /** The result: a gain per band and how far to pull the whole signal down first so boosts cannot clip. */
    public static final class Gains {
        public final double[] bandDb;
        public final double headroomDb;
        Gains(double[] bandDb, double headroomDb) { this.bandDb = bandDb; this.headroomDb = headroomDb; }
    }

    /** Bass drop's own lift: 6 dB up to 80 Hz, easing to nothing by 160 Hz. */
    static double bassDropDb(double hz) {
        if (hz <= 80) return DROP_DB;
        if (hz >= 160) return 0;
        return DROP_DB * (1 - Math.log(hz / 80.0) / Math.log(2));
    }

    public static Gains gains(Settings s, Live l) {
        double[] c = SoundTune.bandCentres();
        double[] out = new double[c.length];
        if (!s.enabled) return new Gains(out, 0);
        double top = 0;
        boolean compensate = s.noiseComp && l.mayAdjust;
        double[] learned = compensate && s.noise != null && s.noise.ready()
                ? s.noise.boostBands(l.mph, s.noiseStrength) : null;
        for (int i = 0; i < c.length; i++) {
            double hz = c[i], g = 0;
            if (s.correction != null && i < s.correction.length) g += s.correction[i];
            if (s.bassDrop) g += bassDropDb(hz);
            if (s.movie) g += SoundAdapt.voiceLiftDb(hz);
            if (s.portGuard) g += Math.max(MIN_DB, SoundAdapt.portGuardDb(hz, s.tuneHz));   // protection: always on
            if (l.mayAdjust && s.sceneEq) g += SoundAdapt.sceneOffsetDb(l.scene, hz);
            if (learned != null) g += Math.max(learned[i], SoundAdapt.engineBoostDb(hz, l.rpm, s.noiseStrength));
            else if (compensate) g += SoundAdapt.noiseBoostDb(hz, l.mph, l.rpm, s.noiseStrength);
            if (s.voltSafeSub && hz <= SUB_TOP_HZ) g += SoundAdapt.subTrimDb(l.volts);     // protection: always on
            out[i] = SoundAdapt.clamp(g, MIN_DB, MAX_DB);
            top = Math.max(top, out[i]);
        }
        return new Gains(out, -top);
    }

    /** Band gains as text for storing, and back; a bad or missing text reads as null (nothing stored). */
    public static String toText(double[] bandDb) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < bandDb.length; i++) {
            if (i > 0) b.append(',');
            b.append(Math.round(bandDb[i] * 100) / 100.0);
        }
        return b.toString();
    }

    public static double[] fromText(String text) {
        if (text == null || text.isEmpty()) return null;
        String[] p = text.split(",");
        double[] out = new double[p.length];
        try {
            for (int i = 0; i < p.length; i++) out[i] = Double.parseDouble(p[i].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    private SoundPlan() { }
}
