package com.strickland.jeepdash;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
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
import com.strickland.jeepdash.core.KenwoodProbe;
import com.strickland.jeepdash.core.KenwoodStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Stereo probe: asks the Kenwood KMM-BT728HD for its current sound settings over the same Bluetooth serial
 * link the KENWOOD Remote app uses, and logs every reply, so the layout of each setting can be worked out
 * before this app ever changes one. Unlike Listen mode it does send: the session opener, read requests and
 * acknowledgements — the list in {@link KenwoodProbe}. It never sends a setting write. One run takes about
 * half a minute; the log is saved and shared the same way as a Listen log.
 */
public class ProbeActivity extends Activity {
    /** The serial service the stock app connects to on the stereo. */
    private static final UUID OPENLINK = UUID.fromString("16f92dc0-2eeb-11e6-b342-0002a5d5c51b");
    private static final int REQ_PERM = 8;
    private static final long STEP_MS = 1500, TAIL_MS = 3000;

    private final DiagLog log = new DiagLog(2000);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final long t0 = System.currentTimeMillis();
    private final String startedWhen = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    private BluetoothAdapter adapter;
    private volatile BluetoothSocket socket;
    private volatile OutputStream out;
    private volatile boolean closing, running;
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
        title.setText("Stereo probe: Kenwood sound settings (read only)");
        col.addView(title);
        TextView how = text(12, Color.LTGRAY);
        how.setText("1 Close the KENWOOD Remote app.  2 Stereo on, engine can be off.  3 Pick the stereo and wait "
                + "about 30 seconds.  4 Save & share the log.");
        col.addView(how);

        LinearLayout top = new LinearLayout(this);
        top.addView(button("Pick stereo & run", v -> pickStereo()));
        top.addView(button("Save & share", v -> save()));
        col.addView(top);

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

    private void start(BluetoothDevice d) {
        disconnect();
        closing = false;
        running = true;
        stereoName = String.valueOf(d.getName());
        note("Picked " + stereoName);
        status.setText("Connecting to " + stereoName + "...");
        Thread t = new Thread(() -> run(d), "probe");
        t.setDaemon(true);
        t.start();
    }

    private void run(BluetoothDevice d) {
        BluetoothSocket s = null;
        for (int attempt = 0; attempt < 2 && s == null && !closing; attempt++) {
            BluetoothSocket cand = null;
            try {
                cand = attempt == 0 ? d.createRfcommSocketToServiceRecord(OPENLINK)
                        : d.createInsecureRfcommSocketToServiceRecord(OPENLINK);
                try { adapter.cancelDiscovery(); } catch (RuntimeException ignored) { }   // needs the scan permission; not vital
                cand.connect();
                s = cand;
            } catch (IOException | RuntimeException e) {
                fail("Connect " + (attempt == 0 ? "secure" : "insecure") + " failed: " + e.getMessage());
                try { if (cand != null) cand.close(); } catch (IOException ignored) { }
            }
        }
        if (s == null) {
            running = false;
            show("Could not connect. Is the KENWOOD Remote app closed and the stereo on?");
            return;
        }
        socket = s;
        try {
            out = s.getOutputStream();
            final InputStream in = s.getInputStream();
            Thread r = new Thread(() -> readLoop(in), "probe-read");
            r.setDaemon(true);
            r.start();
        } catch (IOException e) {
            fail("No streams: " + e.getMessage());
            running = false;
            show("Connected but could not open the link.");
            return;
        }
        note("Connected on the OpenLink serial service.");
        show("Connected. Asking...");
        KenwoodProbe probe = new KenwoodProbe();
        try {
            Thread.sleep(STEP_MS);                                   // let the stereo speak first, if it does
            while (!closing && !probe.done()) {
                KenwoodProbe.Step step = probe.peek();
                byte[] frame = probe.nextFrame();
                send(frame, "> " + step.label + ": " + KenwoodProbe.describe(frame));
                Thread.sleep(STEP_MS);
            }
            Thread.sleep(TAIL_MS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        running = false;
        if (!closing) {
            note("Probe finished. " + probe.stepCount() + " questions asked.");
            show("Finished. Tap Save & share.");
        }
    }

    private void send(byte[] frame, String line) {
        OutputStream o = out;
        if (o == null) return;
        try {
            synchronized (o) {
                o.write(frame);
                o.flush();
            }
            log.info(now(), DiagLog.CMD, line);
        } catch (IOException e) {
            log.error(now(), DiagLog.CMD, "Send failed (" + line + "): " + e.getMessage());
        }
    }

    private void readLoop(InputStream in) {
        KenwoodStream stream = new KenwoodStream();
        byte[] buf = new byte[1024];
        try {
            int n;
            while (!closing && (n = in.read(buf)) > 0) {
                for (byte[] frame : stream.feed(buf, n)) {
                    log.info(now(), DiagLog.CMD, "< " + KenwoodProbe.describe(frame));
                    byte[] ack = KenwoodProbe.ackFor(frame);
                    if (ack != null) send(ack, "> ack");
                }
            }
        } catch (IOException e) {
            if (!closing) fail("Link closed: " + e.getMessage());
        }
        if (stream.strayBytes() > 0) note(stream.strayBytes() + " bytes arrived outside any frame.");
    }

    private void show(String s) { main.post(() -> status.setText(s)); }

    private void disconnect() {
        closing = true;
        running = false;
        out = null;
        BluetoothSocket s = socket;
        socket = null;
        try { if (s != null) s.close(); } catch (IOException ignored) { }
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
