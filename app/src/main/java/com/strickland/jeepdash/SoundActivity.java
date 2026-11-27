package com.strickland.jeepdash;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import com.strickland.jeepdash.core.BoxTune;
import com.strickland.jeepdash.core.DiagLog;
import com.strickland.jeepdash.core.KenwoodLink;
import com.strickland.jeepdash.core.NoiseProfile;
import com.strickland.jeepdash.core.ProfileRoute;
import com.strickland.jeepdash.core.SoundAdapt;
import com.strickland.jeepdash.core.SoundHelp;
import com.strickland.jeepdash.core.SoundPlan;
import com.strickland.jeepdash.core.SoundTune;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.Consumer;

/**
 * The Sound hub: one clean screen for everything about how the system sounds. It switches the tablet DSP
 * ({@link SoundEngine}) on and off, shows what the mic hears against the target, runs Calibrate (pink noise
 * out, mic in, correction stored), and holds the adaptive settings, the scenes and the diagnostics log. It
 * also learns the cabin's own noise for noise compensation, measures the sub box's tuning with a sweep, and
 * holds the Kenwood link (mute, volume, preset EQ).
 *
 * The DSP itself lives in the link service, so it carries on when this screen is closed; this screen only
 * changes saved settings and asks the engine to re-apply. The mic is open only while the screen is showing.
 */
