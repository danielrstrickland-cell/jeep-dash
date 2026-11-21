package com.strickland.jeepdash;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import com.strickland.jeepdash.core.Catalog;
import com.strickland.jeepdash.core.DongleSession;
import com.strickland.jeepdash.core.DriveLogWriter;
import com.strickland.jeepdash.core.LiveValues;
import com.strickland.jeepdash.core.Param;
import com.strickland.jeepdash.core.PollRunner;
import com.strickland.jeepdash.core.PollScheduler;
import com.strickland.jeepdash.core.Trace;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the dongle connection alive in the background: connects, runs the read loop, reconnects on its own,
 * and writes the drive log. Read-only toward the Jeep (see PollRunner / Protocols).
 */
public class LinkService extends Service {

    public enum Status { SEARCHING, CONNECTING, SETTING_UP, LIVE, RECONNECTING, BUSY, NO_PERMISSION }

    /** Both callbacks arrive on the main thread. onValues comes about ten times a second. */
    public interface Listener {
        void onValues(LiveValues v);
        void onStatus(Status s, String detail);
    }

    public final class LocalBinder extends Binder {
        public LinkService service() { return LinkService.this; }
    }

    static final String ACTION_STOP = "com.strickland.jeepdash.STOP";
    static final String BUSY_DETAIL =
            "Can't reach the dongle. Check it's plugged in, then close the GearWrench app and turn off your iPhone's Bluetooth.";
    static final String KEY_OFF_DETAIL = "Waiting for the engine computer. Turn the key on.";

    private static final String CHANNEL = "link";
    private static final int NOTIFICATION_ID = 1;
    private static final long GENERIC_EVERY_MS = 10000;
    private static final long RETRY_MS = 3000;
    private static final long IDLE_STOP_MS = 120000;       // dash closed and no data from the Jeep: let go of the dongle
    private static final long DATA_GAP_MS = 30000;         // rows keep going through short gaps (generic blocks, stalls)
    private static final long LOG_CLOSE_MS = 300000;       // 5 minutes without data ends the drive's log

    /** The clock every reading is stamped with. Use this (not wall time) when asking LiveValues for a value. */
    public static long now() { return SystemClock.elapsedRealtime(); }

    private final LocalBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final LiveValues values = new LiveValues();
    private final Trace trace = new Trace(2000);

    private volatile Set<String> active = new LinkedHashSet<>();
    private volatile boolean activeDirty;
    private volatile Status status = Status.SEARCHING;
    private volatile String detail = "";
    private volatile boolean running;
    private volatile int generation;        // each worker thread has its own; an old one stops when it changes
    private volatile boolean anyBound;
    private volatile boolean foreground;
    private volatile DongleSession session;
    private volatile PollRunner runner;
    private volatile int readsPerSec;
    private Thread worker;
    private ScheduledExecutorService ticker;

    // ticker-thread state
    private PollRunner countedRunner;
    private int countedReads;
    private long lastUsefulMs;
    private DriveLogWriter log;
    private long logStartMs, lastDataMs, logRetryAfterMs;
    private int rowsSinceFlush;

    // ---------------- what the dash uses ----------------

    public void addListener(Listener l) {
        listeners.add(l);
        l.onStatus(status, detail);
    }

    public void removeListener(Listener l) { listeners.remove(l); }

    /** The values on screen right now; only these are read from the Jeep. */
    public void setActiveParams(Set<String> ids) {
        active = new LinkedHashSet<>(ids);
        activeDirty = true;
    }

    public int readsPerSecond() { return readsPerSec; }

    public LiveValues values() { return values; }

    public Status status() { return status; }

    public String statusDetail() { return detail; }

    /** Everything said to the dongle lately and what came back, for sharing when something misbehaves. */
    public String linkLog() {
        return "Jeep Dash link log\nAndroid " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + "), "
                + Build.MANUFACTURER + " " + Build.MODEL + "\nStatus now: " + status + " " + detail
                + "\nReading: " + active + "\n\n" + trace.snapshot();
    }

    private void note(String line) {
        trace.add(String.format(java.util.Locale.US, "%8.2f  %s", now() / 1000.0, line));
    }

