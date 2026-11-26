package com.strickland.jeepdash;

import android.content.Context;
import android.content.SharedPreferences;
import com.strickland.jeepdash.core.NoiseProfile;
import com.strickland.jeepdash.core.SoundAdapt;
import com.strickland.jeepdash.core.SoundPlan;
import com.strickland.jeepdash.core.SoundTune;

/**
 * The Sound hub's saved settings (their own "sound" preferences file) and the one place they are turned into
 * what the tablet DSP should do. Calibrate stores what the mic measured, not the correction, so changing the
 * target re-aims the correction without measuring again.
 */
final class SoundPrefs {
    static final String ENABLED = "enabled", TARGET = "target", BASS = "custom_bass", TREBLE = "custom_treble",
            SCENE_EQ = "scene_eq", NOISE = "noise_comp", STRENGTH = "noise_strength", VOLT = "volt_safe",
            PORT = "port_guard", TUNE = "tune_hz", DROP = "bass_drop", AUTO = "auto_mode",
            MEASURED = "measured", VALID_LO = "valid_lo", VALID_HI = "valid_hi",
            MIC_USB = "mic_usb", MIC_CAL = "mic_cal", PEAK_BEST = "peak_best",
            NOISE_PROFILE = "noise_profile", LEARNING = "noise_learning", MODE = "listen_mode",
            STEREO_ADDR = "stereo_addr", STEREO_NAME = "stereo_name",
            PLAYER_TREE = "player_tree", PLAYER_FILTER = "player_filter";
    /** The settings a scene snapshot carries. */
    private static final String[] SCENE_KEYS = {ENABLED, TARGET, BASS, TREBLE, SCENE_EQ, NOISE, STRENGTH, VOLT, PORT, DROP, MODE};
    static final String[] SCENE_NAMES = {"Solo drive", "Full car", "Bass demo"};
    static final double MAX_BOOST = 6, MAX_CUT = 12;

    private SoundPrefs() { }

    static SharedPreferences of(Context c) { return c.getSharedPreferences("sound", Context.MODE_PRIVATE); }

    static SoundTune.Target target(SharedPreferences p) {
        try {
            return SoundTune.Target.valueOf(p.getString(TARGET, "HARMAN"));
        } catch (IllegalArgumentException e) {
            return SoundTune.Target.HARMAN;
        }
    }

    static SoundAdapt.AutoMode autoMode(SharedPreferences p) { return SoundAdapt.AutoMode.of(p.getString(AUTO, null)); }

    /** The chosen target at each band centre. */
    static double[] targetBands(SharedPreferences p) {
        SoundTune.Target t = target(p);
        if (t == SoundTune.Target.CUSTOM)
            return SoundTune.customBands(p.getInt(BASS, 7), p.getInt(TREBLE, -6));
        return SoundTune.targetBands(t);
    }

    /**
     * The correction toward the chosen target from the stored measurement, or null before Calibrate has run.
     * Bands outside the range the mic could be trusted over are left alone.
     */
    static double[] correction(SharedPreferences p) {
        double[] measured = SoundPlan.fromText(p.getString(MEASURED, null));
        if (measured == null) return null;
        double[] c = SoundTune.correction(measured, targetBands(p), MAX_BOOST, MAX_CUT);
        int lo = p.getInt(VALID_LO, 0), hi = p.getInt(VALID_HI, c.length - 1);
        for (int i = 0; i < c.length; i++) if (i < lo || i > hi) c[i] = 0;
        return c;
    }

    /** The cabin's measured noise, or null before any has been learned. */
    static NoiseProfile noise(SharedPreferences p) { return NoiseProfile.fromText(p.getString(NOISE_PROFILE, null)); }

    static SoundPlan.Settings settings(SharedPreferences p) {
        SoundPlan.Settings s = new SoundPlan.Settings();
        s.enabled = p.getBoolean(ENABLED, false);
        s.correction = correction(p);
        s.sceneEq = p.getBoolean(SCENE_EQ, true);
        s.noiseComp = p.getBoolean(NOISE, true);
        s.noiseStrength = p.getInt(STRENGTH, 60) / 100.0;
        s.voltSafeSub = p.getBoolean(VOLT, true);
        s.portGuard = p.getBoolean(PORT, true);
        s.tuneHz = p.getFloat(TUNE, 0);
        s.bassDrop = p.getBoolean(DROP, false);
        s.noise = noise(p);
        s.movie = "MOVIE".equals(p.getString(MODE, "MUSIC"));
        return s;
    }

    // ---- scenes: a snapshot of the tunable settings, saved under a slot ----

    static void saveScene(SharedPreferences p, int slot) {
        StringBuilder b = new StringBuilder();
        for (String k : SCENE_KEYS) {
            Object v = p.getAll().get(k);
            if (v == null) continue;
            String type = v instanceof Boolean ? "b" : v instanceof Integer ? "i" : "s";
            b.append(k).append('=').append(type).append(v).append(';');
        }
        p.edit().putString("scene" + slot, b.toString()).apply();
    }

    static boolean hasScene(SharedPreferences p, int slot) { return p.getString("scene" + slot, null) != null; }

    /** Puts a saved scene's settings back; false if that slot was never saved. */
    static boolean loadScene(SharedPreferences p, int slot) {
        String text = p.getString("scene" + slot, null);
        if (text == null) return false;
        SharedPreferences.Editor e = p.edit();
        for (String part : text.split(";")) {
            int eq = part.indexOf('=');
            if (eq < 1 || eq + 1 >= part.length()) continue;
            String k = part.substring(0, eq), v = part.substring(eq + 2);
            try {
                switch (part.charAt(eq + 1)) {
                    case 'b': e.putBoolean(k, Boolean.parseBoolean(v)); break;
                    case 'i': e.putInt(k, Integer.parseInt(v)); break;
                    default: e.putString(k, v);
                }
            } catch (NumberFormatException ignored) { }
        }
        e.apply();
        return true;
    }
}
