package com.strickland.jeepdash.linktest;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int BG = Color.rgb(11, 13, 16);
    private static final int PANEL = Color.rgb(21, 26, 32);
    private static final int TEXT = Color.rgb(232, 237, 242);
    private static final int DIM = Color.rgb(138, 151, 164);
    private static final int ACCENT = Color.rgb(255, 138, 31);

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final StringBuilder logText = new StringBuilder();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SS", Locale.US);

    private TextView status, rpmView, cltView, rateView, sourceView, logView;
    private ScrollView logScroll;
    private Button btnPick, btnIdent, btnGeneric, btnJeep, btnStop;
    private CheckBox rawBox;

    private XtoolLink link;
    private BluetoothDevice chosen;
    private volatile boolean running = false;
    private volatile int readsThisSec = 0;

    // ---------------- UI ----------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout header = row();
        TextView title = text("Jeep Link Test", 22, TEXT, true);
        status = text("Not connected", 15, DIM, false);
        status.setGravity(Gravity.END);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(status, new LinearLayout.LayoutParams(-2, -2));
        root.addView(header);

        TextView steps = text("1. Turn your iPhone's Bluetooth off.  2. Force-stop the GearWrench app.  "
                + "3. Start the Jeep.  4. Pick dongle → Identify → an RPM test → Share log.", 14, DIM, false);
        steps.setPadding(0, dp(6), 0, dp(6));
        root.addView(steps);

        LinearLayout buttons = row();
        btnPick = button("Pick dongle", true);
        btnIdent = button("Identify", false);
        btnGeneric = button("Generic RPM", false);
        btnJeep = button("Jeep RPM", false);
        btnStop = button("Stop", false);
        for (Button b : new Button[]{btnPick, btnIdent, btnGeneric, btnJeep, btnStop}) buttons.addView(b);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.addView(buttons);
        root.addView(hs);

        LinearLayout gauges = row();
        gauges.setPadding(0, dp(10), 0, dp(4));
        rpmView = gauge(gauges, "RPM");
        cltView = gauge(gauges, "COOLANT °F");
        rateView = gauge(gauges, "READS / SEC");
        root.addView(gauges);

        sourceView = text("", 14, DIM, false);
        root.addView(sourceView);

        LinearLayout logBar = row();
        logBar.setPadding(0, dp(8), 0, dp(4));
        logBar.addView(text("Log", 16, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        rawBox = new CheckBox(this);
        rawBox.setText("Raw bytes");
        rawBox.setTextColor(DIM);
        rawBox.setOnCheckedChangeListener((v, on) -> { if (link != null) link.showRaw = on; });
        logBar.addView(rawBox);
        Button copy = button("Copy", false);
        Button share = button("Share log", false);
        copy.setEnabled(true); share.setEnabled(true);
        copy.setOnClickListener(v -> copyLog());
        share.setOnClickListener(v -> shareLog());
        logBar.addView(copy);
        logBar.addView(share);
        root.addView(logBar);

        logScroll = new ScrollView(this);
        logScroll.setBackgroundColor(Color.rgb(7, 9, 11));
        logView = text("", 12, Color.rgb(185, 198, 210), false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setPadding(dp(8), dp(8), dp(8), dp(8));
        logView.setTextIsSelectable(true);
        logScroll.addView(logView);
        root.addView(logScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);

        btnPick.setOnClickListener(v -> pickDongle());
        btnIdent.setOnClickListener(v -> runTest(this::identify));
        btnGeneric.setOnClickListener(v -> runTest(this::genericTest));
        btnJeep.setOnClickListener(v -> runTest(this::jeepTest));
        btnStop.setOnClickListener(v -> running = false);

        log("Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + "), " + Build.MANUFACTURER + " " + Build.MODEL);
        ensurePermission();

        rateView.postDelayed(new Runnable() {
            @Override public void run() {
                if (running) rateView.setText(String.valueOf(readsThisSec));
                readsThisSec = 0;
                rateView.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(primary ? Color.BLACK : TEXT);
        b.setBackgroundColor(primary ? ACCENT : Color.rgb(30, 37, 45));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(52));
        lp.setMargins(0, 0, dp(10), 0);
        b.setLayoutParams(lp);
        b.setPadding(dp(18), 0, dp(18), 0);
        b.setEnabled(primary);
        return b;
    }

    private TextView gauge(LinearLayout parent, String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(PANEL);
        box.setPadding(dp(8), dp(10), dp(8), dp(10));
        TextView v = text("–", 44, TEXT, true);
        v.setGravity(Gravity.CENTER);
        TextView l = text(label, 12, DIM, false);
        l.setGravity(Gravity.CENTER);
        box.addView(v);
        box.addView(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMargins(0, 0, dp(8), 0);
        parent.addView(box, lp);
        return v;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void log(String line) {
        String stamped = clock.format(new Date()) + "  " + line + "\n";
        runOnUiThread(() -> {
            logText.append(stamped);
            if (logText.length() > 200_000) logText.delete(0, logText.length() - 150_000);
            logView.setText(logText);
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void setStatus(String s, int color) { runOnUiThread(() -> { status.setText(s); status.setTextColor(color); }); }

    private void setBusy(boolean busy) {
        runOnUiThread(() -> {
            boolean conn = link != null && link.isOpen();
            btnIdent.setEnabled(conn && !busy);
            btnGeneric.setEnabled(conn && !busy);
            btnJeep.setEnabled(conn && !busy);
            btnStop.setEnabled(conn && busy);
            btnPick.setEnabled(!busy);
        });
    }

    private void copyLog() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Jeep Link Test log", logText.toString()));
        Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
    }

    private void shareLog() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "Jeep Link Test log");
        i.putExtra(Intent.EXTRA_TEXT, logText.toString());
        startActivity(Intent.createChooser(i, "Share log"));
    }

    // ---------------- Bluetooth ----------------

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void ensurePermission() {
        if (!hasConnectPermission()) {
            log("Asking for Nearby devices permission...");
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1);
        } else {
            log("Bluetooth permission OK.");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        log(ok ? "Bluetooth permission granted." : "Bluetooth permission DENIED. Allow 'Nearby devices' for this app in Settings.");
    }

    @SuppressWarnings("MissingPermission")
    private void pickDongle() {
        if (!hasConnectPermission()) { ensurePermission(); return; }
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) { log("Bluetooth is off. Turn it on and try again."); return; }
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        final List<BluetoothDevice> devices = new ArrayList<>(bonded);
        if (devices.isEmpty()) { log("No paired devices. Pair the GWBT dongle in Bluetooth settings first."); return; }
        String[] names = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            BluetoothDevice d = devices.get(i);
            String n = d.getName() == null ? "(no name)" : d.getName();
            names[i] = n;
            StringBuilder uu = new StringBuilder();
            ParcelUuid[] uuids = d.getUuids();
            if (uuids != null) for (ParcelUuid u : uuids) uu.append(' ').append(u.toString(), 0, 8);
            log("Paired: " + n + "  services:" + (uu.length() == 0 ? " (none cached)" : uu));
        }
        new AlertDialog.Builder(this)
                .setTitle("Pick the GWBT dongle")
                .setItems(names, (dlg, which) -> {
                    chosen = devices.get(which);
                    connectTo(chosen);
                })
                .show();
    }

    @SuppressWarnings("MissingPermission")
    private void connectTo(BluetoothDevice dev) {
        setStatus("Connecting...", DIM);
        btnPick.setEnabled(false);
        worker.execute(() -> {
            if (link != null) link.close();
            link = new XtoolLink(this::log);
            link.showRaw = rawBox.isChecked();
            try {
                link.connect(dev);
                setStatus("Connected", Color.rgb(79, 209, 139));
                log("Connected to " + dev.getName() + ". Tap Identify.");
            } catch (Exception e) {
                setStatus("Connect failed", Color.rgb(255, 93, 93));
                log("Could not connect: " + e.getMessage());
            }
            setBusy(false);
        });
    }

    // ---------------- Tests ----------------

    private interface Test { void run() throws Exception; }

    private void runTest(Test t) {
        setBusy(true);
        running = true;
        worker.execute(() -> {
            try { t.run(); } catch (Exception e) { log("Stopped with error: " + e); }
            running = false;
            setBusy(false);
        });
    }

    private void identify() throws Exception {
        String[][] cmds = {{"60 cb", "status"}, {"60 c8", "info"}, {"60 c1", "device id"}, {"60 d3", "hardware"}, {"60 c0", "firmware"}};
        int got = 0;
        for (String[] c : cmds) {
            XtoolLink.Result r = link.send(c[0], true, 1500, false);
            if (r.data != null) {
                got++;
                if (c[0].equals("60 d3")) log("   hardware text: \"" + new String(r.data, "US-ASCII").trim() + "\"");
            }
        }
        log(got > 0 ? "Identify OK: dongle answered " + got + "/5. Now try Generic RPM or Jeep RPM."
                : "Dongle did not answer. Share the log with Claude.");
    }

    private static int sum8(int[] a, int n) { int s = 0; for (int i = 0; i < n; i++) s += a[i]; return s & 0xff; }
    private static int toF(double c) { return (int) Math.round(c * 9 / 5 + 32); }
    private void show(TextView v, int value) { runOnUiThread(() -> v.setText(String.valueOf(value))); }
    private void source(String s) { runOnUiThread(() -> sourceView.setText(s)); }

    private void genericTest() throws Exception {
        source("Source: generic OBD2 (ISO 9141-2)");
        log("--- Generic OBD2 (ISO 9141-2) setup ---");
        for (String c : new String[]{"60 05 02 01", "60 01 09 c5 92", "60 03 00 50 03 e8 00 37 00 05", "61 06 01"})
            link.send(c, false, 1500, false);
        log("Waking the engine computer (takes ~2-3 s)...");
        XtoolLink.Result init = link.send("61 05 1e 33 02 02 01 e0 00 00 00 05", true, 6000, false);
        if (init.data == null) log("Engine computer did not answer the wake-up. Is the key on?");
        link.send("61 04 00 06 68 6a f1 01 00 c4 ff", true, 4000, false);
        for (String c : new String[]{"60 04 00 06 68 6a f1 01 00 c4 02 58 01", "60 06 ff", "60 05 01 01", "60 01 09 c5 92",
                "60 02 00 00 28 a0 00 08", "60 03 00 c8 0b b8 00 37 00 05", "61 06 01"})
            link.send(c, false, 1500, false);
        link.send("60 c3", true, 1500, false);
        log("--- Polling RPM + coolant ---");
        int i = 0;
        while (running && link.isOpen()) {
            int pid = (i++ % 4 == 3) ? 0x05 : 0x0c;
            int[] msg = {0x68, 0x6a, 0xf1, 0x01, pid, 0};
            msg[5] = sum8(msg, 5);
            StringBuilder hx = new StringBuilder("61 04 00 06");
            for (int b : msg) hx.append(String.format(" %02x", b));
            hx.append(" 01");
            XtoolLink.Result r = link.send(hx.toString(), true, 2000, i > 6);
            byte[] d = r.data;
            if (d != null && d.length >= 6 && (d[3] & 0xff) == 0x41 && (d[4] & 0xff) == pid) {
                readsThisSec++;
                if (pid == 0x0c && d.length >= 7) show(rpmView, (((d[5] & 0xff) << 8) | (d[6] & 0xff)) / 4);
                else if (pid == 0x05) show(cltView, toF((d[5] & 0xff) - 40));
            }
        }
        log("Stopped.");
    }

    private void jeepTest() throws Exception {
        source("Source: Jeep engine computer (Chrysler SCI)");
        log("--- Jeep SCI setup ---");
        for (String c : new String[]{"60 05 19 01", "60 01 49 c4 21", "60 02 00 00 1e 84 00 08", "60 03 00 78 07 d0 00 14 00 05"})
            link.send(c, false, 1500, false);
        link.send("61 04 00 01 10 01", true, 3000, false);
        log("--- Polling RPM + coolant ---");
        int i = 0;
        while (running && link.isOpen()) {
            boolean clt = (i++ % 4 == 3);
            link.send("60 02 00 00 f4 24 00 08 ff", false, 1500, i > 6);
            XtoolLink.Result r = link.send(clt ? "61 04 00 02 f4 16 01" : "61 04 00 03 f4 0a 0b 01", true, 2000, i > 6);
            byte[] d = r.data;
            if (d != null) {
                readsThisSec++;
                if (!clt && d.length >= 3) show(rpmView, (((d[0] & 0xff) << 8) | (d[2] & 0xff)) / 8);
                if (clt && d.length >= 2) show(cltView, toF((d[1] & 0xff) - 64));
            }
        }
        log("Stopped.");
    }

    @Override
    protected void onDestroy() {
        running = false;
        if (link != null) link.close();
        worker.shutdownNow();
        super.onDestroy();
    }
}
