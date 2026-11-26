package com.strickland.jeepdash.core;

/**
 * The adaptive sound rules: what the Sound hub changes on its own as the Jeep's state changes. Pure sums on
 * values the dash already reads (speed, rpm, battery volts), giving decibel offsets for the tuner to apply.
 *
 * The noise figures here are a starting model — road noise rising with speed, engine noise with rpm, both
 * heaviest in the bass — used until the cabin's own noise has been measured into a {@link NoiseProfile}. Nothing here touches the stereo or the audio; it only answers "how many dB, at this pitch".
 */
public final class SoundAdapt {

    /** How hands-off the engine is while driving: the owner picks one from a drop-down. */
    public enum AutoMode {
        AUTO, AUTO_NOTE, PARKED_ONLY;

        public static AutoMode of(String name) {
            if (name != null) for (AutoMode m : values()) if (m.name().equals(name)) return m;
            return AUTO;
        }

        /** Whether an automatic change may be made at this road speed. */
        public boolean mayAdjust(double mph) { return this != PARKED_ONLY || mph < 1; }

        /** Whether each automatic change is flashed on screen. */
        public boolean showsNote() { return this == AUTO_NOTE; }
    }

    /** What the Jeep is doing, for scene-linked EQ. */
    public enum Scene { IDLE, CRUISE, HIGHWAY }

    static final double MAX_BOOST_DB = 6, IDLE_CUT_DB = 3;

    private Scene scene = Scene.IDLE;

    /**
     * The scene for this speed, with a gap between going up and coming down so it does not flap at a
     * threshold: moving from 5 mph (idle again under 2), highway from 55 (cruise again under 47).
     */
    public Scene scene(double mph) {
        switch (scene) {
            case IDLE:    if (mph >= 5) scene = mph >= 55 ? Scene.HIGHWAY : Scene.CRUISE; break;
            case CRUISE:  if (mph < 2) scene = Scene.IDLE; else if (mph >= 55) scene = Scene.HIGHWAY; break;
            default:      if (mph < 2) scene = Scene.IDLE; else if (mph < 47) scene = Scene.CRUISE; break;
        }
        return scene;
    }

    /** The scene's own EQ offset at a pitch: at idle, a cut centred on 50 Hz to take the boom out; else 0. */
    public static double sceneOffsetDb(Scene s, double hz) {
        if (s != Scene.IDLE || hz <= 0) return 0;
        double octaves = Math.log(hz / 50.0) / Math.log(2);
        return -IDLE_CUT_DB * Math.exp(-octaves * octaves / 0.5);
    }

    /** How much of the music a pitch loses to cabin noise, 0..1: all of it in the bass, little in the treble. */
    static double maskWeight(double hz) {
        if (hz <= 200) return 1;
        if (hz >= 6000) return 0.15;
        double t = Math.log(hz / 200.0) / Math.log(6000.0 / 200.0);
        return 1 - 0.85 * t;
    }

    /** How loud the engine alone is taken to be, 0..0.5: nothing under 1500 rpm, its most by 4000. */
    private static double engineLevel(double rpm) { return 0.5 * clamp((rpm - 1500) / 2500.0, 0, 1); }

    /**
     * The engine's own share of noise compensation, in dB at a pitch: what a revved engine earns with the
     * Jeep standing still. Kept apart so it still applies once road noise comes from a measured
     * {@link NoiseProfile}, which is learned at ordinary driving revs.
     */
    public static double engineBoostDb(double hz, double rpm, double strength) {
        return clamp(clamp(strength, 0, 1) * MAX_BOOST_DB * maskWeight(hz) * engineLevel(rpm), 0, MAX_BOOST_DB);
    }

    /**
     * Noise compensation: the boost in dB at a pitch for this speed and rpm. Strength is the owner's slider,
     * 0..1. Nothing under 20 mph and 1500 rpm; full road effect by 80 mph; never more than 6 dB.
     */
    public static double noiseBoostDb(double hz, double mph, double rpm, double strength) {
        double road = clamp((mph - 20) / 60.0, 0, 1);
        double level = Math.max(road, engineLevel(rpm));
        return clamp(clamp(strength, 0, 1) * MAX_BOOST_DB * maskWeight(hz) * level, 0, MAX_BOOST_DB);
    }

    /**
     * Voltage-safe sub: dB to take off the sub as the battery sags, so the sub amp is not pushed into
     * clipping on a low rail. Nothing at 13.2 V or more, sliding to -3 dB at 11.8 V, and -6 dB under 11 V.
     * A reading of 0 or less means no voltage is known, and nothing is taken off.
     */
    public static double subTrimDb(double volts) {
        if (volts <= 0 || volts >= 13.2) return 0;
        if (volts < 11.0) return -6;
        if (volts <= 11.8) return -3;
        return -3 * (13.2 - volts) / (13.2 - 11.8);
    }

    /**
     * Port guard: the subsonic high-pass for a ported box tuned at tuneHz. Below tuning the cone unloads, so
     * the filter sits just under it (85 %). 0 when the tuning is not known.
     */
    public static double subsonicHz(double tuneHz) { return tuneHz > 0 ? tuneHz * 0.85 : 0; }

    /** dB to pull a sub band down for the port guard: nothing at or above the filter, 24 dB per octave below. */
    public static double portGuardDb(double hz, double tuneHz) {
        double f = subsonicHz(tuneHz);
        if (f <= 0 || hz <= 0 || hz >= f) return 0;
        return 24 * Math.log(hz / f) / Math.log(2);
    }

    static final double VOICE_DB = 4, VOICE_HZ = 2500, VOICE_OCTAVES = 0.8;

    /**
     * Movie and TV mode's lift for voices, in dB at a pitch: 4 dB at 2.5 kHz, where speech is told apart,
     * easing off to nothing about two octaves either side.
     */
    public static double voiceLiftDb(double hz) {
        if (hz <= 0) return 0;
        double octaves = Math.log(hz / VOICE_HZ) / Math.log(2);
        return VOICE_DB * Math.exp(-octaves * octaves / (2 * VOICE_OCTAVES * VOICE_OCTAVES));
    }

    /**
     * Movie and TV levelling ("night mode"): above the threshold, loud sound is squeezed by the ratio, and
     * everything then gets the make-up gain, so speech comes up and explosions come down. Levels are the
     * averaged level of a moment of sound, in dB below full scale, as Android's effect measures it.
     */
    public static final float LEVEL_THRESHOLD_DB = -30, LEVEL_RATIO = 2.5f, LEVEL_MAKEUP_DB = 4,
            LEVEL_ATTACK_MS = 10, LEVEL_RELEASE_MS = 600;

    /** Where a level ends up with levelling on. */
    public static double levelledDb(double inDb) {
        double over = Math.max(0, inDb - LEVEL_THRESHOLD_DB);
        return inDb - over * (1 - 1.0 / LEVEL_RATIO) + LEVEL_MAKEUP_DB;
    }

    static double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
