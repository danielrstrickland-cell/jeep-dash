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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.os.Binder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import com.strickland.jeepdash.core.Catalog;
import com.strickland.jeepdash.core.DongleSession;
import com.strickland.jeepdash.core.DriveLogWriter;
import com.strickland.jeepdash.core.FuelUse;
import com.strickland.jeepdash.core.Gears;
import com.strickland.jeepdash.core.KeepAlive;
import com.strickland.jeepdash.core.LiveValues;
import com.strickland.jeepdash.core.Motion;
import com.strickland.jeepdash.core.MisfireCodes;
import com.strickland.jeepdash.core.NormalReadings;
import com.strickland.jeepdash.core.Param;
import com.strickland.jeepdash.core.PauseGate;
import com.strickland.jeepdash.core.RollingCapture;
import com.strickland.jeepdash.core.KwpRunner;
import com.strickland.jeepdash.core.PollRunner;
import com.strickland.jeepdash.core.Poller;
import com.strickland.jeepdash.core.PollScheduler;
import com.strickland.jeepdash.core.SciCodes;
import com.strickland.jeepdash.core.SpeedCorrection;
import com.strickland.jeepdash.core.SpeedVolume;
import com.strickland.jeepdash.core.TimingPulled;
import com.strickland.jeepdash.core.Trace;
import com.strickland.jeepdash.core.ValueScan;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the dongle connection alive in the background: connects, runs the read loop, reconnects on its own,
 * and writes the drive log. Read-only toward either car, except for clearing stored codes when the owner asks
 * (see PollRunner / Protocols).
 */
public class LinkService extends Service {

    public enum Status { SEARCHING, CONNECTING, SETTING_UP, LIVE, RECONNECTING, BUSY, NO_PERMISSION, PAUSED }

    /** Both callbacks arrive on the main thread. onValues comes about ten times a second. */
    public interface Listener {
        void onValues(LiveValues v);
        void onStatus(Status s, String detail);
    }

    /** Answer to a codes request, on the main thread. codes is null when the engine computer gave no list. */
    public interface CodesListener {
        void onCodes(List<Integer> codes, SciCodes.Outcome cleared);
    }

    private static final class CodesJob {
        final boolean clear;
        final CodesListener listener;
        CodesJob(boolean clear, CodesListener listener) { this.clear = clear; this.listener = listener; }
    }

    public final class LocalBinder extends Binder {
        public LinkService service() { return LinkService.this; }
    }

    static final String ACTION_STOP = "com.strickland.jeepdash.STOP";
    static final String BUSY_DETAIL =
            "Can't reach the dongle. Check it's plugged in, then close the GearWrench app and turn off your iPhone's Bluetooth.";
    static final String KEY_OFF_DETAIL = "Waiting for the engine computer. Turn the key on.";

    /** Shown on the dash while the Jeep Tune app has the dongle. */
    static final String PAUSED_DETAIL = "Paused \u2014 Jeep Tune reading";

    // ---------------- pause while Jeep Tune reads (see BusyReceiver and core PauseGate) ----------------

    /**
     * Jeep Tune's busy broadcasts land here, in this process even when the service is not running, so the
     * state is already right when the service starts. The 90 s failsafe is in the gate.
     */
    private static final PauseGate GATE = new PauseGate(LinkService::now);
    private static volatile LinkService instance;

    /** True while Jeep Tune has the dongle: nothing here may talk to it. */
    static boolean tunePaused() { return GATE.isPaused(); }

    static void onTuneBusy(boolean busy) {
        GATE.onBusy(busy);
        LinkService s = instance;
        if (s != null) s.note("Jeep Tune busy=" + busy);
    }

    private static final String CHANNEL = "link";
    private static final int NOTIFICATION_ID = 1;
    private static final long OTHER_LINE_EVERY_MS = 60000;
    private static final long RETRY_MS = 3000;
    /** Shown when the dongle is not paired and the app may not look for it yet; the dash asks when it sees this. */
    static final String NEED_SCAN_DETAIL = "Allow the permission so the app can find the dongle";
    private static final int SCAN_SECONDS = 13;
    private static final long IDLE_STOP_MS = 120000;       // dash closed and no data from the Jeep: let go of the dongle
    private static final long DATA_GAP_MS = 30000;         // rows keep going through short gaps (generic blocks, stalls)
    private static final long LOG_CLOSE_MS = 300000;       // 5 minutes without data ends the drive's log

    /** The clock every reading is stamped with. Use this (not wall time) when asking LiveValues for a value. */
    public static long now() { return SystemClock.elapsedRealtime(); }

    // When the current drive started (on the now() clock), 0 before the first: the same moment the drive log
    // starts, when the engine computer answers after 5 minutes or more of silence. "This drive" gauges use it.
    private static volatile long driveStartedMs;
    private boolean driving;

    public static long driveStartedMs() { return driveStartedMs; }

    private final LocalBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final LiveValues values = new LiveValues();
    private final Trace trace = new Trace(2000);
    // Misfire catcher: the last 30 s of readings, kept in memory; see MisfireCodes and RollingCapture.
    private RollingCapture misfire;

