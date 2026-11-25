package com.strickland.jeepdash;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.strickland.jeepdash.core.Catalog;
import com.strickland.jeepdash.core.LiveValues;
import com.strickland.jeepdash.core.LogProfile;
import com.strickland.jeepdash.core.Param;
import com.strickland.jeepdash.core.SpeedCalibration;
import com.strickland.jeepdash.core.TuneLog;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Tune log screen: pick one of the four profiles, follow the on-screen steps while the readings are written
 * to a CSV in Downloads/JeepDash, then save it or send it through the share sheet. It only reads what the dash
 * already reads (the link service is asked for the profile's values); nothing new is sent to the engine computer.
 * It will not start, and stops and saves, while Jeep Tune has the dongle.
 */
public class TuneLogActivity extends Activity implements ServiceConnection, LinkService.Listener {
    private static final int BG = Color.rgb(16, 18, 24), PANEL = Color.rgb(30, 34, 44), SELECTED = Color.rgb(34, 64, 104),
            ACCENT = Color.rgb(90, 160, 255), GOOD = Color.rgb(120, 220, 140), RED = Color.rgb(235, 60, 60);

    private LinkService service;
    private boolean bound;
    private LinkService.Status status = LinkService.Status.SEARCHING;

    private LogProfile selected = LogProfile.all().get(0);
    private int step;

    // logging state (main thread only)
    private TuneLog log;
    private Writer writer;
    private Uri file;
    private String fileName;
    private LogProfile logging;
    private GpsFeed gps;
    private boolean askedLocation;
    private String speedCard;                // the speedometer result (or why there is none) shown on the picker
    private long startedAt;
    private int sinceFlush;
    private long lastLiveMs = Long.MIN_VALUE / 2;
    private final List<Param> tileParams = new ArrayList<>();
    private final List<TextView> tileValues = new ArrayList<>();
    private TextView clock, counts, stepTitle, stepText, note;
    private Button nextStep;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showPicker(null);
    }

    @Override
    protected void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, LinkService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        if (logging != null) stopAndSave(false, "Tune log saved because the screen was left");
        if (service != null) service.removeListener(this);
        service = null;
        if (bound) {
            unbindService(this);
            bound = false;
        }
        super.onStop();
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((LinkService.LocalBinder) binder).service();
        service.addListener(this);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) { service = null; }

    // ---------------- link callbacks ----------------

    @Override
    public void onStatus(LinkService.Status s, String detail) {
        status = s;
        if (logging != null && s == LinkService.Status.PAUSED) {
            stopAndSave(false, LinkService.PAUSED_DETAIL + ". The log so far is saved.");
            return;
        }
        if (logging == null && note != null) note.setText(startProblem());
    }

    @Override
    public void onValues(LiveValues v) {
        if (logging == null) return;
        long now = LinkService.now();
        try {
            if (log.row(now - startedAt, now, v) && ++sinceFlush >= 10) {
                sinceFlush = 0;
                log.flush();
            }
        } catch (IOException e) {
            stopAndSave(false, "Could not write the log: " + e.getMessage());
            return;
        }
        sendLive(v, now);
        clock.setText("Logging " + LogProfile.clock(now - startedAt));
        counts.setText(log.rowCount() + " rows · " + logging.rateText());
        for (int i = 0; i < tileParams.size(); i++) tileValues.get(i).setText(format(tileParams.get(i), v.get(tileParams.get(i).id, now)));
    }

    /**
     * Tells Jeep Tune's table viewer where the engine is (rpm and MAP already polled for this log), at most 5 times a
     * second, only to that package. Nothing is sent to the engine computer.
     */
    private void sendLive(LiveValues v, long now) {
        if (now - lastLiveMs < 200) return;
        Double rpm = v.get("rpm", now), map = v.get("map_kpa", now);
        if (rpm == null || map == null) return;
        lastLiveMs = now;
        Intent i = new Intent("com.strickland.jeepdash.action.LIVE");
        i.setPackage("com.strickland.jeeptune");
        i.putExtra("rpm", rpm.floatValue());
        i.putExtra("map_kpa", map.floatValue());
        i.putExtra("t", now);
        try { sendBroadcast(i); } catch (RuntimeException ignored) { }
    }

    private static String format(Param p, Double d) {
        if (d == null) return "–";
        String num = Math.abs(d) >= 100 ? String.format(Locale.US, "%.0f", d) : String.format(Locale.US, "%.1f", d);
        return num + " " + p.baseUnit;
    }

    // ---------------- picker ----------------

    /** Why a log cannot start right now, or "" when it can. */
    private String startProblem() {
        if (LinkService.tunePaused() || status == LinkService.Status.PAUSED) return LinkService.PAUSED_DETAIL;
        if (service == null || status != LinkService.Status.LIVE) return "Waiting for the dongle to connect.";
        return "";
    }

    private TextView text(float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable box(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp * getResources().getDisplayMetrics().density);
        return g;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private Button button(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(Color.WHITE);
        b.setBackground(box(color, 8));
        return b;
    }

    private LinearLayout.LayoutParams full(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private void showPicker(String message) {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(14), dp(20), dp(14));
        scroll.addView(col);

        TextView title = text(22, Color.WHITE, true);
        title.setText("Tune log");
        col.addView(title);

        if (speedCard != null) {
            TextView card = text(16, Color.WHITE, true);
            card.setPadding(dp(14), dp(10), dp(14), dp(10));
            card.setBackground(box(Color.rgb(24, 70, 52), 8));
            card.setText(speedCard);
            col.addView(card, full(8));
        }

        int n = 0;
        for (LogProfile p : LogProfile.all()) {
            n++;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(8), dp(12), dp(8));
            boolean on = p == selected;
            row.setBackground(box(on ? SELECTED : PANEL, 8));

            TextView badge = text(16, Color.WHITE, true);
            badge.setText(String.valueOf(n));
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(box(on ? ACCENT : Color.rgb(70, 76, 90), 20));
            row.addView(badge, new LinearLayout.LayoutParams(dp(36), dp(36)));

            LinearLayout words = new LinearLayout(this);
            words.setOrientation(LinearLayout.VERTICAL);
            words.setPadding(dp(14), 0, dp(8), 0);
            TextView name = text(18, Color.WHITE, true);
            name.setText(p.name);
            TextView desc = text(13, Color.LTGRAY, false);
            desc.setText(p.description);
            words.addView(name);
            words.addView(desc);
            row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            TextView check = text(22, GOOD, true);
            check.setText(on ? "✓" : "");
            row.addView(check);

            row.setOnClickListener(v -> {
                selected = p;
                step = 0;
                showPicker(null);
            });
            col.addView(row, full(8));
        }

        LinearLayout stepBox = new LinearLayout(this);
        stepBox.setOrientation(LinearLayout.VERTICAL);
        stepBox.setPadding(dp(14), dp(10), dp(14), dp(10));
        stepBox.setBackground(box(PANEL, 8));
        TextView st = text(13, ACCENT, true);
        st.setText(selected.stepTitle(step));
        TextView sp = text(17, Color.WHITE, false);
        sp.setText(selected.steps.get(step));
        stepBox.addView(st);
        stepBox.addView(sp);
        col.addView(stepBox, full(12));

        note = text(13, Color.rgb(255, 200, 110), false);
        note.setText(message != null ? message : startProblem());
        col.addView(note, full(8));

        Button start = button("Start " + selected.name.toLowerCase(Locale.US) + " log", Color.rgb(40, 130, 70));
        start.setOnClickListener(v -> start());
        col.addView(start, full(6));
        setContentView(scroll);
    }

    // ---------------- logging ----------------

    private void start() {
        String problem = startProblem();
        if (!problem.isEmpty()) {
            note.setText(problem);
            return;
        }
        boolean wantsGps = false;
        for (LogProfile.Want w : selected.channels) if (w.paramId.equals("gps_mph")) wantsGps = true;
        if (wantsGps && !askedLocation && !GpsFeed.usable(this)
                && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // asked once, only now that a log needing GPS is starting; a refusal just leaves the GPS channels out
            askedLocation = true;
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION}, 7);
            return;
        }
        long wall = System.currentTimeMillis();
        fileName = selected.fileName(wall, java.time.ZoneId.systemDefault());
        try {
            file = DriveLogStore.createCsv(this, fileName);
            if (file == null) {
                note.setText("Saving logs needs Android 10 or newer.");
                return;
            }
            writer = DriveLogStore.writer(this, file);
            Set<String> tablet = GpsFeed.usable(this) ? new HashSet<>(Arrays.asList("gps_mph", "gps_accuracy_m")) : new HashSet<String>();
            log = new TuneLog(writer, selected, wall, Catalog::byId, tablet);
        } catch (IOException e) {
            note.setText("Could not create the log file: " + e.getMessage());
            return;
        }
        logging = selected;
        speedCard = null;
        gps = null;
        if (log.channelIds().contains("gps_mph")) {
            gps = new GpsFeed();
            if (!gps.start(this, service.values())) gps = null;
        }
        step = 0;
        startedAt = LinkService.now();
        sinceFlush = 0;
        // ask the link for this profile's values, the way the dash asks for its gauges
        service.setActiveParams(new LinkedHashSet<>(log.paramIds()));      // engine values only: GPS comes from GpsFeed
        buildLogging();
    }

    private void buildLogging() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(BG);
        col.setPadding(dp(20), dp(12), dp(20), dp(12));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView dot = text(22, RED, true);
        dot.setText("● ");
        clock = text(20, Color.WHITE, true);
        clock.setText("Logging 00:00");
        counts = text(14, Color.LTGRAY, false);
        counts.setGravity(Gravity.END);
        counts.setText("0 rows · " + logging.rateText());
        top.addView(dot);
        top.addView(clock);
        top.addView(counts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        col.addView(top);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        tileParams.clear();
        tileValues.clear();
        for (String id : log.channelIds()) {
            Param p = Catalog.byId(id);
            if (p == null) continue;
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setPadding(dp(12), dp(6), dp(12), dp(6));
            tile.setBackground(box(PANEL, 8));
            TextView label = text(12, Color.LTGRAY, false);
            label.setText(p.name);
            TextView value = text(22, Color.WHITE, true);
            value.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            value.setText("–");
            tile.addView(label);
            tile.addView(value);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            grid.addView(tile, lp);
            tileParams.add(p);
            tileValues.add(value);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(grid);
        col.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout stepRow = new LinearLayout(this);
        stepRow.setGravity(Gravity.CENTER_VERTICAL);
        stepRow.setPadding(dp(12), dp(6), dp(12), dp(6));
        stepRow.setBackground(box(PANEL, 8));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        stepTitle = text(12, ACCENT, true);
        stepText = text(16, Color.WHITE, false);
        words.addView(stepTitle);
        words.addView(stepText);
        stepRow.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        nextStep = button("Next step", Color.rgb(60, 70, 90));
        nextStep.setOnClickListener(v -> {
            if (step < logging.steps.size() - 1) step++;
            showStep();
        });
        stepRow.addView(nextStep);
        col.addView(stepRow, full(6));
        showStep();

        LinearLayout bottom = new LinearLayout(this);
        Button stop = button("Stop and save", Color.rgb(170, 50, 50));
        stop.setOnClickListener(v -> stopAndSave(false, null));
        Button send = button("Send to Claude", Color.rgb(40, 100, 190));
        send.setOnClickListener(v -> stopAndSave(true, null));
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        half.setMargins(dp(3), 0, dp(3), 0);
        bottom.addView(stop, half);
        bottom.addView(send, half);
        col.addView(bottom, full(6));
        setContentView(col);
    }

    private void showStep() {
        stepTitle.setText(logging.stepTitle(step));
        stepText.setText(logging.steps.get(step));
        nextStep.setEnabled(step < logging.steps.size() - 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 7 && logging == null && !isFinishing()) start();      // allowed or not, the log goes ahead
    }

    /** Closes the CSV, goes back to the picker and, when asked, opens the share sheet for the file. */
    private void stopAndSave(boolean share, String message) {
        if (logging == null) return;
        int rows = log.rowCount();
        if (gps != null) gps.stop();
        gps = null;
        Uri saved = file;
        String name = fileName;
        try {
            log.close();
        } catch (IOException ignored) {
            // what was flushed is in the file
        }
        if (log.measuresSpeed()) {
            SpeedCalibration.Result r = log.speedResult();
            speedCard = r != null ? r.summary()
                    : "Speedometer check: not enough steady GPS data (" + log.speedSamples() + " samples). Hold each speed steady for a few seconds with a good GPS fix.";
        } else if (logging.id.equals("cruise")) {
            speedCard = "Speedometer check skipped: GPS was not available (location not allowed).";
        }
        logging = null;
        log = null;
        writer = null;
        tileParams.clear();
        tileValues.clear();
        clock = null;
        if (!isFinishing()) {
            showPicker(message != null ? message : "Saved " + name + " (" + rows + " rows) in Downloads/JeepDash");
            if (message == null) Toast.makeText(this, "Saved " + name, Toast.LENGTH_SHORT).show();
        }
        if (share && saved != null && !isFinishing()) DriveLogStore.share(this, saved, "Send tune log");
    }
}
