package com.strickland.jeepdash;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.strickland.jeepdash.core.NormalReadings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Normal readings (Diagnostics): each watched reading's normal range, its latest figure and whether that is
 * normal. Tapping one offers "Accept current as normal"; "Start learning again" forgets this car's normal
 * after asking. The figures follow the units the dash's gauges are set to.
 */
final class NormalDialog {
    private final Activity activity;
    private final Map<String, String> units;
    private final Runnable changed;
    private final NormalReadings normal;
    private AlertDialog dialog;
    private TextView status;
    private ArrayAdapter<String> rows;
    private List<NormalReadings.Row> shown = new ArrayList<>();

    private NormalDialog(Activity activity, Map<String, String> units, Runnable changed) {
        this.activity = activity;
        this.units = units;
        this.changed = changed;
        normal = NormalStore.get(activity);
    }

    /** changed runs after anything was accepted or forgotten (the dash takes its notice down). */
    static void show(Activity activity, Map<String, String> units, Runnable changed) {
        new NormalDialog(activity, units, changed).open();
    }

    private void open() {
        int pad = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        status = new TextView(activity);
        status.setPadding(pad, pad / 2, pad, pad / 2);
        status.setTextSize(16);
        rows = new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, new ArrayList<String>());
        ListView list = new ListView(activity);
        list.setAdapter(rows);
        list.setOnItemClickListener((parent, view, position, id) -> offerAccept(shown.get(position)));
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(status);
        box.addView(list);
        dialog = new AlertDialog.Builder(activity)
                .setTitle("Normal readings")
                .setView(box)
                .setPositiveButton("Close", null)
                .setNegativeButton("Start learning again", (d, w) -> confirmStartAgain())
                .create();
        fill();
        dialog.show();
    }

    private void fill() {
        String progress = normal.progress(System.currentTimeMillis());
        status.setText(progress != null ? progress + ". Readings are taken at warm idle (stopped, engine warm) and from "
                + "the coolant once settled on the move; nothing is said until it has learned."
                : "Watching. A reading is flagged when it is outside normal on two drives in a row. Tap one to accept "
                + "its latest figure as the new normal.");
        shown = normal.rows();
        rows.clear();
        for (NormalReadings.Row r : shown) rows.add(NormalReadings.describe(r, units));
        rows.notifyDataSetChanged();
    }

    private void offerAccept(NormalReadings.Row r) {
        if (r.median == null || r.latest == null) {
            new AlertDialog.Builder(activity).setTitle(r.watch.name)
                    .setMessage(r.median == null ? "Still learning this one." : "No reading since it learned yet.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle("Accept current as normal")
                .setMessage(NormalReadings.describe(r, units) + "\n\nMake the latest figure this reading's normal from now on?")
                .setPositiveButton("Accept", (d, w) -> {
                    normal.accept(r.watch.id);
                    NormalStore.save();
                    fill();
                    changed.run();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmStartAgain() {
        new AlertDialog.Builder(activity)
                .setTitle("Start learning again")
                .setMessage("Forget what normal looks like for this car and learn it again over the next week and "
                        + "five drives? Nothing is said meanwhile.")
                .setPositiveButton("Start again", (d, w) -> {
                    normal.startAgain();
                    NormalStore.save();
                    changed.run();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
