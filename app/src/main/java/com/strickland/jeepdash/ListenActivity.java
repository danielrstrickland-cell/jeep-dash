package com.strickland.jeepdash;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.strickland.jeepdash.core.ListenLog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Listen mode: a strictly passive look at the Bluetooth conversation of the Kenwood KMM-BT728HD, to learn the
 * protocol the KENWOOD Remote app uses. It lists the stereo's services (classic SDP and BLE GATT), connects to
 * every one it can, and only listens: the sole things ever sent are the connection itself, reads of readable
 * GATT characteristics and the GATT "subscribe" descriptor write. No command is ever written to the stereo.
 * Every received byte is logged with a millisecond timestamp; label buttons put marker lines in the log.
 */
public class ListenActivity extends Activity {
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int REQ_PERM = 7;

    private final ListenLog log = new ListenLog();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService rfcommQueue = Executors.newSingleThreadExecutor();
    private final Set<UUID> seenUuids = new LinkedHashSet<>();
    private final List<BluetoothSocket> sockets = new ArrayList<>();
    private final Queue<Runnable> gattOps = new ArrayDeque<>();
    private BluetoothAdapter adapter;
    private BluetoothDevice device;
    private BluetoothGatt gatt;
    private volatile boolean closing;
    private boolean uuidReceiverOn;
    private String stereoName = "(none)";

