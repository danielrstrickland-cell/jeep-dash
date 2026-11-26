package com.strickland.jeepdash;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.audiofx.DynamicsProcessing;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import com.strickland.jeepdash.core.DiagLog;
import com.strickland.jeepdash.core.DspBands;
import com.strickland.jeepdash.core.LiveValues;
import com.strickland.jeepdash.core.SoundAdapt;
import com.strickland.jeepdash.core.SoundPlan;
import com.strickland.jeepdash.core.SoundTune;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The tablet-side DSP: a 31-band equaliser and limiter on everything the tablet plays (Spotify included),
 * using Android's DynamicsProcessing effect on the output mix. It applies whatever {@link SoundPlan} adds up
 * from the saved settings and the Jeep's live speed, rpm and battery volts. Owned by the link service, which
 * ticks it twice a second, so it keeps working behind every screen.
 *
 * Whether a tablet allows an effect on the whole output mix is up to its maker; if this one refuses, the
 * reason goes in the diagnostics log and {@link #state()} says so, and nothing else in the app is affected.
 */
final class SoundEngine {
    private static final double STEP_DB = 0.25;           // a band is re-sent only when it moves this far
    private final SoundAdapt adapt = new SoundAdapt();
    private final Handler main = new Handler(Looper.getMainLooper());
    private DynamicsProcessing dp;
    private double[] sent;
    private double sentHeadroom;
    private double rate = 48000;                          // the output mix's sample rate, read when the effect opens
    private float frameMs = DspBands.FRAME_MS;            // the block length the open effect was asked for
    private boolean refused;
    private SoundAdapt.Scene lastScene;
    private volatile boolean bypass, convolving;
    private volatile String state = "off";
    private volatile double[] applied = new double[31];

    /** What the screen shows: "on", "off", "measuring" or why the tablet refused. */
    String state() { return state; }

    /** The gains in force right now, per band (all zero when off). */
    double[] applied() { return applied; }

    /** Calibrate measures the system untouched, so it switches the DSP out while it listens. */
    void setBypass(boolean on) { bypass = on; }

    /**
     * The lossless player is applying the room correction itself (as an exact filter), so this effect, which
     * also sits on the player's output, leaves the correction out and keeps only the adaptive parts.
     */
    void setConvolving(boolean on) { convolving = on; }

    /** The live values the engine needs polled, added to whatever the dash already reads. */
    static Set<String> withInputs(Set<String> ids, Context c, boolean porsche) {
        if (porsche) return ids;
        SharedPreferences p = SoundPrefs.of(c);
        boolean on = p.getBoolean(SoundPrefs.ENABLED, false);
        boolean learning = p.getBoolean(SoundPrefs.LEARNING, false);   // "Learn cabin noise" files noise by speed
        if (!on && !learning) return ids;
        Set<String> out = new LinkedHashSet<>(ids);
        if (learning || p.getBoolean(SoundPrefs.NOISE, true) || p.getBoolean(SoundPrefs.SCENE_EQ, true)) {
            out.add("speed");
            out.add("rpm");
        }
        if (on && p.getBoolean(SoundPrefs.VOLT, true)) out.add("batt_v");
        return out;
    }

    private static double value(LiveValues v, String id, long now) {
        Double d = v == null ? null : v.get(id, now);
        return d == null ? 0 : d;
    }

    /** Works out and applies the gains for this moment. Safe to call from any thread. */
    synchronized void tick(Context c, LiveValues v, long now) {
        SharedPreferences p = SoundPrefs.of(c);
        SoundPlan.Settings s = SoundPrefs.settings(p);
        if (convolving) s.correction = null;
        if (bypass || !s.enabled) {
            release();
            state = bypass ? "measuring" : "off";
            return;
        }
        SoundPlan.Live l = new SoundPlan.Live();
        l.mph = value(v, "speed", now);
        l.rpm = value(v, "rpm", now);
        l.volts = value(v, "batt_v", now);
        l.scene = adapt.scene(l.mph);
        SoundAdapt.AutoMode mode = SoundPrefs.autoMode(p);
        l.mayAdjust = mode.mayAdjust(l.mph);
        if (lastScene != null && l.scene != lastScene && mode.showsNote() && s.sceneEq) {
            final String text = "Sound: " + l.scene.name().toLowerCase(Locale.US) + " tune";
            final Context app = c.getApplicationContext();
            main.post(() -> Toast.makeText(app, text, Toast.LENGTH_SHORT).show());
        }
        lastScene = l.scene;
        apply(SoundPlan.gains(s, l), c, DspBands.frameMs(s.movie));
    }

    /** The sample rate the tablet mixes its output at; 48 kHz if it will not say. */
    private static double outputRate(Context c) {
        try {
            AudioManager am = c.getSystemService(AudioManager.class);
            String r = am == null ? null : am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
            double v = r == null ? 0 : Double.parseDouble(r);
            return v >= 8000 ? v : 48000;
        } catch (RuntimeException e) {
            return 48000;
        }
    }

    private void apply(SoundPlan.Gains g, Context c, float wantedFrameMs) {
        if (Build.VERSION.SDK_INT < 28) {
            if (!refused) Diag.error(DiagLog.DSP, "Tablet DSP needs Android 9 or newer.");
            refused = true;
            state = "not available on this Android version";
            return;
        }
        if (refused) return;
        try {
            if (dp != null && frameMs != wantedFrameMs) release();   // music and movie modes use different blocks
            if (dp == null) open(g.bandDb.length, outputRate(c), wantedFrameMs);
            boolean fresh = sent == null;
            for (int i = 0; i < g.bandDb.length; i++) {
                if (sent != null && Math.abs(sent[i] - g.bandDb[i]) < STEP_DB) continue;
                dp.setPreEqBandAllChannelsTo(i,
                        new DynamicsProcessing.EqBand(true, DspBands.cutoff(i, rate, frameMs), (float) g.bandDb[i]));
                if (sent == null) sent = new double[g.bandDb.length];
                sent[i] = g.bandDb[i];
            }
            if (Math.abs(sentHeadroom - g.headroomDb) >= STEP_DB) {
                dp.setInputGainAllChannelsTo((float) g.headroomDb);
                sentHeadroom = g.headroomDb;
            }
            applied = g.bandDb.clone();
            state = "on";
            if (fresh) Diag.info(DiagLog.DSP, "Gains now (dB, 20 Hz to 20 kHz): " + SoundPlan.toText(g.bandDb)
                    + "; whole signal " + Math.round(g.headroomDb * 10) / 10.0 + " dB.");
        } catch (RuntimeException e) {
            Diag.error(DiagLog.DSP, "Tablet DSP refused by this tablet: " + e);
            refused = true;
            release();
            state = "refused by this tablet (see diagnostics)";
        }
    }

    private void open(int bands, double outputRate, float wantedFrameMs) {
        rate = outputRate;
        frameMs = wantedFrameMs;
        // a long block: at the effect's own 10 ms it cannot tell the bass bands apart (see DspBands)
        DynamicsProcessing.Config cfg = new DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                true, bands, false, 0, false, 0, true)
                .setPreferredFrameDuration(frameMs).build();
        dp = new DynamicsProcessing(0, 0, cfg);               // session 0: everything the tablet plays
        DynamicsProcessing.Eq eq = new DynamicsProcessing.Eq(true, true, bands);
        for (int i = 0; i < bands; i++) {
            eq.getBand(i).setCutoffFrequency(DspBands.cutoff(i, rate, frameMs));
            eq.getBand(i).setGain(0);
        }
        dp.setPreEqAllChannelsTo(eq);
        // a safety limiter just under full scale, in case a boost still overshoots the headroom
        dp.setLimiterAllChannelsTo(new DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f));
        dp.setEnabled(true);
        sent = null;
        sentHeadroom = 0;
        Diag.info(DiagLog.DSP, "Tablet DSP opened: " + bands + " bands on the output mix, "
                + DspBands.blockFor(rate, frameMs) + "-sample blocks at " + Math.round(rate) + " Hz.");
    }

    /** Lets the tablet's sound go back to untouched. */
    synchronized void release() {
        if (dp != null) {
            try {
                dp.setEnabled(false);
                dp.release();
            } catch (RuntimeException ignored) { }
            dp = null;
        }
        sent = null;
        sentHeadroom = 0;
        applied = new double[31];
        if (!refused) state = "off";
    }

    /** Lets a refused effect be tried again (the owner switched the DSP off and on). */
    synchronized void retry() { refused = false; }
}