public class SoundActivity extends Activity implements ServiceConnection {
    private static final int RATE = 48000, BLOCK = 4096, REQ_MIC = 3, REQ_CAL_FILE = 4, REQ_BT = 5;
    private static final double AMBIENT_S = 1.5, SETTLE_S = 0.8, MEASURE_S = 8;
    private static final int BG = Color.rgb(12, 16, 24), CARD = Color.rgb(22, 28, 40), LINE = Color.rgb(40, 50, 68),
            INK = Color.rgb(226, 232, 242), DIM = Color.rgb(138, 150, 170), GOOD = Color.rgb(96, 214, 150),
            WARN = Color.rgb(240, 150, 110);

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private LinkService service;
    private boolean bound;
    private volatile Thread mic;
    private volatile boolean calibrating, noiseOn, learning, boxTuning;
    private volatile String learnNote = "";
    private String shownProfile = "";                  // the sound profile this screen was built for
    /** True while this screen is in front: the link service does not swap sound profiles under it. */
    static volatile boolean showing;
    private volatile int learnKept, learnMusic, learnNoSpeed;      // why learning did or did not hear anything
    private volatile NoiseProfile profile;             // the cabin noise learned so far, or null
    private final StereoLink stereo = new StereoLink();
    private AudioManager audio;
    private TextView noiseState, boxState, huState;
    private Button learnButton;
    private EditText tuneField;
    private volatile double[] liveBands;
    private volatile double peakNow = -120;
    private RtaView graph;
    private TextView dspState, calState, loud;
    private float density;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!SoundProfiles.active(prefs).equals(shownProfile)) {     // it switched by itself: show the new one
                profile = SoundPrefs.noise(prefs);
                buildScreen();
            }
            SoundEngine e = service == null ? null : service.sound();
            graph.set(liveBands, SoundPrefs.targetBands(prefs), e == null ? null : e.applied());
            dspState.setText("Tablet DSP: " + (e == null ? "starting" : e.state()));
            noiseState.setText(noiseText());
            huState.setText(stereo.state());
            float best = prefs.getFloat(SoundPrefs.PEAK_BEST, -120);
            if (peakNow > best) {
                best = (float) peakNow;
                prefs.edit().putFloat(SoundPrefs.PEAK_BEST, best).apply();
            }
            loud.setText(String.format(Locale.US, "Loud-o-meter: now %.0f, best %.0f dBFS at the mic (not true SPL)",
                    peakNow, best));
            main.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = SoundPrefs.of(this);
        density = getResources().getDisplayMetrics().density;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        audio = getSystemService(AudioManager.class);
        SoundProfiles.ensure(prefs);
        profile = SoundPrefs.noise(prefs);
        prefs.edit().putBoolean(SoundPrefs.LEARNING, false).apply();    // learning only runs with this screen open
        buildScreen();
    }

    @Override
    protected void onStart() {
        super.onStart();
        showing = true;
        bound = bindService(new Intent(this, LinkService.class), this, Context.BIND_AUTO_CREATE);
        main.post(refresh);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startMic();
        else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
    }

    @Override
    protected void onStop() {
        showing = false;
        main.removeCallbacks(refresh);
        if (learning) stopLearning();
        mic = null;
        noiseOn = false;
        calibrating = false;
        boxTuning = false;
        stereo.close();
        if (service != null) service.sound().setBypass(false);
        if (bound) {
            unbindService(this);
            bound = false;
        }
        service = null;
        super.onStop();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_BT) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) connectStereo(false);
            else Toast.makeText(this, "Bluetooth was not allowed, so the stereo cannot be reached", Toast.LENGTH_LONG).show();
            return;
        }
        if (code != REQ_MIC) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startMic();
        else {
            Diag.warn(DiagLog.CAL, "Microphone permission refused: no live graph, no Calibrate.");
            calState.setText("Microphone not allowed, so the graph and Calibrate are off.");
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((LinkService.LocalBinder) binder).service();
        changed();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) { service = null; }

    /** A setting changed: have the engine re-apply now, not at its next tick. */
    private void changed() {
        LinkService s = service;
        if (s != null) s.soundChanged();
    }

    // ---------------- screen ----------------

    private TextView label(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    /** A card with its title and, beside it, the information button for that card's help topic. */
    private LinearLayout card(LinearLayout parent, String title, String topic) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(8 * density);
        bg.setStroke(Math.max(1, (int) density), LINE);
        c.setBackground(bg);
        int pad = (int) (14 * density);
        c.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (12 * density);
        parent.addView(c, lp);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = label(title, 15, INK);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(HelpButton.make(this, topic), new LinearLayout.LayoutParams(HelpButton.size(this), HelpButton.size(this)));
        c.addView(head);
        return c;
    }

    private Button button(String s, View.OnClickListener l) {
        Button x = new Button(this);
        x.setText(s);
        x.setAllCaps(false);
        x.setTextSize(13);
        x.setOnClickListener(l);
        return x;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(r);
        return r;
    }

    private void toggle(LinearLayout parent, String text, String key, boolean def) {
        Switch s = new Switch(this);
        s.setText(text);
        s.setTextColor(INK);
        s.setTextSize(14);
        s.setChecked(prefs.getBoolean(key, def));
        s.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(key, on).apply();
            if (SoundPrefs.ENABLED.equals(key) && on && service != null) service.sound().retry();
            changed();
        });
        parent.addView(s);
    }

    private void slider(LinearLayout parent, String text, String key, int min, int max, int def, String unit) {
        final TextView t = label("", 13, DIM);
        final int now = prefs.getInt(key, def);
        t.setText(text + ": " + now + unit);
        parent.addView(t);
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(now - min);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) {
                t.setText(text + ": " + (p + min) + unit);
                if (!user) return;
                prefs.edit().putInt(key, p + min).apply();
                changed();
            }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { }
        });
        parent.addView(bar);
    }

    private void picker(LinearLayout parent, String text, String[] shown, String[] stored, String key, String def) {
        parent.addView(label(text, 13, DIM));
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, shown);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(a);
        String cur = prefs.getString(key, def);
        for (int i = 0; i < stored.length; i++) if (stored[i].equals(cur)) sp.setSelection(i);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (stored[pos].equals(prefs.getString(key, def))) return;
                prefs.edit().putString(key, stored[pos]).apply();
                if (SoundPrefs.MIC_USB.equals(key) && mic != null) {      // reopen on the newly chosen mic
                    mic = null;
                    main.postDelayed(SoundActivity.this::startMic, 400);
                }
                changed();
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        parent.addView(sp);
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * density);
        col.setPadding(pad, pad, pad, pad);
        scroll.addView(col);

        TextView title = label("Sound", 22, INK);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);
        col.addView(label("Tuned for: Type R components, JL tweeters, Skar EVL-15 ported, PDR-V75 / SR 1Dk", 12, DIM));
        col.addView(label("Signal: tablet DSP  >  head unit  >  amps  >  speakers", 12, DIM));

        profileCard(col);

        LinearLayout dsp = card(col, "Tablet DSP", SoundHelp.DSP);
        toggle(dsp, "On (EQ and limiter on everything the tablet plays)", SoundPrefs.ENABLED, false);
        dspState = label("", 12, GOOD);
        dsp.addView(dspState);
        picker(dsp, "Listening to", new String[]{"Music", "Movie or TV show (voices lifted, sound in step with the picture)"},
                new String[]{"MUSIC", "MOVIE"}, SoundPrefs.MODE, "MUSIC");
        toggle(dsp, "Even out loud and quiet (Movie or TV only)", SoundPrefs.LEVEL, false);

        LinearLayout tune = card(col, "EQ and live RTA", SoundHelp.RTA);
        tune.addView(label("Bars: what the mic hears.  Yellow: target.  Green: gains applied.", 12, DIM));
        graph = new RtaView(this);
        tune.addView(graph, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (190 * density)));
        picker(tune, "Target curve", new String[]{"Harman", "Flat / reference", "Custom"},
                new String[]{"HARMAN", "FLAT", "CUSTOM"}, SoundPrefs.TARGET, "HARMAN");
        slider(tune, "Custom bass shelf", SoundPrefs.BASS, 0, 12, 7, " dB");
        slider(tune, "Custom treble at 20 kHz", SoundPrefs.TREBLE, -10, 0, -6, " dB");

        LinearLayout cal = card(col, "Calibrate and room correction", SoundHelp.CALIBRATE);
        cal.addView(label("Engine off or idling, windows up, tablet where your head is. Set a normal listening "
                + "volume. It plays about 10 seconds of noise and stores what the mic hears.", 12, DIM));
        picker(cal, "Microphone", new String[]{"Tablet's built-in mic", "USB measurement mic"},
                new String[]{"builtin", "usb"}, SoundPrefs.MIC_USB, "builtin");
        LinearLayout calRow = row(cal);
        calRow.addView(button("Calibrate", v -> askCalibrate()));
        calRow.addView(button("Load mic cal file", v -> pickCalFile()));
        calRow.addView(button("Clear", v -> {
            prefs.edit().remove(SoundPrefs.MEASURED).apply();
            calState.setText("Correction cleared.");
            changed();
        }));
        calState = label(prefs.getString(SoundPrefs.MEASURED, null) == null ? "Not calibrated yet."
                : "Calibrated. Correction follows the target above.", 12, GOOD);
        cal.addView(calState);
        slider(cal, "Correction strength", SoundPrefs.CORR, 0, 100, 100, " %");

        LinearLayout ad = card(col, "Adaptive engine", SoundHelp.ADAPTIVE);
        picker(ad, "While driving", new String[]{"Fully automatic", "Automatic, with a note on each change", "Parked only"},
                new String[]{"AUTO", "AUTO_NOTE", "PARKED_ONLY"}, SoundPrefs.AUTO, "AUTO");
        toggle(ad, "Scene-linked EQ (idle / cruise / highway)", SoundPrefs.SCENE_EQ, true);
        toggle(ad, "Noise compensation", SoundPrefs.NOISE, true);
        slider(ad, "Noise compensation strength", SoundPrefs.STRENGTH, 0, 100, 60, " %");
        noiseState = label(noiseText(), 12, GOOD);
        ad.addView(noiseState);
        LinearLayout noiseRow = row(ad);
        learnButton = button(learning ? "Stop learning" : "Learn cabin noise", v -> {
            if (learning) stopLearning();
            else askLearn();
        });
        noiseRow.addView(learnButton);
        noiseRow.addView(button("Forget", v -> askForgetNoise()));
        toggle(ad, "Voltage-safe sub", SoundPrefs.VOLT, true);
        toggle(ad, "Port / unloading guard", SoundPrefs.PORT, true);
        LinearLayout tuneRow = row(ad);
        final EditText hz = new EditText(this);
        tuneField = hz;
        hz.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        hz.setTextColor(INK);
        hz.setHintTextColor(DIM);
        hz.setHint("Box tuning, Hz");
        float saved = prefs.getFloat(SoundPrefs.TUNE, 0);
        if (saved > 0) hz.setText(String.valueOf(saved));
        tuneRow.addView(hz, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        tuneRow.addView(button("Set", v -> {
            float f = 0;
            try { f = Float.parseFloat(hz.getText().toString().trim()); } catch (NumberFormatException ignored) { }
            prefs.edit().putFloat(SoundPrefs.TUNE, f > 10 && f < 80 ? f : 0).apply();
            Toast.makeText(this, f > 10 && f < 80 ? "Port guard set under " + f + " Hz" : "Port guard off: no tuning set",
                    Toast.LENGTH_SHORT).show();
            changed();
        }));
        tuneRow.addView(button("Measure", v -> askBoxTune()));
        boxState = label("The port guard needs the box's tuning frequency; it does nothing until one is set. "
                + "Type it in, or Measure finds it with a sweep and the mic.", 12, DIM);
        ad.addView(boxState);

        LinearLayout sc = card(col, "Scenes", SoundHelp.SCENES);
        sc.addView(label("Tap to recall, hold to save the current settings.", 12, DIM));
        LinearLayout scRow = row(sc);
        for (int i = 0; i < SoundPrefs.SCENE_NAMES.length; i++) {
            final int slot = i;
            Button x = button(SoundPrefs.SCENE_NAMES[i], v -> {
                if (!SoundPrefs.loadScene(prefs, slot)) {
                    Toast.makeText(this, "Nothing saved here yet: hold to save", Toast.LENGTH_SHORT).show();
                    return;
                }
                buildScreen();
                changed();
            });
            x.setOnLongClickListener(v -> {
                SoundPrefs.saveScene(prefs, slot);
                Toast.makeText(this, "Saved to " + SoundPrefs.SCENE_NAMES[slot], Toast.LENGTH_SHORT).show();
                return true;
            });
            scRow.addView(x);
        }
        toggle(sc, "Bass drop (show-off lift under 80 Hz)", SoundPrefs.DROP, false);
        loud = label("", 12, DIM);
        sc.addView(loud);

        LinearLayout lp = card(col, "Lossless player", SoundHelp.PLAYER);
        lp.addView(label("Plays your own files (the USB stick) with the room correction applied as an exact filter.", 12, DIM));
        lp.addView(button("Open player", v -> startActivity(new Intent(this, PlayerActivity.class))));

        LinearLayout hu = card(col, "Head unit", SoundHelp.HEAD_UNIT);
        hu.addView(label("Kenwood link: mute, volume and preset EQ. Force-stop the KENWOOD Remote app and set the "
                + "stereo's REMOTE APP to ANDROID with this tablet picked, then Connect: it calls the stereo and, if the "
                + "stereo will not talk, waits for the stereo to call. Not proven on the stereo yet, so every command and "
                + "answer goes in the diagnostics log. Source, custom EQ, filters, sub level and fader wait on the probe "
                + "log.", 12, DIM));
        LinearLayout huRow = row(hu);
        huRow.addView(button("Connect", v -> connectStereo(false)));
        huRow.addView(button("Disconnect", v -> stereo.close()));
        huRow.addView(button("Change stereo", v -> connectStereo(true)));
        huState = label(stereo.state(), 12, GOOD);
        hu.addView(huState);
        Switch mute = new Switch(this);
        mute.setText("Mute");
        mute.setTextColor(INK);
        mute.setTextSize(14);
        mute.setOnCheckedChangeListener((v, on) -> { if (stereoReady()) stereo.mute(on); });
        hu.addView(mute);
        final TextView volText = label("Volume: slide and let go to send (0 to " + KenwoodLink.VOLUME_MAX + ")", 13, DIM);
        hu.addView(volText);
        SeekBar vol = new SeekBar(this);
        vol.setMax(KenwoodLink.VOLUME_MAX);
        vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) {
                if (user) volText.setText("Volume: " + p + " (let go to send)");
            }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) {
                if (!stereoReady()) return;
                stereo.volume(b.getProgress());
                volText.setText("Volume: " + b.getProgress() + " sent");
            }
        });
        hu.addView(vol);
        hu.addView(label("Knob keys: the same as pressing the stereo's own volume and ATT keys.", 12, DIM));
        LinearLayout keyRow = row(hu);
        keyRow.addView(button("Vol -", v -> { if (stereoReady()) stereo.volumeDown(); }));
        keyRow.addView(button("Vol +", v -> { if (stereoReady()) stereo.volumeUp(); }));
        keyRow.addView(button("ATT", v -> { if (stereoReady()) stereo.att(); }));
        LinearLayout presetRow = row(hu);
        String[] places = new String[KenwoodLink.PRESETS];
        for (int i = 0; i < places.length; i++) places[i] = "Preset " + i;
        final Spinner preset = new Spinner(this);
        ArrayAdapter<String> presetNames = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, places);
        presetNames.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        preset.setAdapter(presetNames);
        presetRow.addView(preset);
        presetRow.addView(button("Set preset EQ", v -> { if (stereoReady()) stereo.presetEq(preset.getSelectedItemPosition()); }));

        LinearLayout dg = card(col, "Diagnostics", SoundHelp.DIAGNOSTICS);
        dg.addView(label("Errors and issues from the sound engine, Calibrate and the stereo link.", 12, DIM));
        dg.addView(button("Open log", v -> showLog()));
        setContentView(scroll);
    }

    private void showLog() {
        final String text = Diag.LOG.export(DashApp.build(this), new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date())
                + " now, times in ms since boot", DiagLog.INFO, null, 100000);
        new AlertDialog.Builder(this).setTitle("Diagnostics")
                .setMessage(Diag.LOG.size() == 0 ? "Nothing logged." : text)
                .setPositiveButton("Share", (d, w) -> {
                    Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain");
                    i.putExtra(Intent.EXTRA_TEXT, text);
                    startActivity(Intent.createChooser(i, "Jeep Dash diagnostics"));
                })
                .setNeutralButton("Clear", (d, w) -> Diag.LOG.clear())
                .setNegativeButton("Close", null).show();
    }

    // ---------------- sound profiles ----------------

    private void switchProfile(String id) {
        if (calibrating || boxTuning) {
            Toast.makeText(this, "Wait for the measurement to finish", Toast.LENGTH_SHORT).show();
            buildScreen();
            return;
        }
        if (learning) stopLearning();
        SoundProfiles.switchTo(prefs, id);
        profile = SoundPrefs.noise(prefs);
        Diag.info(DiagLog.DSP, "Sound profile: " + SoundProfiles.name(prefs, SoundProfiles.active(prefs)) + ".");
        buildScreen();
        changed();
    }

    private void askProfileName(String title, String now, final Consumer<String> then) {
        final EditText name = new EditText(this);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setHint("Jeep, Room, Headphones...");
        name.setText(now);
        new AlertDialog.Builder(this).setTitle(title).setView(name)
                .setPositiveButton("OK", (d, w) -> {
                    String text = name.getText().toString().trim();
                    if (text.isEmpty()) Toast.makeText(this, "It needs a name", Toast.LENGTH_SHORT).show();
                    else then.accept(text);
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void askDeleteProfile(final String id) {
        if (SoundProfiles.ids(prefs).size() < 2) {
            Toast.makeText(this, "This is the only profile, so it stays", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Delete " + SoundProfiles.name(prefs, id) + "?")
                .setMessage("Its Calibrate measurement and settings go with it.")
                .setPositiveButton("Delete", (d, w) -> {
                    if (calibrating || boxTuning) return;
                    if (learning) stopLearning();
                    SoundProfiles.delete(prefs, id);
                    profile = SoundPrefs.noise(prefs);
                    buildScreen();
                    changed();
                })
                .setNegativeButton("Keep", null).show();
    }

    private Spinner chooser(String[] shown, int selected, final Consumer<Integer> picked) {
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, shown);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(a);
        sp.setSelection(Math.max(0, selected));
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { picked.accept(pos); }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        return sp;
    }

    /** The profile card: which place this is, making and removing profiles, and when this one comes on by itself. */
    private void profileCard(LinearLayout col) {
        LinearLayout pf = card(col, "Sound profile", SoundHelp.PROFILES);
        pf.addView(label("One for each place you listen. Each keeps its own Calibrate measurement, target and adaptive "
                + "settings, and saves as you go.", 12, DIM));
        final List<String> ids = SoundProfiles.ids(prefs);
        final String active = SoundProfiles.active(prefs);
        shownProfile = active;
        String[] names = new String[ids.size()];
        for (int i = 0; i < names.length; i++) names[i] = SoundProfiles.name(prefs, ids.get(i));
        pf.addView(chooser(names, ids.indexOf(active), pos -> {
            if (!ids.get(pos).equals(SoundProfiles.active(prefs))) switchProfile(ids.get(pos));
        }));
        LinearLayout pfRow = row(pf);
        pfRow.addView(button("New", v -> askProfileName("New sound profile", "",
                name -> switchProfile(SoundProfiles.create(prefs, name)))));
        pfRow.addView(button("Rename", v -> askProfileName("Rename this profile", SoundProfiles.name(prefs, active), name -> {
            SoundProfiles.rename(prefs, active, name);
            buildScreen();
        })));
        pfRow.addView(button("Delete", v -> askDeleteProfile(active)));
        pf.addView(label("Come on by itself", 13, DIM));
        final String[] routes = {ProfileRoute.NONE, ProfileRoute.SPEAKER, ProfileRoute.BLUETOOTH};
        int at = 0;
        for (int i = 0; i < routes.length; i++) if (routes[i].equals(SoundProfiles.route(prefs, active))) at = i;
        pf.addView(chooser(new String[]{"Never (pick it by hand)", "When the tablet plays through its own speakers",
                "When the tablet plays through Bluetooth (the stereo)"}, at, pos -> {
            if (!routes[pos].equals(SoundProfiles.route(prefs, active))) SoundProfiles.setRoute(prefs, active, routes[pos]);
        }));
    }

    // ---------------- cabin noise: learned on a drive ----------------

    private String noiseText() {
        NoiseProfile p = profile;
        if (learning) return "Learning: " + learnNote + ". Heard so far: " + (p == null ? "nothing yet" : p.summary()) + ".";
        if (p == null) return "Using the starting model. Learn cabin noise to use this Jeep's own.";
        if (!p.ready())
            return "Not enough yet (" + p.summary() + "): it needs stopped and at least one speed. Using the starting model.";
        return "Using this cabin's measured noise: " + p.summary() + ".";
    }

    private void askLearn() {
        new AlertDialog.Builder(this).setTitle("Learn cabin noise")
                .setMessage("This measures your Jeep's own noise so noise compensation fits it.\n\n"
                        + "1 Pause the music (it waits while anything is playing).\n"
                        + "2 Start parked with the engine running.\n"
                        + "3 Drive as you normally do for a few minutes, through the speeds you usually drive.\n"
                        + "4 Keep this screen open, then tap Stop learning.\n\n"
                        + "It needs the dongle linked, for road speed. Another drive adds to it.")
                .setPositiveButton("Start", (d, w) -> startLearning())
                .setNegativeButton("Cancel", null).show();
    }

    private void startLearning() {
        if (mic == null) {
            Toast.makeText(this, "The microphone is not running, so it cannot listen", Toast.LENGTH_LONG).show();
            return;
        }
        if (profile == null) profile = new NoiseProfile();
        learnKept = learnMusic = learnNoSpeed = 0;
        learnNote = "starting";
        prefs.edit().putBoolean(SoundPrefs.LEARNING, true).apply();     // has the link service read road speed
        changed();
        learning = true;
        learnButton.setText("Stop learning");
        Diag.info(DiagLog.CAL, "Learning cabin noise started.");
    }

    private void stopLearning() {
        learning = false;
        NoiseProfile p = profile;
        SharedPreferences.Editor e = prefs.edit().putBoolean(SoundPrefs.LEARNING, false);
        if (p != null && p.total() > 0) e.putString(SoundPrefs.NOISE_PROFILE, p.toText());
        e.apply();
        if (learnButton != null) learnButton.setText("Learn cabin noise");
        Diag.info(DiagLog.CAL, "Learning cabin noise stopped. Heard: " + (p == null ? "nothing" : p.summary())
                + ". Moments kept " + learnKept + ", skipped " + learnMusic + " for music playing and " + learnNoSpeed
                + " for no road speed from the dongle.");
        changed();
    }

    private void askForgetNoise() {
        if (profile == null && !learning) return;
        new AlertDialog.Builder(this).setTitle("Forget the learned cabin noise?")
                .setMessage("Noise compensation goes back to the starting model until it is learned again.")
                .setPositiveButton("Forget", (d, w) -> {
                    if (learning) stopLearning();
                    profile = null;
                    prefs.edit().remove(SoundPrefs.NOISE_PROFILE).apply();
                    changed();
                })
                .setNegativeButton("Keep", null).show();
    }

    /** One block of what the mic hears, filed under the road speed. Runs on the mic thread. */
    private void learn(double[] bands, int count) {
        NoiseProfile p = profile;
        LinkService s = service;
        if (p == null || s == null) return;
        if (audio != null && audio.isMusicActive()) {
            learnNote = "waiting, pause the music";
            learnMusic++;
            return;
        }
        Double mph = s.liveSpeed();
        if (mph == null) {
            learnNote = "waiting for road speed from the dongle";
            learnNoSpeed++;
            return;
        }
        learnKept++;
        p.add(mph, bands);
        learnNote = String.format(Locale.US, "listening at %.0f mph", mph);
        if (count % 60 == 0) prefs.edit().putString(SoundPrefs.NOISE_PROFILE, p.toText()).apply();   // every 5 s or so
    }

    // ---------------- box tuning: measured with a sweep ----------------

    private void askBoxTune() {
        new AlertDialog.Builder(this).setTitle("Measure the box tuning")
                .setMessage("Plays a 12-second low sweep and finds where the sub's cone goes still: that is the box's "
                        + "tuning.\n\n"
                        + "1 Engine off, music paused.\n"
                        + "2 Volume LOW, about a quarter of normal. This is bass right at the mic.\n"
                        + "3 Hold the mic a finger-width from the centre of the sub's cone.\n"
                        + "4 Tap Start and hold still until it finishes.")
                .setPositiveButton("Start", (d, w) -> startBoxTune())
                .setNegativeButton("Cancel", null).show();
    }

    private void startBoxTune() {
        if (mic == null) {
            boxState.setText("The microphone is not running, so it cannot listen.");
            return;
        }
        if (calibrating || boxTuning) return;
        if (service != null) service.sound().setBypass(true);     // the port guard and EQ must not shape the sweep
        changed();
        boxState.setTextColor(GOOD);
        boxState.setText("Measuring: about 14 seconds. Hold the mic at the cone...");
        Diag.info(DiagLog.CAL, "Box-tune sweep started.");
        boxTuning = true;
    }

    /** The 20 to 80 Hz sweep out of the tablet, then a little silence. Runs on its own thread. */
    private void startSweep() {
        Thread t = new Thread(() -> {
            AudioTrack track = null;
            try {
                int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                        .setBufferSizeInBytes(Math.max(min, 16384))
                        .setTransferMode(AudioTrack.MODE_STREAM).build();
                track.play();
                short[] buf = new short[2048];
                long at = 0, end = (long) (RATE * (BoxTune.SWEEP_S + 0.5));
                while (boxTuning && at < end) {
                    for (int i = 0; i < buf.length; i += 2) {
                        short v = (short) (BoxTune.sweepSample(at++, RATE) * 0.5 * 32767);    // half scale
                        buf[i] = v;
                        buf[i + 1] = v;
                    }
                    track.write(buf, 0, buf.length);
                }
            } catch (RuntimeException e) {
                Diag.error(DiagLog.CAL, "The sweep could not be played: " + e);
            } finally {
                if (track != null) {
                    try { track.stop(); } catch (RuntimeException ignored) { }
                    track.release();
                }
            }
        }, "sound-sweep");
        t.setDaemon(true);
        t.start();
    }

    /** The recording is in: find the dip and offer it as the tuning. Runs on the mic thread. */
    private void finishBoxTune(short[] pcm, int n, int clipped) {
        if (service != null) service.sound().setBypass(false);
        final BoxTune.Result r = BoxTune.estimate(BoxTune.response(pcm, n, RATE));
        final boolean good = clipped <= 3 && r.clear;
        final String text;
        if (clipped > 3) text = "The mic overloaded. Turn the volume down and try again.";
        else if (!r.heard) text = "Could not hear the sweep. Turn the volume up a little and hold the mic right at the cone.";
        else if (!r.clear) text = String.format(Locale.US, "No clear dip (the deepest was %.0f dB at %.0f Hz). Hold the mic "
                + "closer to the centre of the cone and try again. A sealed box has no dip.", r.depthDb, r.hz);
        else text = String.format(Locale.US, "Measured: the box is tuned at %.1f Hz (a %.0f dB dip).", r.hz, r.depthDb);
        if (good) Diag.info(DiagLog.CAL, text);
        else Diag.warn(DiagLog.CAL, text);
        main.post(() -> {
            boxState.setTextColor(good ? GOOD : WARN);
            boxState.setText(text);
            changed();
            if (good && !isFinishing()) offerTune(Math.round(r.hz * 10) / 10f);
        });
    }

    private void offerTune(final float hz) {
        new AlertDialog.Builder(this).setTitle("Box tuning: " + hz + " Hz")
                .setMessage(String.format(Locale.US, "Use it? The port guard will then cut below %.0f Hz, where the cone "
                        + "unloads.", SoundAdapt.subsonicHz(hz)))
                .setPositiveButton("Use it", (d, w) -> {
                    prefs.edit().putFloat(SoundPrefs.TUNE, hz).apply();
                    tuneField.setText(String.valueOf(hz));
                    changed();
                })
                .setNegativeButton("Not now", null).show();
    }

    // ---------------- the Kenwood link ----------------

    private boolean stereoReady() {
        if (stereo.connected()) return true;
        Toast.makeText(this, "Connect to the stereo first", Toast.LENGTH_SHORT).show();
        return false;
    }

    /** Connects to the remembered stereo, or asks which paired device it is (always, when choosing again). */
    private void connectStereo(boolean choose) {
        BluetoothManager m = getSystemService(BluetoothManager.class);
        final BluetoothAdapter adapter = m == null ? null : m.getAdapter();
        if (adapter == null) {
            Toast.makeText(this, "This tablet has no Bluetooth", Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
            return;
        }
        if (!adapter.isEnabled()) {
            Toast.makeText(this, "Turn Bluetooth on first", Toast.LENGTH_LONG).show();
            return;
        }
        if (stereo.busy()) return;
        if (choose) stereo.close();
        else if (stereo.connected()) return;
        final List<BluetoothDevice> paired = new ArrayList<>(adapter.getBondedDevices());
        String saved = prefs.getString(SoundPrefs.STEREO_ADDR, null);
        if (!choose && saved != null) {
            for (BluetoothDevice d : paired) {
                if (saved.equals(d.getAddress())) {
                    stereo.connect(adapter, d, prefs.getString(SoundPrefs.STEREO_NAME, "the stereo"));
                    return;
                }
            }
        }
        if (paired.isEmpty()) {
            Toast.makeText(this, "No paired devices. Pair the stereo in Android's Bluetooth settings first.", Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[paired.size()];
        for (int i = 0; i < names.length; i++) names[i] = paired.get(i).getName() + "  " + paired.get(i).getAddress();
        new AlertDialog.Builder(this).setTitle("Which one is the stereo?")
                .setItems(names, (d, which) -> {
                    BluetoothDevice dev = paired.get(which);
                    String name = String.valueOf(dev.getName());
                    prefs.edit().putString(SoundPrefs.STEREO_ADDR, dev.getAddress())
                            .putString(SoundPrefs.STEREO_NAME, name).apply();
                    stereo.connect(adapter, dev, name);
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ---------------- mic calibration file ----------------

    private void pickCalFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try {
            startActivityForResult(i, REQ_CAL_FILE);
        } catch (RuntimeException e) {
            Toast.makeText(this, "No file picker on this tablet", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQ_CAL_FILE || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while (in != null && (n = in.read(buf)) > 0 && out.size() < 500000) out.write(buf, 0, n);
            String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            int points = SoundTune.parseMicCal(text).hz.length;
            if (points < 5) {
                Diag.warn(DiagLog.CAL, "Mic cal file had only " + points + " usable lines; not loaded.");
                calState.setText("That file is not a mic calibration file (" + points + " usable lines).");
                return;
            }
            prefs.edit().putString(SoundPrefs.MIC_CAL, text).apply();
            calState.setText("Mic calibration loaded: " + points + " points. Used when the USB mic is chosen.");
        } catch (IOException | RuntimeException e) {
            Diag.error(DiagLog.CAL, "Mic cal file could not be read: " + e);
            calState.setText("Could not read that file.");
        }
    }

    // ---------------- the mic: live graph, loud-o-meter and Calibrate ----------------

    private void startMic() {
        if (mic != null) return;
        Thread t = new Thread(this::listen, "sound-mic");
        t.setDaemon(true);
        mic = t;
        t.start();
    }

    private AudioRecord openMic(boolean usb) {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        for (int source : new int[]{MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC}) {
            try {
                AudioRecord rec = new AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(min, BLOCK * 4));
                if (rec.getState() == AudioRecord.STATE_INITIALIZED) {
                    if (usb && !preferUsb(rec))
                        Diag.warn(DiagLog.CAL, "USB mic chosen but none is plugged in; using the built-in mic.");
                    rec.startRecording();
                    return rec;
                }
                rec.release();
            } catch (RuntimeException ignored) {
                // try the next source
            }
        }
        return null;
    }

    private boolean preferUsb(AudioRecord rec) {
        AudioManager am = getSystemService(AudioManager.class);
        if (am == null) return false;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (d.getType() == AudioDeviceInfo.TYPE_USB_DEVICE || d.getType() == AudioDeviceInfo.TYPE_USB_HEADSET)
                return rec.setPreferredDevice(d);
        }
        return false;
    }

    /** Adds one block's power spectrum into a running sum. */
    private static void addPower(double[] sum, double[] mag) {
        for (int k = 0; k < sum.length && k < mag.length; k++) sum[k] += mag[k] * mag[k];
    }

    private static double[] bandsOf(double[] powerSum, int blocks) {
        double[] mag = new double[powerSum.length];
        for (int k = 0; k < mag.length; k++) mag[k] = Math.sqrt(powerSum[k] / Math.max(1, blocks));
        return SoundTune.bandLevelsDb(mag, RATE, BLOCK);
    }

    /** A band curve moved so its 300 Hz to 3 kHz average sits at 0, for drawing. */
    static double[] centred(double[] bands) {
        double[] c = SoundTune.bandCentres();
        double sum = 0;
        int n = 0;
        for (int i = 0; i < bands.length; i++) if (c[i] >= 300 && c[i] <= 3000) { sum += bands[i]; n++; }
        double mean = n == 0 ? 0 : sum / n;
        double[] out = new double[bands.length];
        for (int i = 0; i < bands.length; i++) out[i] = bands[i] - mean;
        return out;
    }

    private void listen() {
        boolean usb = "usb".equals(prefs.getString(SoundPrefs.MIC_USB, "builtin"));
        AudioRecord rec = openMic(usb);
        if (rec == null) {
            Diag.error(DiagLog.CAL, "The microphone could not be opened.");
            main.post(() -> calState.setText("The microphone could not be opened."));
            mic = null;
            return;
        }
        short[] block = new short[BLOCK];
        double[] smooth = null;
        double[] ambient = new double[BLOCK / 2], measure = new double[BLOCK / 2];
        int ambientBlocks = 0, measureBlocks = 0, clipped = 0, learned = 0;
        short[] sweepHeard = null;                               // the box-tune recording while one runs
        int sweepLen = 0, sweepClipped = 0;
        long calStart = 0;
        boolean wasCal = false;
        try {
            while (mic == Thread.currentThread()) {
                int got = 0;
                while (got < BLOCK) {
                    int n = rec.read(block, got, BLOCK - got);
                    if (n <= 0) throw new IOException("mic read " + n);
                    got += n;
                }
                double sq = 0;
                int peak = 0;
                for (short s : block) {
                    sq += (double) s * s;
                    peak = Math.max(peak, Math.abs(s));
                }
                peakNow = 10 * Math.log10(Math.max(1e-12, sq / BLOCK / (32768.0 * 32768.0)));
                if (boxTuning) {
                    if (sweepHeard == null) {                        // a new run: start the sweep and record it whole
                        sweepHeard = new short[(int) (RATE * (BoxTune.SWEEP_S + 1.5))];
                        sweepLen = sweepClipped = 0;
                        startSweep();
                    }
                    int room = Math.min(BLOCK, sweepHeard.length - sweepLen);
                    System.arraycopy(block, 0, sweepHeard, sweepLen, room);
                    sweepLen += room;
                    if (peak > 32000) sweepClipped++;
                    if (sweepLen >= sweepHeard.length) {
                        boxTuning = false;
                        finishBoxTune(sweepHeard, sweepLen, sweepClipped);
                        sweepHeard = null;
                    }
                    continue;
                }
                sweepHeard = null;
                double[] mag = SoundTune.magnitudes(block);

                if (calibrating) {
                    long now = System.currentTimeMillis();
                    if (!wasCal) {                                   // a new run: start clean
                        wasCal = true;
                        calStart = now;
                        java.util.Arrays.fill(ambient, 0);
                        java.util.Arrays.fill(measure, 0);
                        ambientBlocks = measureBlocks = clipped = 0;
                    }
                    double t = (now - calStart) / 1000.0;
                    if (t < AMBIENT_S) {
                        addPower(ambient, mag);
                        ambientBlocks++;
                    } else if (t < AMBIENT_S + SETTLE_S) {
                        if (!noiseOn) startNoise();
                    } else if (t < AMBIENT_S + SETTLE_S + MEASURE_S) {
                        addPower(measure, mag);
                        measureBlocks++;
                        if (peak > 32000) clipped++;
                    } else {
                        noiseOn = false;
                        calibrating = false;
                        wasCal = false;
                        finishCalibrate(bandsOf(ambient, ambientBlocks), bandsOf(measure, measureBlocks), clipped, usb);
                    }
                    continue;
                }
                wasCal = false;
                double[] bands = SoundTune.bandLevelsDb(mag, RATE, BLOCK);
                if (learning) learn(bands, ++learned);
                if (smooth == null) smooth = bands.clone();
                else for (int i = 0; i < bands.length; i++) smooth[i] = 0.75 * smooth[i] + 0.25 * bands[i];
                liveBands = centred(smooth);
            }
        } catch (IOException | RuntimeException e) {
            Diag.error(DiagLog.CAL, "Mic stopped: " + e);
        } finally {
            noiseOn = false;
            try { rec.stop(); } catch (RuntimeException ignored) { }
            rec.release();
            if (mic == Thread.currentThread()) mic = null;
        }
    }

    private static String routeWords(String route) {
        return ProfileRoute.BLUETOOTH.equals(route) ? "Bluetooth (the stereo)"
                : ProfileRoute.SPEAKER.equals(route) ? "the tablet's own speakers" : "something else";
    }

    /** Calibrate measures into the profile showing: check first that the sound is going where that profile expects. */
    private void askCalibrate() {
        String id = SoundProfiles.active(prefs), want = SoundProfiles.route(prefs, id);
        String now = service == null ? "" : service.soundRoute();
        if (want.isEmpty() || now.isEmpty() || want.equals(now)) {
            startCalibrate();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Measure into " + SoundProfiles.name(prefs, id) + "?")
                .setMessage("This profile is for " + routeWords(want) + ", but right now the tablet's sound is going out by "
                        + routeWords(now) + ". Calibrate would measure the wrong speakers and replace this profile's "
                        + "measurement.")
                .setPositiveButton("Calibrate anyway", (d, w) -> startCalibrate())
                .setNegativeButton("Cancel", null).show();
    }

    private void startCalibrate() {
        if (mic == null) {
            calState.setText("The microphone is not running, so Calibrate cannot listen.");
            return;
        }
        if (calibrating || boxTuning) return;
        if (service != null) service.sound().setBypass(true);     // measure the system untouched
        changed();
        calState.setTextColor(GOOD);
        calState.setText("Measuring: quiet first, then about 9 seconds of noise...");
        Diag.info(DiagLog.CAL, "Calibrate started.");
        calibrating = true;
    }

    /** Pink noise out of the tablet until noiseOn goes false. Runs on its own thread. */
    private void startNoise() {
        noiseOn = true;
        Thread t = new Thread(() -> {
            AudioTrack track = null;
            try {
                int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                        .setBufferSizeInBytes(Math.max(min, 16384))
                        .setTransferMode(AudioTrack.MODE_STREAM).build();
                track.play();
                Random rnd = new Random();
                short[] buf = new short[2048];
                double b0 = 0, b1 = 0, b2 = 0;
                while (noiseOn) {
                    for (int i = 0; i < buf.length; i += 2) {
                        double white = rnd.nextDouble() * 2 - 1;     // Paul Kellet's economy pink filter
                        b0 = 0.99765 * b0 + white * 0.0990460;
                        b1 = 0.96300 * b1 + white * 0.2965164;
                        b2 = 0.57000 * b2 + white * 1.0526913;
                        double pink = (b0 + b1 + b2 + white * 0.1848) * 0.11;
                        short s = (short) (Math.max(-1, Math.min(1, pink)) * 32767);
                        buf[i] = s;
                        buf[i + 1] = s;
                    }
                    track.write(buf, 0, buf.length);
                }
            } catch (RuntimeException e) {
                Diag.error(DiagLog.CAL, "Test noise could not be played: " + e);
            } finally {
                if (track != null) {
                    try { track.stop(); } catch (RuntimeException ignored) { }
                    track.release();
                }
            }
        }, "sound-noise");
        t.setDaemon(true);
        t.start();
    }

    private void finishCalibrate(double[] ambient, double[] measured, int clipped, boolean usb) {
        if (service != null) service.sound().setBypass(false);
        double[] c = SoundTune.bandCentres();
        double over = 0;
        int n = 0;
        for (int i = 0; i < c.length; i++) if (c[i] >= 200 && c[i] <= 4000) { over += measured[i] - ambient[i]; n++; }
        over = n == 0 ? 0 : over / n;
        String problem = null;
        if (over < 10)
            problem = String.format(Locale.US, "Too quiet: the noise was only %.0f dB above the cabin. Turn the volume up and try again.", over);
        else if (clipped > 3)
            problem = "The mic overloaded. Turn the volume down and try again.";
        if (problem != null) {
            Diag.warn(DiagLog.CAL, problem);
            final String shown = problem;
            main.post(() -> {
                calState.setTextColor(WARN);
                calState.setText(shown);
                changed();
            });
            return;
        }
        String calText = usb ? prefs.getString(SoundPrefs.MIC_CAL, null) : null;
        boolean trusted = calText != null;
        if (trusted) measured = SoundTune.applyMicCal(measured, SoundTune.parseMicCal(calText));
        // a tablet mic cannot be trusted at the extremes, so the correction only covers its honest range
        double loHz = trusted ? 25 : 40, hiHz = trusted ? 16000 : 12500;
        int lo = 0, hi = c.length - 1;
        for (int i = 0; i < c.length; i++) {
            if (c[i] < loHz) lo = i + 1;
            if (c[i] <= hiHz) hi = i;
        }
        prefs.edit().putString(SoundPrefs.MEASURED, SoundPlan.toText(measured))
                .putInt(SoundPrefs.VALID_LO, lo).putInt(SoundPrefs.VALID_HI, hi).apply();
        int reach = SoundTune.reach(measured, SoundPrefs.targetBands(prefs))[0];
        final String done = String.format(Locale.US, "Calibrated %s with the %s (noise %.0f dB above the cabin). Correcting %d Hz to %s.",
                SoundProfiles.name(prefs, SoundProfiles.active(prefs)), trusted ? "measurement mic" : usb ? "USB mic, no cal file loaded" : "built-in mic",
                over, (int) Math.round(c[lo]), String.format(Locale.US, "%.0f kHz", c[hi] / 1000))
                + (c[reach] > 150 ? String.format(Locale.US, " These speakers start at about %d Hz, so nothing is boosted "
                + "below that.", (int) Math.round(c[reach])) : "");
        Diag.info(DiagLog.CAL, done);
        Diag.info(DiagLog.CAL, "Measured (dB, 20 Hz to 20 kHz): " + SoundPlan.toText(measured));
        double[] fix = SoundPrefs.correction(prefs);
        if (fix != null) Diag.info(DiagLog.CAL, "Correction toward the target: " + SoundPlan.toText(fix));
        main.post(() -> {
            calState.setTextColor(GOOD);
            calState.setText(done);
            changed();
        });
    }
}