    private volatile Set<String> active = new LinkedHashSet<>();
    private volatile Set<String> requested = new LinkedHashSet<>();     // what the screen in front asked for
    private volatile Set<String> dashShown = new LinkedHashSet<>();     // the dash's gauges, in front or not
    private FuelUse fuel;                   // the one fuel counter (see FuelStore); fed here, every tick
    private NormalReadings normal;          // the learning alerts (see NormalStore); fed here once a second
    private volatile boolean learnNearIdle, learnStoppedWarm;      // what it needs read right now (refreshActive)
    private final TimingPulled timing = new TimingPulled();        // the Jeep's "Timing pulled" gauge
    private volatile ValueScan scan;        // "Scan all values": the one under way, or the last one
    private volatile boolean scanOpen;      // a scan is under way: live data waits for it
    private volatile Uri scanUri;           // where the last scan was saved (null if it could not be)
    private volatile String scanName, scanCsv;
    private volatile SpeedVolume.Strength volumeStrength = SpeedVolume.Strength.OFF;   // volume follows speed
    // Gear, pull/braking and the 0-60 timer come from the car's readings alone, so they are worked out here and
    // keep going (and reach the drive log) whichever screen is in front. The screen's sensors add to carMotion:
    // the first shove of a launch and GPS speed standing in (see TabletSensors).
    private final Motion carMotion = new Motion();
    private final Gears gears = Catalog.porsche() ? Gears.porsche() : Gears.jeep();
    private static final String GEARING_KEY = Catalog.porsche() ? "gearing_p" : "gearing";
    private static final long RPM_WITHIN_MS = 1500;        // engine and road speed this close in time make a pair
    private double savedGearing;                            // ticker thread: the learned gearing as last saved
    private long lastSpeedAt = Long.MIN_VALUE;              // ticker thread: the speed reading carMotion had last
    private final SpeedCorrection.Gps speedGps = new SpeedCorrection.Gps();
    private volatile double speedCorrection;                // percent, this car's
    private volatile boolean activeDirty;
    private volatile Status status = Status.SEARCHING;
    private volatile String detail = "";
    private volatile boolean running;
    private volatile int generation;        // each worker thread has its own; an old one stops when it changes
    private volatile boolean anyBound;
    private volatile boolean soundOnly;                     // dash closed, dongle let go, alive only for the DSP or the player
    private volatile boolean foreground;
    private volatile DongleSession session;
    private volatile Poller runner;
    private volatile int readsPerSec;
    private volatile CodesJob codesJob;
    private Thread worker;
    private ScheduledExecutorService ticker;

    // ticker-thread state
    private Poller countedRunner;
    private int countedReads;
    private long lastUsefulMs;
    private DriveLogWriter log;
    private long logStartMs, lastDataMs, logRetryAfterMs;
    private int rowsSinceFlush;
    private SpeedVolume volume;             // set while it is raising the volume; null otherwise
    private SpeedVolume.Strength volumeFor; // the strength that one was made for

    // ---------------- what the dash uses ----------------

    public void addListener(Listener l) {
        listeners.add(l);
        l.onStatus(status, detail);
    }

    public void removeListener(Listener l) { listeners.remove(l); }

    /**
     * The values on screen right now; only these are read from the Jeep, plus what the fuel counter needs
     * (see FuelUse.withInputs), so fuel is counted for the whole drive whichever screen is in front.
     */
    public void setActiveParams(Set<String> ids) {
        requested = new LinkedHashSet<>(ids);
        refreshActive();
    }

    /** The gauges on the dash: its fuel gauges keep the fuel counter going while another screen is in front. */
    public void setDashGauges(Set<String> ids) {
        dashShown = new LinkedHashSet<>(ids);
        refreshActive();
    }

    /** Works the read list out again (also after a fill-up was marked). */
    public void refreshActive() {
        FuelUse f = fuel;
        Set<String> ids = FuelUse.withInputs(requested, dashShown, Catalog.porsche(), f != null && f.fillStarted());
        ids = Motion.withCarInputs(ids, dashShown);              // gear, pull and 0-60 behind another screen
        ids = TimingPulled.withInputs(ids, dashShown, Catalog.porsche());     // timing pulled, likewise
        // the learning alerts: engine speed, then road speed and coolant near idle, the idle values at warm idle
        if (normal != null) ids = NormalReadings.withInputs(ids, Catalog.porsche(), learnNearIdle, learnStoppedWarm);
        ids = SpeedVolume.withSpeed(ids, volumeStrength);         // speed is read while volume follows it
        active = SoundEngine.withInputs(ids, this, Catalog.porsche());   // and what the Sound hub's DSP follows
        activeDirty = true;
    }

    // ---------------- gear, pull/braking, 0-60 ----------------

    /** For the screen's sensors to add the first shove of a launch and GPS speed (see TabletSensors). */
    public Motion carMotion() { return carMotion; }

    private TabletHealth tablet;

    /** The tablet's heat and battery watch (Jeep only; null on the Porsche). */
    TabletHealth tablet() { return tablet; }

    /** The dash is in front: it shows the tablet's alerts itself, so no notification. */
    void setDashInFront(boolean front) { if (tablet != null) tablet.setDashInFront(front); }