    // ---------------- service plumbing ----------------

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Dongle connection", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        lastUsefulMs = now();
        ticker = Executors.newSingleThreadScheduledExecutor();
        ticker.scheduleWithFixedDelay(this::safeTick, 100, 100, TimeUnit.MILLISECONDS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutDown();
            return START_NOT_STICKY;
        }
        if (!hasBluetoothPermission()) {
            setStatus(Status.NO_PERMISSION, "Allow Nearby devices for Jeep Dash");
            return START_NOT_STICKY;
        }
        goForeground();
        startWorker();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        anyBound = true;
        return binder;
    }

    @Override
    public void onRebind(Intent intent) { anyBound = true; }

    @Override
    public boolean onUnbind(Intent intent) {
        anyBound = false;
        return true;
    }

    @Override
    public void onDestroy() {
        stopWorker();
        if (ticker != null) {
            ticker.execute(this::closeLog);
            ticker.shutdown();
        }
        super.onDestroy();
    }

    private void shutDown() {
        stopWorker();
        setStatus(Status.SEARCHING, "Stopped. Open Jeep Dash to reconnect.");
        if (foreground) {
            foreground = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
        stopSelf();
    }

    private boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void goForeground() {
        if (foreground) return;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification());
            }
            foreground = true;
        } catch (RuntimeException e) {
            // Not allowed right now (app not in front). The link still runs while the dash is open.
            foreground = false;
        }
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, DashActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, LinkService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Jeep Dash")
                .setContentText(statusLine(status))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "Stop", stop).build())
                .build();
    }

    static String statusLine(Status s) {
        switch (s) {
            case LIVE: return "Connected to the dongle";
            case CONNECTING: return "Connecting to the dongle";
            case SETTING_UP: return "Setting up";
            case RECONNECTING: return "Reconnecting";
            case BUSY: return "Can't reach the dongle";
            case NO_PERMISSION: return "Needs the Nearby devices permission";
            default: return "Looking for the dongle";
        }
    }

    private void setStatus(Status s, String d) {
        final String dd = d == null ? "" : d;
        final Status old = status;
        if (s == old && dd.equals(detail)) return;
        status = s;
        detail = dd;
        note("== " + s + (dd.isEmpty() ? "" : ": " + dd));
        main.post(() -> {
            for (Listener l : listeners) l.onStatus(s, dd);
            if (foreground && s != old) {
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) {
                    try { nm.notify(NOTIFICATION_ID, buildNotification()); } catch (RuntimeException ignored) { }
                }
            }
        });
    }

    // ---------------- the connection loop ----------------

    private synchronized void startWorker() {
        if (running && worker != null && worker.isAlive()) return;
        running = true;
        final int gen = ++generation;
        worker = new Thread(() -> workLoop(gen), "dongle-link");
        worker.setDaemon(true);
        worker.start();
    }

    private synchronized void stopWorker() {
        running = false;
        generation++;
        DongleSession s = session;
        if (s != null) s.close();
        if (worker != null) worker.interrupt();
        worker = null;
    }

    private boolean alive(int gen) { return running && gen == generation; }

    private void workLoop(int gen) {
        boolean everLive = false;
        int failures = 0;
        SharedPreferences prefs = getSharedPreferences("link", MODE_PRIVATE);
        while (alive(gen)) {
            try {
                if (!hasBluetoothPermission()) {
                    setStatus(Status.NO_PERMISSION, "Allow Nearby devices for Jeep Dash");
                    pause(RETRY_MS);
                    continue;
                }
                String[] why = {""};
                BluetoothDevice dev = findDongle(prefs.getString("address", null), why);
                if (dev == null) {
                    setStatus(Status.SEARCHING, why[0]);
                    pause(RETRY_MS);
                    continue;
                }
                if (failures == 0) setStatus(everLive ? Status.RECONNECTING : Status.CONNECTING, "");
                AndroidByteLink link = connect(dev, prefs.getInt("mode", -1), failures, gen);
                if (link == null) {
                    failures++;
                    setStatus(everLive ? Status.RECONNECTING : Status.BUSY, BUSY_DETAIL);
                    pause(RETRY_MS);
                    continue;
                }
                failures = 0;
                prefs.edit().putString("address", dev.getAddress()).putInt("mode", link.mode).apply();
                if (runConnection(link, gen)) everLive = true;
                if (alive(gen)) {
                    setStatus(Status.RECONNECTING, "Lost the dongle. Reconnecting.");
                    pause(RETRY_MS);
                }
            } catch (SecurityException e) {
                setStatus(Status.NO_PERMISSION, "Allow Nearby devices for Jeep Dash");
                pause(RETRY_MS);
            } catch (RuntimeException e) {
                // Never let the link thread die: report and try again.
                setStatus(everLive ? Status.RECONNECTING : Status.SEARCHING, "Link error: " + e);
                pause(RETRY_MS);
            }
        }
    }

    private void pause(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    @SuppressLint("MissingPermission")
    private BluetoothDevice findDongle(String rememberedAddress, String[] why) {
        BluetoothManager bm = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            why[0] = "Turn Bluetooth on";
            return null;
        }
        BluetoothDevice remembered = null;
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded != null) {
            for (BluetoothDevice d : bonded) {
                String n = d.getName();
                if (n != null && n.startsWith("GWBT-")) return d;
                if (d.getAddress().equals(rememberedAddress)) remembered = d;
            }
        }
        if (remembered == null) why[0] = "Pair the GWBT dongle in Bluetooth settings";
        return remembered;
    }

    /** Tries the way that worked last time first; every fourth failed round tries all three ways. */
    private AndroidByteLink connect(BluetoothDevice dev, int lastGoodMode, int failures, int gen) {
        boolean tryAll = lastGoodMode < 0 || lastGoodMode >= AndroidByteLink.MODES || failures % 4 == 3;
        for (int i = 0; i < AndroidByteLink.MODES && alive(gen); i++) {
            int mode = tryAll ? i : lastGoodMode;
            try {
                AndroidByteLink link = AndroidByteLink.connect(dev, mode);
                note("connected (way " + mode + ")");
                return link;
            } catch (IOException e) {
                note("connect (way " + mode + ") failed: " + e.getMessage());
            }
            if (!tryAll) break;
        }
        return null;
    }

    /** Runs the read loop until the link is lost or the service stops. True if it ever went live. */
    private boolean runConnection(AndroidByteLink link, int gen) {
        final DongleSession s = new DongleSession(link);
        session = s;
        final InputStream in = link.input();
        Thread reader = new Thread(() -> {
            byte[] buf = new byte[1024];
            try {
                while (true) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    if (n > 0) s.onBytes(buf, n);
                }
            } catch (IOException ignored) {
                // socket closed or dongle gone
            } catch (RuntimeException ignored) {
                // malformed data must not kill the app; drop the link and reconnect
            }
            s.close();
        }, "dongle-reader");
        reader.setDaemon(true);
        reader.start();

        PollRunner r = new PollRunner(s, new PollScheduler(GENERIC_EVERY_MS), values, LinkService::now);
        r.setTrace(trace);
        activeDirty = false;
        r.setActive(active);
        runner = r;
        setStatus(Status.SETTING_UP, "");
        boolean live = false;
        try {
            while (alive(gen)) {
                if (activeDirty) {
                    activeDirty = false;
                    r.setActive(active);
                }
                PollScheduler.Kind k = r.step();
                live = true;
                setStatus(Status.LIVE, r.vehicleAnswering() ? "" : KEY_OFF_DETAIL);
                if (k == PollScheduler.Kind.IDLE) pause(200);
            }
        } catch (IOException e) {
            note("link lost: " + e.getMessage());
        } finally {
            if (runner == r) runner = null;
            if (session == s) session = null;
            s.close();
        }
        return live;
    }

    // ---------------- ten times a second: screen updates, reads/s, drive log ----------------

    private int tickCount;

    private void safeTick() {
        try {
            tick();
        } catch (RuntimeException ignored) {
            // a bad tick must not cancel the schedule
        }
    }

    private void tick() {
        if (!listeners.isEmpty()) {
            main.post(() -> {
                for (Listener l : listeners) l.onValues(values);
            });
        }
        if (++tickCount % 10 != 0) return;

        // once a second
        long t = now();
        PollRunner r = runner;
        if (r != countedRunner) {
            countedRunner = r;
            countedReads = 0;
        }
        int total = r == null ? 0 : r.goodReads();
        int perSec = Math.max(0, total - countedReads);
        countedReads = total;
        readsPerSec = perSec;

        // "Data" means the engine computer is answering. The dongle stays powered with the key off,
        // so being connected alone must not keep the log growing or the service running.
        if (status == Status.LIVE && perSec > 0) lastDataMs = t;
        boolean dataLately = lastDataMs > 0 && t - lastDataMs < DATA_GAP_MS;
        if (dataLately && log == null && t >= logRetryAfterMs) openLog(t);
        if (log != null) {
            if (dataLately) writeRow(t);
            else if (t - lastDataMs > LOG_CLOSE_MS) closeLog();
        }

        if (anyBound || dataLately) lastUsefulMs = t;
        else if (running && t - lastUsefulMs > IDLE_STOP_MS) main.post(this::shutDown);
    }

    private void openLog(long t) {
        try {
            List<String> ids = new ArrayList<>();
            for (Param p : Catalog.all()) ids.add(p.id);
            log = DriveLogStore.open(this, ids);
            logStartMs = t;
            rowsSinceFlush = 0;
            if (log == null) logRetryAfterMs = Long.MAX_VALUE;   // not supported on this Android version
        } catch (IOException e) {
            log = null;
            logRetryAfterMs = t + 60000;
        }
    }

    private void writeRow(long t) {
        try {
            log.row(t - logStartMs, t, values);
            if (++rowsSinceFlush >= 5) {
                rowsSinceFlush = 0;
                log.flush();
            }
        } catch (IOException e) {
            closeLog();
            logRetryAfterMs = t + 60000;
        }
    }

    private void closeLog() {
        DriveLogWriter l = log;
        log = null;
        if (l == null) return;
        try { l.close(); } catch (IOException ignored) { }
    }
}
