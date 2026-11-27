package com.strickland.jeepdash;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.strickland.jeepdash.core.DiagLog;
import com.strickland.jeepdash.core.KenwoodCommands;
import com.strickland.jeepdash.core.KenwoodProbe;
import com.strickland.jeepdash.core.KenwoodSettings;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Stereo probe: asks the Kenwood KMM-BT728HD for its current sound settings over the same Bluetooth serial
 * link the KENWOOD Remote app uses, and logs every reply, so the layout of each setting can be worked out
 * before this app ever changes one. Unlike Listen mode it does send: the session set-up and acknowledgements
 * ({@link StereoLink}) and the read requests listed in {@link KenwoodProbe}. It never sends a setting write.
 * One run takes about half a minute; the log is saved and shared the same way as a Listen log.
 */
public class ProbeActivity extends Activity {
    private static final int REQ_PERM = 8;
    private static final long OPEN_WAIT_MS = 45000, ANSWER_WAIT_MS = 12000, STEP_MS = 700, TAIL_MS = 3000;

    private final DiagLog log = new DiagLog(2000);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final long t0 = System.currentTimeMillis();
    private final String startedWhen = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    private BluetoothAdapter adapter;
    private volatile boolean closing, running;
    private final StereoLink link = new StereoLink((level, tag, text) -> log.add(now(), level, tag, text));
    private String stereoName = "(none)";