    /** Ticker thread: hands carMotion and the gear indicator each new speed reading once, then puts their figures. */
    private void workOutMotion() {
        if (!Motion.carWanted(requested, dashShown)) return;
        Long at = values.timeOf("speed");
        if (at != null && at != lastSpeedAt) {
            Double mph = values.get("speed", at);              // corrected (see SpeedCorrection)
            if (mph != null && at.equals(values.timeOf("speed"))) {
                lastSpeedAt = at;
                carMotion.dash(mph, at);
                Long rpmAt = values.timeOf("rpm");
                Double rpm = rpmAt == null ? null : values.get("rpm", rpmAt);
                if (rpm != null && Math.abs(at - rpmAt) <= RPM_WITHIN_MS) {
                    gears.sample(rpm, mph, at);
                    keepGearing();
                }
            }
        }
        long now = now();
        putIf("g_accel", carMotion.gAccel(now), now);
        putIf("zero_sixty", carMotion.zeroSixty(now), now);
        putIf("gear", gears.gear(now), now);
    }

    /** Saves the learned gearing when it has moved by more than half a percent since it was last saved. */
    private void keepGearing() {
        double top = gears.topRpmPerMph();
        if (savedGearing > 0 && Math.abs(top / savedGearing - 1) < 0.005) return;
        if (savedGearing == 0 && !gears.learned()) return;      // nothing learned yet: do not store the stock guess
        savedGearing = top;
        getSharedPreferences(DashApp.PREFS, MODE_PRIVATE).edit().putFloat(GEARING_KEY, (float) top).apply();
    }

    // ---------------- speed correction ----------------

    private static String correctionKey() { return Catalog.porsche() ? "speed_corr_p" : "speed_corr"; }

    /** This car's speed correction in percent (0 when none). */
    public double speedCorrection() { return speedCorrection; }

    /** GPS against the car's raw speed, collected while the screen's GPS runs, for "Set from GPS". */
    public SpeedCorrection.Gps speedGps() { return speedGps; }

    /**
     * Sets this car's correction, kept for the next start. Every speed reading from now on is corrected as it
     * comes in; the gear indicator's learned rpm per mph is rescaled to match, so it does not go wrong meanwhile.
     */
    public void setSpeedCorrection(double pct) {
        final double old = speedCorrection, now = SpeedCorrection.clamp(pct);
        speedCorrection = now;
        getSharedPreferences(DashApp.PREFS, MODE_PRIVATE).edit().putFloat(correctionKey(), (float) now).apply();
        values.setScale("speed", SpeedCorrection.factor(now));
        if (ticker != null) ticker.execute(() -> {
            if (!gears.learned()) return;
            gears.restore(SpeedCorrection.rescaleTop(gears.topRpmPerMph(), old, now));
            savedGearing = 0;
            keepGearing();
        });
    }

    /** A line from a screen for the link log (which motion sensor the tablet has, for one). */
    public void noteFromScreen(String line) { note(line); }

    // ---------------- volume follows speed (experimental) ----------------

    private static final String VOLUME_PREF = "volume_speed";

    public SpeedVolume.Strength volumeFollowsSpeed() { return volumeStrength; }

    /** Off, low, medium or high; kept for the next start. Turning it off puts the owner's level back. */
    public void setVolumeFollowsSpeed(SpeedVolume.Strength s) {
        getSharedPreferences("link", MODE_PRIVATE).edit().putString(VOLUME_PREF, s.name()).apply();
        volumeStrength = s;
        refreshActive();
    }

    /**
     * Ticker thread, twice a second: while the link is up and the setting is on, sets the media volume
     * SpeedVolume asks for; otherwise gives back the owner's own level once.
     */
    // ---------------- the Sound hub's tablet DSP ----------------

    private final SoundEngine sound = new SoundEngine();

    SoundEngine sound() { return sound; }

    /** A Sound hub setting changed: read what it now needs and re-apply at once. */
    void soundChanged() {
        refreshActive();
        LosslessPlayer p = player;
        if (p != null) p.refilter();
        sound.tick(this, values, now());
    }

    /** Road speed right now in mph, or null without a fresh reading from the dongle. */
    Double liveSpeed() { return values.get("speed", now()); }

    // ---------------- the lossless player ----------------

    private volatile LosslessPlayer player;

    /** The local lossless player; made the first time it is asked for, and it plays on behind every screen. */
    synchronized LosslessPlayer player() {
        if (player == null) player = new LosslessPlayer(this, sound, () -> sound.tick(this, values, now()));
        return player;
    }

