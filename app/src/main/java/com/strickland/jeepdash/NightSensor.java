package com.strickland.jeepdash;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.strickland.jeepdash.core.AutoNight;

/**
 * Feeds AutoNight for the dash's Auto brightness: the ambient light sensor when the tablet has one, otherwise
 * the last known GPS position (for sunset and sunrise; this never asks for location, it only uses a position
 * the tablet already has and is allowed to read). With neither, dark() stays null and nothing changes.
 * Main thread only.
 */
final class NightSensor implements SensorEventListener {
    private static final long TICK_MS = 2000, PLACE_EVERY_MS = 60_000;

    private final AutoNight auto = new AutoNight();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable onChange;
    private final Runnable tick = this::check;
    private Context app;
    private SensorManager sensors;
    private boolean running, hasSensor;
    private Boolean last;
    private long placeAt = Long.MIN_VALUE / 2;

    /** onChange runs when the answer changes (light to dark or back). */
    NightSensor(Runnable onChange) { this.onChange = onChange; }

    /** True for dark, false for light, null when nothing is known yet. */
    Boolean dark() { return last; }

    /** Whether this tablet has a light sensor (false before start). */
    boolean hasLightSensor() { return hasSensor; }

    void start(Context c) {
        if (running) return;
        running = true;
        app = c.getApplicationContext();
        sensors = app.getSystemService(SensorManager.class);
        Sensor s = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_LIGHT);
        hasSensor = s != null;
        if (s != null) sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, main);
        main.post(tick);
    }

    void stop() {
        if (!running) return;
        running = false;
        main.removeCallbacks(tick);
        if (sensors != null) sensors.unregisterListener(this);
    }

    private void check() {
        if (!running) return;
        long now = SystemClock.elapsedRealtime();
        if (!auto.hasLux() && now - placeAt >= PLACE_EVERY_MS) {
            placeAt = now;
            readPlace();
        }
        Boolean d = auto.dark(now, System.currentTimeMillis());
        if (d != null && !d.equals(last)) {
            last = d;
            onChange.run();
        }
        main.postDelayed(tick, TICK_MS);
    }

    private void readPlace() {
        try {
            if (app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    && app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
            LocationManager lm = app.getSystemService(LocationManager.class);
            if (lm == null) return;
            Location best = null;
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (!lm.getAllProviders().contains(p)) continue;
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best != null) auto.place(best.getLatitude(), best.getLongitude());
        } catch (RuntimeException ignored) {
            // no position available: Auto changes nothing
        }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (!running || e.values.length < 1) return;
        auto.lux(e.values[0], SystemClock.elapsedRealtime());
        // the 10 s wait is counted by check(), which runs every couple of seconds
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }
}