    private TextView status, logView;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            StringBuilder s = new StringBuilder();
            for (DiagLog.Entry e : log.recent(60, DiagLog.INFO, null))
                s.append(String.format(Locale.US, "%7d  %s%n", e.t, e.msg));
            logView.setText(s);
            main.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildScreen();
        BluetoothManager m = getSystemService(BluetoothManager.class);
        adapter = m == null ? null : m.getAdapter();
        note("Probe opened. Sends read requests only; no setting is written to the stereo.");
        main.post(refresh);
        if (adapter == null) status.setText("This tablet has no Bluetooth.");
        else if (!hasBt()) askBt();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(refresh);
        disconnect();
        super.onDestroy();
    }

    private long now() { return System.currentTimeMillis() - t0; }

    private void note(String msg) { log.info(now(), DiagLog.BT, msg); }

    private void fail(String msg) { log.error(now(), DiagLog.BT, msg); }

    // ---------------- permissions ----------------

    private boolean hasBt() {
        if (Build.VERSION.SDK_INT < 31) return true;
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void askBt() {
        if (Build.VERSION.SDK_INT >= 31)
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_PERM);
    }

    // ---------------- screen ----------------

    private Button button(String text, View.OnClickListener l) {
        Button x = new Button(this);
        x.setText(text);
        x.setAllCaps(false);
        x.setTextSize(13);
        x.setOnClickListener(l);
        return x;
    }

    private TextView text(float size, int color) {
        TextView t = new TextView(this);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(16, 18, 24));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(24, 24, 24, 24);
        scroll.addView(col);

        TextView title = text(20, Color.WHITE);
        title.setText("Stereo probe: Kenwood sound settings");
        col.addView(title);
        TextView how = text(12, Color.LTGRAY);
        how.setText("1 Force-stop the KENWOOD Remote app.  2 On the stereo: FUNCTION, REMOTE APP, SELECT, ANDROID: YES, "
                + "then ANDROID LIST: this tablet.  3 Tap Pick stereo & run: it reads every sound setting (reading "
                + "changes nothing) and keeps them as your backup.  4 Test a write: changes Loudness one step, checks "
                + "it, and puts it back.  5 Find the volume command: tries each way, you say if the stereo reacted.  "
                + "6 Save & share the log.");
        col.addView(how);

        LinearLayout top = new LinearLayout(this);
        top.addView(button("Pick stereo & run", v -> pickStereo()));
        top.addView(button("Save & share", v -> save()));
        col.addView(top);
        LinearLayout tests = new LinearLayout(this);
        tests.addView(button("Test a write", v -> writeTest()));
        tests.addView(button("Find the volume command", v -> findVolume(0)));
        col.addView(tests);

        status = text(14, Color.rgb(120, 220, 140));
        status.setText("Not connected.");
        col.addView(status);

        TextView lg = text(14, Color.WHITE);
        lg.setText("\nLog (latest lines; > sent, < received)");
        col.addView(lg);
        logView = text(10, Color.LTGRAY);
        logView.setGravity(Gravity.START);
        col.addView(logView);
        setContentView(scroll);
    }

    // ---------------- connecting and running ----------------

    private void pickStereo() {
        if (adapter == null) return;
        if (!hasBt()) {
            askBt();
            return;
        }
        if (running) {
            Toast.makeText(this, "A probe is already running", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!adapter.isEnabled()) {
            Toast.makeText(this, "Turn Bluetooth on first", Toast.LENGTH_LONG).show();
            return;
        }
        final List<BluetoothDevice> paired = new ArrayList<>(adapter.getBondedDevices());
        if (paired.isEmpty()) {
            status.setText("No paired devices. Pair the stereo in Android Bluetooth settings first.");
            return;
        }
        String[] names = new String[paired.size()];
        for (int i = 0; i < names.length; i++) names[i] = paired.get(i).getName() + "  " + paired.get(i).getAddress();
        new AlertDialog.Builder(this).setTitle("Which one is the stereo?")
                .setItems(names, (d, which) -> start(paired.get(which)))
                .setNegativeButton("Cancel", null).show();
    }

    private void start(final BluetoothDevice d) {
        disconnect();
        closing = false;
        running = true;
        stereoName = String.valueOf(d.getName());
        note("Picked " + stereoName);
        status.setText("Getting ready for " + stereoName + "...");
        link.connect(adapter, d, stereoName);
        Thread t = new Thread(this::run, "probe");
        t.setDaemon(true);
        t.start();
    }

    private boolean waitIdle(long ms) throws InterruptedException {
        long until = System.currentTimeMillis() + ms;
        Thread.sleep(300);
        while (!closing && link.connected() && !link.idle() && System.currentTimeMillis() < until) Thread.sleep(150);
        return link.idle();
    }

    /** Waits for the session, then reads every sound setting and keeps the answers as the tune backup. */
    private void run() {
        try {
            long until = System.currentTimeMillis() + OPEN_WAIT_MS;
            while (!closing && !link.ready() && System.currentTimeMillis() < until) {
                show(link.state());
                Thread.sleep(300);
            }
            if (closing) return;
            if (!link.ready()) {
                fail("No session with the stereo after " + (OPEN_WAIT_MS / 1000) + " seconds. Nothing was asked.");
                show("No session with the stereo. Tap Save & share to send the log.");
                return;
            }
            show("Session open. Reading every sound setting...");
            link.readAll();
            waitIdle(ANSWER_WAIT_MS * KenwoodSettings.reads().size());
            Thread.sleep(TAIL_MS);
            if (closing) return;
            KenwoodSettings.Backup b = link.heard();
            String when = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
            String text = b.toText(when, stereoName);
            for (String line : text.split("\n")) note(line);
            if (b.size() > 0) SoundPrefs.of(this).edit().putString(SoundPrefs.STEREO_BACKUP, text)
                    .putString(SoundPrefs.STEREO_BACKUP_WHEN, when).apply();
            note("Read finished: the stereo answered " + b.size() + " of " + KenwoodSettings.reads().size() + " settings.");
            show(b.size() + " settings read and kept as your backup. Now Test a write, Find the volume command, or Save & share.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            running = false;
        }
    }

    /** Changes Loudness one step, reads it back, and puts it back as it was. */
    private void writeTest() {
        if (!link.ready()) {
            Toast.makeText(this, "Pick the stereo and let it read first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (running) {
            Toast.makeText(this, "Wait for the probe to finish", Toast.LENGTH_SHORT).show();
            return;
        }
        running = true;
        final KenwoodSettings.WriteTest w = KenwoodSettings.WriteTest.loudness();
        link.listen((cat, cmd, p) -> { if (cat == KenwoodSettings.REPLY) w.onReply(cmd, p); });
        note("Write test: Loudness, one step and back.");
        show("Testing a write: watch LOUDNESS on the stereo...");
        Thread t = new Thread(() -> {
            try {
                while (!closing && !w.done() && link.connected()) {
                    KenwoodSettings.Msg m = w.next();
                    if (m != null) link.ask(m.label, KenwoodCommands.CAT_SETTING, m.cmd, m.payload);
                    Thread.sleep(500);
                    w.tick();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                link.listen(null);
                running = false;
            }
            String r = w.done() ? w.result() : "Write test stopped: the link ended.";
            note("WRITE TEST: " + r);
            show(r);
        }, "probe-write");
        t.setDaemon(true);
        t.start();
    }

    /** The ways of turning the volume tried in turn: a remote key (both code sets), mute, and a direct level. */
    private static final String[] TRY_LABELS = {"Volume up key, code 8", "Volume up key, code 1",
            "Volume down key, code 7", "Volume down key, code 2", "ATT key, code 12", "ATT key, code 4",
            "Mute on (control 03)", "Mute off (control 03)"};
    private static final int[][] TRIES = {{5, 8}, {5, 1}, {5, 7}, {5, 2}, {5, 12}, {5, 4}, {3, 1}, {3, 0}};

    /** Sends one way of changing the volume, then asks whether the stereo reacted, and goes on to the next. */
    private void findVolume(final int i) {
        if (!link.ready()) {
            Toast.makeText(this, "Pick the stereo and let it read first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (i == 0) note("Volume finder: trying each way, one at a time.");
        if (i >= TRIES.length) {
            note("Volume finder done.");
            show("Volume finder done. Tap Save & share.");
            return;
        }
        byte[] st = link.status();
        note("TRY " + (i + 1) + ": " + TRY_LABELS[i] + (st == null ? "" : "  (stereo status " + KenwoodProbe.hex(st) + ")"));
        link.ask(TRY_LABELS[i], KenwoodCommands.CAT_CONTROL, TRIES[i][0], (byte) TRIES[i][1]);
        show("Sent: " + TRY_LABELS[i]);
        main.postDelayed(() -> {
            if (isFinishing()) return;
            new AlertDialog.Builder(this).setTitle("Try " + (i + 1) + " of " + TRIES.length)
                    .setMessage("Sent: " + TRY_LABELS[i] + ".\n\nDid the stereo do anything (volume moved, ATT or MUTE showed)?")
                    .setPositiveButton("Yes", (d, w) -> { note("RESULT " + (i + 1) + ": " + TRY_LABELS[i] + ": the stereo REACTED"); findVolume(i + 1); })
                    .setNegativeButton("No", (d, w) -> { note("RESULT " + (i + 1) + ": " + TRY_LABELS[i] + ": no reaction"); findVolume(i + 1); })
                    .setNeutralButton("Stop", (d, w) -> note("Volume finder stopped at try " + (i + 1) + "."))
                    .setCancelable(false).show();
        }, 1500);
    }

    private void show(String s) { main.post(() -> status.setText(s)); }

    private void disconnect() {
        closing = true;
        running = false;
        link.close();
    }

    // ---------------- save ----------------

    private int buildNumber() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return (int) (Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode);
        } catch (Exception e) {
            return 0;
        }
    }

    private void save() {
        String name = "probe-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
        final String body = "Jeep Dash stereo probe log\nStereo: " + stereoName + "\nTablet: " + Build.MODEL
                + " Android " + Build.VERSION.RELEASE + "\n"
                + log.export(buildNumber(), startedWhen, DiagLog.INFO, null, 400000);
        final Uri uri = DriveLogStore.saveText(this, name, body);
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("Probe log saved")
                .setMessage((uri != null ? "Downloads/JeepDash/" + name + "\n\n" : "")
                        + "Use Share to send it to Claude (Drive, e-mail, etc.).")
                .setNegativeButton("Close", null);
        if (uri != null) b.setPositiveButton("Share", (d, w) -> DriveLogStore.shareText(this, uri, "Kenwood probe log"));
        else b.setPositiveButton("Share as text", (d, w) -> {
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, body.length() > 200000 ? body.substring(body.length() - 200000) : body);
            startActivity(Intent.createChooser(i, "Kenwood probe log"));
        });
        b.show();
    }
}