    private void followSpeed() {
        SpeedVolume.Strength s = volumeStrength;
        if (s == SpeedVolume.Strength.OFF || status != Status.LIVE || runner == null) {
            releaseVolume();
            return;
        }
        AudioManager am = getSystemService(AudioManager.class);
        if (am == null) return;
        if (volume != null && volumeFor != s) releaseVolume();      // strength changed: start from the owner's level
        if (volume == null) {
            volume = new SpeedVolume(s);
            volumeFor = s;
        }
        long now = now();
        int current = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        int want = volume.step(current, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), values.get("speed", now), now);
        if (want != current) setMusicVolume(am, want);
    }

    /** Puts the owner's own level back (link dropped, setting changed or off, service stopping). */
    private void releaseVolume() {
        SpeedVolume v = volume;
        if (v == null) return;
        volume = null;
        AudioManager am = getSystemService(AudioManager.class);
        if (am == null) return;
        int current = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        Integer back = v.release(current);
        if (back != null && back != current) setMusicVolume(am, back);
    }

    private void setMusicVolume(AudioManager am, int step) {
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0);
        } catch (SecurityException e) {
            note("volume not changed: " + e.getMessage());      // Do Not Disturb, for one
        }
    }

    private boolean fuelWanted() {
        return fuel != null && (FuelUse.needsInputs(requested, fuel.fillStarted()) || FuelUse.needsInputs(dashShown, false));
    }

    /** Ticker thread: hands the fuel counter the latest readings, and puts its figures with the others. */
    private void countFuel() {
        if (!fuelWanted()) return;
        long now = now();
        FuelUse f = fuel;
        f.drive(driveStartedMs);
        if (Catalog.porsche())
            f.porsche(FuelUse.fresh(values, "maf", now), FuelUse.fresh(values, "lambda_r", now),
                    FuelUse.fresh(values, "lambda_l", now), FuelUse.fresh(values, "speed", now), now);
        else
            f.jeep(FuelUse.fresh(values, "inj_ms", now), FuelUse.fresh(values, "rpm", now),
                    FuelUse.fresh(values, "speed", now), now);
        putIf("mpg", f.mpgNow(now), now);
        putIf("mpg_avg", f.driveMpg(), now);
        putIf("fuel_used", f.driveGallons(), now);
        putIf("fuel_gph", f.gph(now), now);
        FuelStore.keep(now);
    }

    private void putIf(String id, Double v, long now) { if (v != null) values.put(id, v, now); }

    // ---------------- timing pulled (Jeep) ----------------

    /** Ticker thread: while the gauge is on screen or on the dash, works it out; blank at once when it means nothing. */
    private void workOutTiming() {
        if (Catalog.porsche() || !(TimingPulled.needsInputs(requested) || TimingPulled.needsInputs(dashShown))) return;
        long now = now();
        Double pulled = timing.update(values, now);
        if (pulled != null) values.put(TimingPulled.GAUGE, pulled, now);
        else values.remove(TimingPulled.GAUGE);
    }

    // ---------------- learning alerts ----------------

    /**
     * Ticker thread, once a second: hands the learning alerts this drive and the latest readings, and changes
     * the read list when it needs road speed and coolant (near idle) or the idle values (stopped and warm).
     */
    private void learnNormal(long t) {
        NormalReadings n = normal;
        if (n == null || scanOpen) return;          // a scan leaves the gauges blank: nothing to learn meanwhile
        long wall = System.currentTimeMillis();
        n.drive(driveStartedMs, wall);
        n.update(values, t, wall);
        boolean near = n.nearIdle(t), warm = n.stoppedWarm();
        if (near != learnNearIdle || warm != learnStoppedWarm) {
            learnNearIdle = near;
            learnStoppedWarm = warm;
            refreshActive();
        }
        NormalStore.keep(t);
    }

    // ---------------- scan all values (Jeep) ----------------

    /** True when the value scan can run: the Jeep, with the dongle linked. */
    public boolean scanAvailable() { return runner instanceof PollRunner; }

    /** Starts a value scan between two live-data reads; false when it cannot run now. */
    public boolean startScan() {
        if (!scanAvailable() || scanOpen) return false;
        scanUri = null;
        scanName = null;
        scanCsv = null;
        scan = new ValueScan();
        scanOpen = true;
        return true;
    }

    /** Stops the scan under way; what was read is still saved. */
    public void cancelScan() {
        ValueScan s = scan;
        if (s != null) s.cancel();
    }

    /** The scan under way or the last one (null before the first). */
    public ValueScan scan() { return scan; }

    /** True while a scan is under way (live data waits). */
    public boolean scanRunning() { return scanOpen; }

    /** Where the last scan was saved, its file name and its text (for sharing); null until it has ended. */
    public Uri scanUri() { return scanUri; }

    public String scanName() { return scanName; }

    public String scanCsv() { return scanCsv; }

    /**
     * Link thread: one read of the scan under way, in place of a live-data read; false when there is none.
     * Live data picks up again where it left off once the scan has ended.
     */
    private boolean scanStep(Poller r) throws IOException {
        ValueScan s = scan;
        if (!scanOpen || s == null) return false;
        int id = s.next();
        if (id < 0 || !(r instanceof PollRunner)) {
            endScan(s);
            return true;
        }
        if (s.done() == 0) note("-- value scan: every live-data id, " + ValueScan.PASSES + " passes");
        s.record(id, ((PollRunner) r).scanRead(id));
        setStatus(Status.LIVE, "Scanning all values: pass " + s.pass() + " of " + ValueScan.PASSES + ", "
                + (100 * s.done() / s.total()) + "%");
        return true;
    }

    /** The scan has ended (finished, cancelled, or the link went): saves the CSV and lets live data go on. */
    private void endScan(ValueScan s) {
        if (!scanOpen) return;
        s.cancel();                                 // does nothing to a finished scan
        String csv = s.csv(ValueScan.knownAs(Catalog.all()));
        String name = "value-scan-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date()) + ".csv";
        Uri uri = DriveLogStore.saveText(this, name, csv, "text/comma-separated-values");
        note("-- value scan ended: " + s.progress() + (uri == null ? "; could not save the file" : "; saved as " + name));
        scanCsv = csv;
        scanName = name;
        scanUri = uri;
        scanOpen = false;
        activeDirty = true;
    }

    public int readsPerSecond() { return readsPerSec; }

    public LiveValues values() { return values; }

    public Status status() { return status; }

    public String statusDetail() { return detail; }

    /** True when the experimental fast refresh exists for this vehicle (the Jeep line for now). */
    public boolean fastRefreshAvailable() { return !Catalog.porsche(); }

    /** OFF, ON, or BACKED_OFF when it was dropped again because readings went missing. */
    public PollRunner.Fast fastRefresh() {
        Poller r = runner;
        if (r instanceof PollRunner) return ((PollRunner) r).fastRefresh();
        if (Catalog.porsche()) return PollRunner.Fast.OFF;       // the Jeep line only
        return getSharedPreferences("link", MODE_PRIVATE).getBoolean("fast", false) ? PollRunner.Fast.ON : PollRunner.Fast.OFF;
    }

    /** Turns the fast refresh on or off, now and for the next time the app starts. */
    public void setFastRefresh(boolean on) {
        getSharedPreferences("link", MODE_PRIVATE).edit().putBoolean("fast", on).apply();
        Poller r = runner;
        if (r instanceof PollRunner) ((PollRunner) r).setFastRefresh(on);
    }

    /** True when stored codes can be read on this vehicle (both cars now). */
    public boolean codesAvailable() { return true; }

    /** Reads the stored codes between two live-data reads. The answer comes on the main thread. */
    public void readCodes(CodesListener l) { askCodes(new CodesJob(false, l)); }

    /** Clears the stored codes (the one thing the dash ever changes in the engine computer), then reads them back. */
    public void clearCodes(CodesListener l) { askCodes(new CodesJob(true, l)); }

    private void askCodes(CodesJob job) {
        if (!(runner instanceof PollRunner)) {      // no dongle link right now, or not the Jeep
            answerCodes(job, null, job.clear ? SciCodes.Outcome.NOT_ANSWERING : null);
            return;
        }
        codesJob = job;
    }

    private void answerCodes(CodesJob job, List<Integer> codes, SciCodes.Outcome cleared) {
        main.post(() -> job.listener.onCodes(codes, cleared));
    }

    /** Runs a waiting codes request on the link thread, so nothing else talks to the dongle meanwhile. */
    private void doCodes(Poller r) throws IOException {
        CodesJob job = codesJob;
        if (job == null) return;
        codesJob = null;
        try {
            if (job.clear) {
                SciCodes.Cleared c = r.clearCodes();
                answerCodes(job, c.left, c.outcome);
            } else {
                List<Integer> codes = r.readCodes();
                String m = MisfireCodes.first(codes, Catalog.porsche());
                if (m != null && misfire != null) misfire.trigger(now(), m);     // misfire code in the list: keep 30 s either side
                answerCodes(job, codes, null);
            }
        } catch (IOException e) {
            answerCodes(job, null, job.clear ? SciCodes.Outcome.FAILED : null);
            throw e;
        }
    }

    /** Everything said to the dongle lately and what came back, for sharing when something misbehaves. */
    public String linkLog() {
        return "Jeep Dash link log\nAndroid " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + "), "
                + Build.MANUFACTURER + " " + Build.MODEL + "\nStatus now: " + status + " " + detail
                + "\nReading: " + active + "\nFast refresh: " + fastRefresh() + ", " + readsPerSec + " readings/s"
                + "\nUSB: " + usbDevices() + "\n\n" + trace.snapshot();
    }

    /** What is plugged into the USB ports, as maker:product numbers (tells one cable's chip from another's). */
    private String usbDevices() {
        try {
            UsbManager usb = getSystemService(UsbManager.class);
            if (usb == null) return "not available";
            StringBuilder sb = new StringBuilder();
            for (UsbDevice d : usb.getDeviceList().values())
                sb.append(String.format(java.util.Locale.US, "%04x:%04x %s; ", d.getVendorId(), d.getProductId(), d.getProductName()));
            return sb.length() == 0 ? "nothing plugged in" : sb.toString();
        } catch (RuntimeException e) {
            return "could not list: " + e;
        }
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
        instance = this;
        // the tablet's heat and battery: watched behind every screen, layout or not
        if (com.strickland.jeepdash.core.TabletWatch.wanted(Catalog.porsche())) {
            tablet = new TabletHealth(this, values);
            tablet.start();
        }
        List<String> misfireIds = new ArrayList<>();
        for (Param p : Catalog.all()) misfireIds.add(p.id);
        misfire = new RollingCapture(misfireIds);
        try {
            IntentFilter pairing = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
            pairing.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            pairing.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY - 1);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(pairingWatcher, pairing, Context.RECEIVER_EXPORTED);
            else registerReceiver(pairingWatcher, pairing);
        } catch (RuntimeException e) {
            note("cannot watch for pairing requests: " + e);
        }
        fuel = FuelStore.get(this);
        normal = NormalStore.get(this);
        volumeStrength = SpeedVolume.Strength.of(getSharedPreferences("link", MODE_PRIVATE).getString(VOLUME_PREF, null));
        SharedPreferences dash = getSharedPreferences(DashApp.PREFS, MODE_PRIVATE);
        savedGearing = dash.getFloat(GEARING_KEY, 0);
        gears.restore(savedGearing);                // what earlier drives taught it about this car
        speedCorrection = SpeedCorrection.clamp(dash.getFloat(correctionKey(), 0));
        values.setScale("speed", SpeedCorrection.factor(speedCorrection));
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
        LosslessPlayer lp = player;
        if (lp != null) lp.release();
        sound.release();
        if (tablet != null) tablet.stop();
        stopWorker();
        try {
            unregisterReceiver(pairingWatcher);
        } catch (RuntimeException ignored) {
            // it was never registered
        }
        if (instance == this) instance = null;
        if (ticker != null) {
            ticker.execute(() -> saveMisfire(misfire.finish()));
            ticker.execute(this::closeLog);
            ticker.execute(this::releaseVolume);
            ticker.execute(FuelStore::save);
            ticker.execute(NormalStore::save);
            ticker.shutdown();
        }
        super.onDestroy();
    }

    /** True while the sound side should outlive the dash: the Tablet DSP is switched on, or the player is playing. */
    private boolean soundWanted() {
        if (Catalog.porsche()) return false;
        LosslessPlayer p = player;
        return SoundPrefs.of(this).getBoolean(SoundPrefs.ENABLED, false) || (p != null && p.playing());
    }

    /**
     * The dash is closed and the Jeep is quiet, but the sound is still wanted: let go of the dongle and stay
     * alive for the DSP, so it carries on over whatever else the tablet plays. Opening the dash starts the
     * dongle link again; Stop on the notification ends this.
     */
    private void keepOnlyTheSound() {
        if (!running || soundOnly) return;
        stopWorker();
        soundOnly = true;
        setStatus(Status.SEARCHING, "Dash closed. The sound is still on.");
        postNotification();
    }

    private void postNotification() {
        if (!foreground) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        try { nm.notify(NOTIFICATION_ID, buildNotification()); } catch (RuntimeException ignored) { }
    }

    private void shutDown() {
        soundOnly = false;
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
                .setContentText(soundOnly ? "Sound is on. Stop turns it off." : statusLine(status))
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
            case PAUSED: return PAUSED_DETAIL;
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
        if (soundOnly) {
            soundOnly = false;
            postNotification();
        }
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
        final DongleSession s = session;
        final Thread w = worker;
        Poller r = runner;
        if (s != null && r != null && r.keepAliveRunning()) {
            // Give the worker a moment to finish its read and turn the dongle's keep-alive off (leave()).
            main.postDelayed(() -> {
                s.close();
                if (w != null) w.interrupt();
            }, 1500);
        } else {
            if (s != null) s.close();
            if (w != null) w.interrupt();
        }
        worker = null;
    }

    private boolean alive(int gen) { return running && gen == generation; }

    private void workLoop(int gen) {
        boolean everLive = false;
        int failures = 0;
        SharedPreferences prefs = getSharedPreferences("link", MODE_PRIVATE);
        while (alive(gen)) {
            try {
                if (GATE.isPaused()) {          // Jeep Tune has the dongle: no connecting, no retries
                    setStatus(Status.PAUSED, PAUSED_DETAIL);
                    pause(500);
                    continue;
                }
                if (!hasBluetoothPermission()) {
                    setStatus(Status.NO_PERMISSION, "Allow Nearby devices for Jeep Dash");
                    pause(RETRY_MS);
                    continue;
                }
                String[] why = {""};
                BluetoothDevice dev = findDongle(prefs.getString("address", null), why);
                // Not paired: some car radios can only pair phones, so look for the dongle ourselves.
                if (dev == null && UNPAIRED.equals(why[0])) dev = lookForDongle(why, gen);
                if (dev == null) {
                    setStatus(Status.SEARCHING, why[0]);
                    pause(RETRY_MS);
                    continue;
                }
                if (failures == 0) setStatus(everLive ? Status.RECONNECTING : Status.CONNECTING, "");
                if (!paired(dev)) pair(dev, gen);
                AndroidByteLink link = connect(dev, prefs.getInt("mode", -1), failures, gen);
                if (link != null && GATE.isPaused()) {      // Tune started while this was connecting
                    link.close();
                    continue;
                }
                if (link == null && GATE.isPaused()) continue;
                if (link == null) {
                    failures++;
                    // An address remembered from an unpaired dongle may be a dongle that is gone: look again.
                    if (failures >= 3 && !paired(dev)) prefs.edit().remove("address").apply();
                    setStatus(everLive ? Status.RECONNECTING : Status.BUSY, BUSY_DETAIL);
                    pause(RETRY_MS);
                    continue;
                }
                failures = 0;
                prefs.edit().putString("address", dev.getAddress()).putInt("mode", link.mode).apply();
                if (runConnection(link, gen)) everLive = true;
                if (alive(gen) && !GATE.isPaused()) {
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
        if (remembered != null) return remembered;
        // found by looking last time and never paired: go straight back to it
        if (rememberedAddress != null && BluetoothAdapter.checkBluetoothAddress(rememberedAddress))
            return adapter.getRemoteDevice(rememberedAddress);
        why[0] = UNPAIRED;
        return null;
    }

    private static final String UNPAIRED = "Pair the GWBT dongle in Bluetooth settings";

    @SuppressLint("MissingPermission")
    private static boolean paired(BluetoothDevice d) {
        try {
            return d.getBondState() == BluetoothDevice.BOND_BONDED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean mayLook() {
        if (Build.VERSION.SDK_INT >= 31)
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        // before Android 12, seeing nearby Bluetooth devices needs the location permission
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED);
    }

    /**
     * Searches nearby Bluetooth devices for the dongle by its name, for a few seconds. It is connected to
     * directly afterwards; the serial link does not need the device to be on the paired list first.
     */
    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private BluetoothDevice lookForDongle(String[] why, int gen) {
        if (!mayLook()) {
            why[0] = NEED_SCAN_DETAIL;
            return null;
        }
        BluetoothManager bm = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null) return null;
        setStatus(Status.SEARCHING, "Looking for the GWBT dongle nearby");
        final BluetoothDevice[] found = {null};
        final CountDownLatch seen = new CountDownLatch(1);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                String name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME);
                try {
                    if (name == null && d != null) name = d.getName();
                } catch (RuntimeException ignored) {
                    // no name to go by
                }
                if (d != null && name != null && name.startsWith("GWBT-")) {
                    found[0] = d;
                    seen.countDown();
                }
            }
        };
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        else registerReceiver(receiver, filter);
        try {
            adapter.startDiscovery();
            for (int i = 0; i < SCAN_SECONDS * 2 && alive(gen); i++)
                if (seen.await(500, TimeUnit.MILLISECONDS)) break;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                adapter.cancelDiscovery();      // a search still running would slow the connection down
            } catch (RuntimeException ignored) {
                // nothing to cancel
            }
            unregisterReceiver(receiver);
        }
        if (found[0] == null) why[0] = "No GWBT dongle found nearby. Is it plugged in with the key on?";
        else note("found the dongle by looking (not paired)");
        return found[0];
    }

    /** PINs dongles like this one usually ship with; one is tried per pairing attempt. */
    private static final String[] PINS = {"1234", "0000", "1111", "8888"};
    private int pinTry;
    private volatile String pairingAddress;         // the dongle being paired right now, if any
    private volatile boolean askedForPin, askedForApproval;

    /**
     * Watches every pairing request for the dongle while the service runs, whoever started it. A PIN is
     * answered here. A plain "approve this pairing" can only be approved by the system, so the request is
     * kept away from other apps (one on some car radios cancels it at once) and the system's own pairing
     * box is put on screen for the driver to tap. Every step goes in the link log.
     */
    private final BroadcastReceiver pairingWatcher = new BroadcastReceiver() {
        @Override
        @SuppressLint("MissingPermission")
        @SuppressWarnings("deprecation")
        public void onReceive(Context context, Intent intent) {
            BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (d == null) return;
            String name = null;
            try {
                name = d.getName();
            } catch (RuntimeException ignored) {
                // go by the address alone
            }
            if (!d.getAddress().equals(pairingAddress) && (name == null || !name.startsWith("GWBT-"))) return;
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                note("pairing state " + intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                        + " (10 none, 11 pairing, 12 paired)");
                return;
            }
            int kind = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);
            note("pairing request, kind " + kind);
            try {
                if (kind == BluetoothDevice.PAIRING_VARIANT_PIN) {
                    askedForPin = true;
                    String pin = PINS[pinTry % PINS.length];
                    d.setPin(pin.getBytes());
                    note("answered with PIN " + pin);
                    if (isOrderedBroadcast()) abortBroadcast();
                    return;
                }
                askedForApproval = true;
                if (isOrderedBroadcast()) {
                    abortBroadcast();
                    note("kept the request from other apps");
                }
                note(openSystemPairingBox(d, kind, intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, BluetoothDevice.ERROR)));
            } catch (RuntimeException e) {
                note("could not handle the pairing request: " + e);
            }
        }
    };

    /** Asks the system's own settings app for its "Pair with this device?" box. Says how it went. */
    private String openSystemPairingBox(BluetoothDevice d, int kind, int key) {
        String[] classes = {"com.android.settings.bluetooth.BluetoothPairingDialog", null};
        String last = "";
        for (String cls : classes) {
            Intent box = new Intent(BluetoothDevice.ACTION_PAIRING_REQUEST);
            if (cls != null) box.setClassName("com.android.settings", cls);
            else box.setPackage("com.android.settings");
            box.putExtra(BluetoothDevice.EXTRA_DEVICE, d);
            box.putExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, kind);
            if (key != BluetoothDevice.ERROR) box.putExtra(BluetoothDevice.EXTRA_PAIRING_KEY, key);
            box.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(box);
                setStatus(Status.CONNECTING, "Tap PAIR on the box that just opened");
                return "opened the system pairing box" + (cls == null ? " (by package)" : "");
            } catch (RuntimeException e) {
                last = e.toString();
            }
        }
        return "could not open the system pairing box: " + last;
    }

    /**
     * Pairs with a dongle found by looking. Some car radios have no screen for pairing anything but a
     * phone, so the pairing is started here and the watcher above deals with what the dongle asks for.
     */
    @SuppressLint("MissingPermission")
    private void pair(BluetoothDevice dev, int gen) {
        pairingAddress = dev.getAddress();
        askedForPin = false;
        askedForApproval = false;
        try {
            Thread.sleep(400);                  // let the search wind down before talking to the dongle
            note("pairing started: " + dev.createBond());
            // an approval box needs time for a finger to reach it
            for (int i = 0; i < (askedForApproval ? 70 : 24) && alive(gen); i++) {
                int state = dev.getBondState();
                if (state == BluetoothDevice.BOND_BONDED || (i > 3 && state == BluetoothDevice.BOND_NONE)) break;
                Thread.sleep(500);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            note("pairing could not start: " + e);
        }
        boolean done = paired(dev);
        if (!done && askedForPin) pinTry++;         // next attempt tries the next PIN
        note(done ? "paired with the dongle" : "not paired");
    }

    /** Tries the way that worked last time first; every fourth failed round tries all three ways. */
    private AndroidByteLink connect(BluetoothDevice dev, int lastGoodMode, int failures, int gen) {
        boolean tryAll = lastGoodMode < 0 || lastGoodMode >= AndroidByteLink.MODES || failures % 4 == 3;
        for (int i = 0; i < AndroidByteLink.MODES && alive(gen) && !GATE.isPaused(); i++) {
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

        // the Porsche speaks a different language on a different line; which car this is was chosen in SETUP
        Poller r;
        if (Catalog.porsche()) {
            r = new KwpRunner(s, values, LinkService::now);
        } else {
            PollRunner jeep = new PollRunner(s, new PollScheduler(OTHER_LINE_EVERY_MS), values, LinkService::now);
            jeep.setFastRefresh(getSharedPreferences("link", MODE_PRIVATE).getBoolean("fast", false));
            r = jeep;
        }
        r.setTrace(trace);
        activeDirty = false;
        r.setActive(active);
        runner = r;
        setStatus(Status.SETTING_UP, "");
        boolean live = false;
        try {
            while (alive(gen)) {
                if (GATE.isPaused()) break;         // Jeep Tune wants the dongle: leave() below, then the socket closes
                if (activeDirty) {
                    activeDirty = false;
                    r.setActive(active);
                }
                doCodes(r);
                if (scanStep(r)) {
                    live = true;
                    continue;
                }
                boolean busy = r.poll();
                live = true;
                setStatus(Status.LIVE, r.vehicleAnswering() ? "" : KEY_OFF_DETAIL);
                if (!busy) pause(200);
            }
            r.leave();
        } catch (IOException e) {
            note("link lost: " + e.getMessage());
        } finally {
            if (runner == r) runner = null;
            ValueScan s2 = scan;
            if (scanOpen && s2 != null) endScan(s2);     // the link went mid-scan: keep what was read
            CodesJob waiting = codesJob;            // asked just as the link went away
            if (waiting != null) {
                codesJob = null;
                answerCodes(waiting, null, waiting.clear ? SciCodes.Outcome.NOT_ANSWERING : null);
            }
            if (session == s) session = null;
            s.close();
            values.clear();       // nothing on screen may outlive the link it came over
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
        workOutMotion();
        countFuel();
        workOutTiming();
        sampleMisfire();
        if (++tickCount % 5 == 0) followSpeed();
        if (tickCount % 5 == 0 && !Catalog.porsche()) sound.tick(this, values, now());
        if (tickCount % 10 != 0) return;

        // once a second
        long t = now();
        Poller r = runner;
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
        if (dataLately && !driving) {
            driving = true;
            driveStartedMs = t;
        } else if (driving && !dataLately && t - lastDataMs > LOG_CLOSE_MS) {
            driving = false;
        }
        learnNormal(t);
        if (dataLately && log == null && t >= logRetryAfterMs) openLog(t);
        if (log != null) {
            if (dataLately) writeRow(t);
            else if (t - lastDataMs > LOG_CLOSE_MS) closeLog();
        }

        if (anyBound || dataLately) lastUsefulMs = t;
        switch (KeepAlive.decide(anyBound || dataLately, t - lastUsefulMs, IDLE_STOP_MS, running, soundOnly, soundWanted())) {
            case STOP: main.post(this::shutDown); break;
            case SOUND_ONLY: main.post(this::keepOnlyTheSound); break;
            default: break;
        }
    }

    /** Ticker thread: feeds the misfire buffer while the link is up; a capture cut short by a lost link is saved as it is. */
    private void sampleMisfire() {
        RollingCapture m = misfire;
        if (m == null) return;
        if (status == Status.LIVE && runner != null) saveMisfire(m.sample(now(), values));
        else if (m.pending()) saveMisfire(m.finish());
    }

    private static final int KEEP_MISFIRE_FILES = 30;

    /** Writes a finished capture to misfire-yyyyMMdd-HHmmss-P0303.csv in the app's own storage folder. */
    private void saveMisfire(RollingCapture.Capture c) {
        if (c == null) return;
        try {
            java.io.File dir = getExternalFilesDir("misfire");
            if (dir == null) dir = new java.io.File(getFilesDir(), "misfire");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no folder " + dir);
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date());
            java.io.File f = new java.io.File(dir, "misfire-" + stamp + "-" + c.reason + ".csv");
            try (java.io.Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(f), java.nio.charset.StandardCharsets.UTF_8)) {
                c.writeCsv(w);
            }
            note("misfire capture saved: " + f.getName() + " (" + c.rows.size() + " rows)");
            java.io.File[] all = dir.listFiles((d, n) -> n.startsWith("misfire-") && n.endsWith(".csv"));
            if (all != null && all.length > KEEP_MISFIRE_FILES) {
                java.util.Arrays.sort(all);                 // names start with the time, so oldest first
                for (int i = 0; i < all.length - KEEP_MISFIRE_FILES; i++) all[i].delete();
            }
        } catch (IOException | RuntimeException e) {
            note("misfire capture not saved: " + e);
        }
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
