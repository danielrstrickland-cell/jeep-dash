package com.strickland.jeepdash;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import com.strickland.jeepdash.core.QualityMode;
import com.strickland.jeepdash.scene.ModelStore;

import java.util.Arrays;
import java.util.Locale;

/**
 * The Holo look's 3D truck and quality settings: the truck model (the owner's full-detail file and an optional
 * lite one, or the built-in stand-in), how hard it leans, its lighting, the Quality mode, the hologram effects,
 * calm while moving and heat protection. Opened from the menu or by tapping the truck in edit mode.
 */
final class HoloSettingsDialog {
    private HoloSettingsDialog() { }

    static final String MOTION = "truck_motion", LIGHT = "truck_ev", QUALITY = "holo_quality", EFFECTS = "holo_effects",
            CALM = "holo_calm";

    interface Actions {
        void loadModel(String kind);
        void forgetModels();
        void hideTruck();
        void settingsChanged();
    }

    /** "realistic", "lively", "exaggerated": how the motion setting reads in the menu. */
    static String motionLabel(float s) { return s < 0.25f ? "realistic" : s < 0.7f ? "lively" : "exaggerated"; }

    static void show(Activity a, SharedPreferences prefs, String found, Actions act) {
        float d = a.getResources().getDisplayMetrics().density;
        int pad = Math.round(20 * d);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        String full = ModelStore.name(a, QualityMode.FULL), lite = ModelStore.name(a, QualityMode.LITE);
        TextView models = text(a, "Truck: " + (full == null && lite == null ? "the built-in stand-in" : full == null ? lite : full)
                + (lite != null && full != null ? "\nLite (Power saver): " + lite : "")
                + (ModelStore.has(a, QualityMode.TWO_K) ? "\nHigh uses a 2K-texture copy of it." : "")
                + (found == null ? "" : "\nMoving parts found: " + found), 14);
        box.addView(models);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button loadFull = new Button(a);
        loadFull.setText("Load truck model");
        Button loadLite = new Button(a);
        loadLite.setText("Load lite model (optional)");
        row.addView(loadFull);
        row.addView(loadLite);
        box.addView(row);
        Button standIn = new Button(a);
        standIn.setText("Use the built-in stand-in");
        standIn.setEnabled(full != null || lite != null);
        box.addView(standIn);

        box.addView(text(a, "Body lean", 13));
        final TextView motionText = text(a, "", 14);
        SeekBar motion = new SeekBar(a);
        motion.setMax(100);
        motion.setProgress(Math.round(prefs.getFloat(MOTION, 0.35f) * 100));
        motionText.setText(motionLabel(motion.getProgress() / 100f));
        box.addView(motion);
        box.addView(motionText);

        box.addView(text(a, "Lighting", 13));
        final TextView lightText = text(a, "", 14);
        SeekBar light = new SeekBar(a);
        light.setMax(8);
        light.setProgress(Math.round((prefs.getFloat(LIGHT, 0) + 2) * 2));
        lightText.setText(ev(light.getProgress()));
        box.addView(light);
        box.addView(lightText);

        box.addView(text(a, "Quality mode", 13));
        Spinner quality = new Spinner(a);
        ArrayAdapter<String> ad = new ArrayAdapter<>(a, android.R.layout.simple_spinner_item, QualityMode.MODES);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        quality.setAdapter(ad);
        quality.setSelection(Arrays.asList(QualityMode.MODES).indexOf(QualityMode.pick(prefs.getString(QUALITY, null))));
        box.addView(quality);
        TextView hint = text(a, "Ultra: 30 a second, sharpest, soft shadow. High: 60 a second. Power saver: 30 a second, "
                + "cooler and lighter on the battery.", 12);
        box.addView(hint);

        CheckBox effects = check(a, "Hologram effects (scanlines, glow, flicker)", prefs.getBoolean(EFFECTS, true));
        CheckBox calm = check(a, "Calm while moving (no flicker or big animations above 5 mph)", prefs.getBoolean(CALM, true));
        CheckBox heat = check(a, "Heat protection (lower the quality when the tablet runs hot)", prefs.getBoolean(TabletHealth.PREF_PROTECT, true));
        box.addView(effects);
        box.addView(calm);
        box.addView(heat);

        ScrollView scroll = new ScrollView(a);
        scroll.addView(box);
        AlertDialog dlg = HoloDialogs.show(HoloDialogs.builder(a, true)
                .setTitle("3D truck and quality")
                .setView(scroll)
                .setPositiveButton("Done", null)
                .setNegativeButton("Hide the truck", (x, w) -> act.hideTruck()), true, true);

        Runnable save = () -> {
            prefs.edit()
                    .putFloat(MOTION, motion.getProgress() / 100f)
                    .putFloat(LIGHT, light.getProgress() / 2f - 2)
                    .putString(QUALITY, QualityMode.MODES[Math.max(0, quality.getSelectedItemPosition())])
                    .putBoolean(EFFECTS, effects.isChecked())
                    .putBoolean(CALM, calm.isChecked())
                    .putBoolean(TabletHealth.PREF_PROTECT, heat.isChecked())
                    .apply();
            act.settingsChanged();
        };
        SeekBar.OnSeekBarChangeListener bars = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) {
                motionText.setText(motionLabel(motion.getProgress() / 100f));
                lightText.setText(ev(light.getProgress()));
            }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { save.run(); }
        };
        motion.setOnSeekBarChangeListener(bars);
        light.setOnSeekBarChangeListener(bars);
        quality.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { save.run(); }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        for (CheckBox c : new CheckBox[]{effects, calm, heat}) c.setOnCheckedChangeListener((b, on) -> save.run());
        loadFull.setOnClickListener(v -> {
            dlg.dismiss();
            act.loadModel(QualityMode.FULL);
        });
        loadLite.setOnClickListener(v -> {
            dlg.dismiss();
            act.loadModel(QualityMode.LITE);
        });
        standIn.setOnClickListener(v -> {
            dlg.dismiss();
            act.forgetModels();
        });
    }

    private static String ev(int progress) {
        float ev = progress / 2f - 2;
        return ev == 0 ? "as made" : String.format(Locale.US, "%s%.1f stops %s", ev > 0 ? "+" : "", ev, ev > 0 ? "brighter" : "darker");
    }

    private static TextView text(Activity a, String s, int size) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(size);
        t.setPadding(0, Math.round(8 * a.getResources().getDisplayMetrics().density), 0, 0);
        return t;
    }

    private static CheckBox check(Activity a, String s, boolean on) {
        CheckBox c = new CheckBox(a);
        c.setText(s);
        c.setChecked(on);
        return c;
    }
}