    private TextView status, services, logView, timing;
    private EditText freeText;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            logView.setText(log.tail(40));
            timing.setText(log.timingReport());
            main.postDelayed(this, 500);
        }
    };

    private final BroadcastReceiver uuidReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (d == null || device == null || !d.getAddress().equals(device.getAddress())) return;
            android.os.Parcelable[] p = i.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID);
            if (p == null) return;
            List<UUID> found = new ArrayList<>();
            for (android.os.Parcelable x : p) found.add(((ParcelUuid) x).getUuid());
            addServiceUuids(found, "SDP");
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildScreen();
        BluetoothManager m = getSystemService(BluetoothManager.class);
        adapter = m == null ? null : m.getAdapter();
        log.info(now(), "Listen mode opened. Passive only: nothing is written to the stereo.");
        main.post(refresh);
        if (adapter == null) status.setText("This tablet has no Bluetooth.");
        else if (!hasBt()) askBt();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(refresh);
        disconnectAll();
        rfcommQueue.shutdownNow();
        super.onDestroy();
    }

    // ---------------- permissions ----------------

    private boolean hasBt() {
        if (Build.VERSION.SDK_INT < 31) return true;       // older Android: pairing list needs only the normal Bluetooth permission
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
    }

    private void askBt() {
        if (Build.VERSION.SDK_INT >= 31)
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN}, REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_PERM && !hasBt())
            status.setText("Bluetooth permission was not allowed, so the paired devices cannot be listed. Tap \"Pick stereo\" to ask again.");
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
        title.setText("Listen mode: Kenwood stereo (passive)");
        col.addView(title);
        TextView how = text(12, Color.LTGRAY);
        how.setText("1 Close the KENWOOD Remote app.  2 Pick the stereo.  3 Change ONE setting on the stereo's knob.  "
                + "4 Tap the matching label below.  5 Repeat.  6 Finish & upload.");
        col.addView(how);

        LinearLayout top = new LinearLayout(this);
        top.addView(button("Pick stereo", v -> pickStereo()));
        top.addView(button("Finish & upload", v -> finish_()));
        col.addView(top);

        status = text(14, Color.rgb(120, 220, 140));
        status.setText("Not connected.");
        col.addView(status);
        services = text(11, Color.rgb(150, 190, 255));
        col.addView(services);

        TextView lh = text(14, Color.WHITE);
        lh.setText("\nLabels (tap right after the change on the stereo)");
        col.addView(lh);
        LinearLayout row = null;
        int n = 0;
        for (String label : ListenLog.PRESETS) {
            if (n++ % 5 == 0) {
                row = new LinearLayout(this);
                col.addView(row);
            }
            Button x = button(label, v -> log.mark(now(), label));
            x.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(x);
        }
        LinearLayout free = new LinearLayout(this);
        freeText = new EditText(this);
        freeText.setHint("Free-text label");
        freeText.setTextColor(Color.WHITE);
        freeText.setHintTextColor(Color.GRAY);
        free.addView(freeText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        free.addView(button("Add label", v -> {
            String s = freeText.getText().toString().trim();
            if (s.isEmpty()) return;
            log.mark(now(), s);
            freeText.setText("");
        }));
        col.addView(free);

        TextView th = text(14, Color.WHITE);
        th.setText("\nSpeed test (message timing, no writes)");
        col.addView(th);
        timing = text(11, Color.rgb(255, 210, 120));
        col.addView(timing);

        TextView lg = text(14, Color.WHITE);
        lg.setText("\nLog (latest lines)");
        col.addView(lg);
        logView = text(10, Color.LTGRAY);
        logView.setGravity(Gravity.START);
        col.addView(logView);
        setContentView(scroll);
    }

    private static long now() { return System.currentTimeMillis(); }

    // ---------------- picking and connecting ----------------

    private void pickStereo() {
        if (adapter == null) return;
        if (!hasBt()) {
            askBt();
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
                .setItems(names, (d, which) -> connect(paired.get(which)))
                .setNegativeButton("Cancel", null).show();
    }

    private void connect(BluetoothDevice d) {
        disconnectAll();
        closing = false;
        device = d;
        stereoName = d.getName() + " " + d.getAddress();
        seenUuids.clear();
        log.info(now(), "Chosen: " + stereoName + " type=" + d.getType() + " bond=" + d.getBondState());
        status.setText("Chosen: " + stereoName + ". Looking up services...");
        if (!uuidReceiverOn) {
            registerReceiver(uuidReceiver, new IntentFilter(BluetoothDevice.ACTION_UUID));
            uuidReceiverOn = true;
        }
        List<UUID> cached = new ArrayList<>();
        ParcelUuid[] have = d.getUuids();
        if (have != null) for (ParcelUuid p : have) cached.add(p.getUuid());
        cached.add(SPP);                       // always try standard SPP
        addServiceUuids(cached, "cached/SPP");
        try {
            d.fetchUuidsWithSdp();             // fresh SDP lookup: answers arrive at uuidReceiver
        } catch (RuntimeException e) {
            log.info(now(), "SDP lookup failed: " + e);
        }
        startGatt(d);
    }

    /** Logs and shows new classic service UUIDs, and queues a listen-only RFCOMM connection to each. */
    private void addServiceUuids(List<UUID> list, String from) {
        for (final UUID u : list) {
            if (!seenUuids.add(u)) continue;
            log.info(now(), "Classic service UUID (" + from + "): " + u);
            showService("classic " + u);
            final BluetoothDevice d = device;
            rfcommQueue.execute(() -> tryRfcomm(d, u));
        }
    }

    private void showService(String line) {
        main.post(() -> services.append(line + "\n"));
    }

    private void tryRfcomm(BluetoothDevice d, UUID u) {
        if (closing || d == null) return;
        BluetoothSocket s = null;
        for (int attempt = 0; attempt < 2 && s == null && !closing; attempt++) {
            BluetoothSocket cand = null;
            try {
                cand = attempt == 0 ? d.createRfcommSocketToServiceRecord(u) : d.createInsecureRfcommSocketToServiceRecord(u);
                if (adapter != null) adapter.cancelDiscovery();
                cand.connect();
                s = cand;
            } catch (IOException | RuntimeException e) {
                log.info(now(), "RFCOMM " + u + (attempt == 0 ? " secure" : " insecure") + " failed: " + e.getMessage());
                try { if (cand != null) cand.close(); } catch (IOException ignored) { }
            }
        }
        if (s == null) return;
        final BluetoothSocket sock = s;
        synchronized (sockets) { sockets.add(sock); }
        log.info(now(), "RFCOMM connected on " + u + ". Listening.");
        main.post(() -> status.setText("Connected (classic) on " + u));
        Thread t = new Thread(() -> readLoop(sock, u), "listen-" + u);
        t.setDaemon(true);
        t.start();
    }

    private void readLoop(BluetoothSocket s, UUID u) {
        byte[] buf = new byte[1024];
        try {
            InputStream in = s.getInputStream();
            int n;
            while (!closing && (n = in.read(buf)) > 0) {
                byte[] got = new byte[n];
                System.arraycopy(buf, 0, got, 0, n);
                log.rx(now(), "rfcomm:" + u, got);
            }
        } catch (IOException e) {
            if (!closing) log.info(now(), "RFCOMM " + u + " closed: " + e.getMessage());
        }
    }

    // ---------------- BLE: discover, read readable, subscribe to notify/indicate ----------------

    private void startGatt(BluetoothDevice d) {
        try {
            gatt = Build.VERSION.SDK_INT >= 23
                    ? d.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                    : d.connectGatt(this, false, gattCallback);
        } catch (RuntimeException e) {
            log.info(now(), "BLE connect failed to start: " + e);
        }
    }

    private void nextGatt() {
        Runnable r;
        synchronized (gattOps) { r = gattOps.poll(); }
        if (r != null) r.run();
    }

    private void onGattData(BluetoothGattCharacteristic c, byte[] v, String how) {
        log.rx(now(), how + ":" + c.getUuid(), v == null ? new byte[0] : v);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int st, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                log.info(now(), "BLE connected, discovering services");
                main.post(() -> status.append("\nBLE connected"));
                g.discoverServices();
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                log.info(now(), "BLE disconnected (status " + st + ")");
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int st) {
            for (BluetoothGattService s : g.getServices()) {
                log.info(now(), "GATT service " + s.getUuid());
                showService("ble service " + s.getUuid());
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    int p = c.getProperties();
                    String props = ((p & BluetoothGattCharacteristic.PROPERTY_READ) != 0 ? "R" : "")
                            + ((p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ? "W" : "")
                            + ((p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 ? "w" : "")
                            + ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ? "N" : "")
                            + ((p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 ? "I" : "");
                    log.info(now(), "  characteristic " + c.getUuid() + " [" + props + "]");
                    showService("   char " + c.getUuid() + " [" + props + "]");
                    final BluetoothGattCharacteristic ch = c;
                    if ((p & BluetoothGattCharacteristic.PROPERTY_READ) != 0)
                        synchronized (gattOps) { gattOps.add(() -> { if (!g.readCharacteristic(ch)) nextGatt(); }); }
                    if ((p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0)
                        synchronized (gattOps) { gattOps.add(() -> subscribe(g, ch)); }
                }
            }
            nextGatt();
        }

        @Override @SuppressWarnings("deprecation")
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int st) {
            if (Build.VERSION.SDK_INT < 33) { onGattData(c, c.getValue(), "ble-read"); nextGatt(); }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v, int st) {
            onGattData(c, v, "ble-read");
            nextGatt();
        }

        @Override @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < 33) onGattData(c, c.getValue(), "ble-notify");
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v) {
            onGattData(c, v, "ble-notify");
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int st) {
            nextGatt();
        }
    };

    /** Turns on notifications/indications: the standard subscribe, not a command to the stereo. */
    @SuppressWarnings("deprecation")
    private void subscribe(BluetoothGatt g, BluetoothGattCharacteristic c) {
        try {
            g.setCharacteristicNotification(c, true);
            BluetoothGattDescriptor d = c.getDescriptor(CCCD);
            if (d == null) { nextGatt(); return; }
            boolean notify = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
            byte[] v = notify ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
            boolean ok;
            if (Build.VERSION.SDK_INT >= 33) ok = g.writeDescriptor(d, v) == 0;
            else { d.setValue(v); ok = g.writeDescriptor(d); }
            if (!ok) nextGatt();
        } catch (RuntimeException e) {
            log.info(now(), "subscribe failed " + c.getUuid() + ": " + e);
            nextGatt();
        }
    }

    private void disconnectAll() {
        closing = true;
        synchronized (gattOps) { gattOps.clear(); }
        try { if (gatt != null) { gatt.disconnect(); gatt.close(); } } catch (RuntimeException ignored) { }
        gatt = null;
        synchronized (sockets) {
            for (BluetoothSocket s : sockets) try { s.close(); } catch (IOException ignored) { }
            sockets.clear();
        }
        if (uuidReceiverOn) {
            try { unregisterReceiver(uuidReceiver); } catch (RuntimeException ignored) { }
            uuidReceiverOn = false;
        }
    }

    // ---------------- finish ----------------

    /**
     * Stops listening, saves the log in the app's own folder (local copy) and in Downloads/JeepDash, then offers
     * Share. The app holds no GitHub token (its only online use is the public update check), so it never uploads
     * by itself: the log is shared from here (Drive, e-mail, Messages...) or picked up from Downloads/JeepDash.
     */
    private void finish_() {
        disconnectAll();
        String name = "listen-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
        String header = "Jeep Dash Listen mode log\nStereo: " + stereoName + "\nServices:\n"
                + services.getText() + "\nBuild: " + Build.MODEL + " Android " + Build.VERSION.RELEASE;
        String body = log.file(header);
        String where = "";
        File dir = getExternalFilesDir("listen-logs");
        if (dir != null) {
            dir.mkdirs();
            try (FileOutputStream o = new FileOutputStream(new File(dir, name))) {
                o.write(body.getBytes(StandardCharsets.UTF_8));
                where += "App copy: " + new File(dir, name).getPath() + "\n";
            } catch (IOException e) {
                where += "App copy failed: " + e.getMessage() + "\n";
            }
        }
        final Uri uri = DriveLogStore.saveText(this, name, body);
        if (uri != null) where += "Downloads/JeepDash/" + name + "\n";
        log.info(now(), "Saved " + name);
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("Log saved")
                .setMessage(where + "\nThe app has no upload account, so use Share to send it to Claude (Drive, e-mail, etc.).")
                .setNegativeButton("Close", null);
        if (uri != null) b.setPositiveButton("Share", (d, w) -> DriveLogStore.shareText(this, uri, "Kenwood listen log"));
        else b.setPositiveButton("Share as text", (d, w) -> {
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, body.length() > 200000 ? body.substring(body.length() - 200000) : body);
            startActivity(Intent.createChooser(i, "Kenwood listen log"));
        });
        b.show();
    }
}
