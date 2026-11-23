package com.strickland.jeepdash;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.strickland.jeepdash.core.Gravity;
import com.strickland.jeepdash.core.LiveValues;
import com.strickland.jeepdash.core.Motion;

/**
 * The tablet's own accelerometer and GPS, turned into gauge values by Motion and put next to the Jeep's
 * readings: GPS speed, G-force and Speedo vs GPS. Runs only while the dash asks for it. Gear, pull/braking and
 * the 0-60 timer are worked out by the link service from the car's readings (they keep going behind another
 * screen); while this runs it hands the service's Motion the first shove of a launch and GPS speed, as before.
 * A tablet without the sensor or without GPS just leaves those gauges blank. Everything here happens on the
 * main thread.
 */
final class TabletSensors implements SensorEventListener, LocationListener {
    private static final long PUBLISH_MS = 50;             // new numbers for the gauges 20 times a second
    private static final long PAIR_WITHIN_MS = 1500;       // a GPS fix and a car speed reading this close make a pair
    private static final double MPH_PER_MPS = 2.23694;

    private final Motion motion = new Motion();            // fed the car's raw speed, so Speedo vs GPS shows its error
    private final Gravity gravity = new Gravity();         // for a plain accelerometer (no gyroscope)
    private boolean plainAccelerometer;
    private LinkService link;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable onPublished;
    private final Runnable tick = this::publish;
    private LiveValues values;
    private SensorManager sensors;
    private LocationManager locations;
    private boolean running, gpsOn;
    private long lastDashMs = Long.MIN_VALUE;              // the Jeep speed reading Motion was given last

    /** onPublished runs after each batch of new values, so the dash can redraw. */
    TabletSensors(Runnable onPublished) { this.onPublished = onPublished; }

    /** Where results go, and where the Jeep's own speed is read from. */
    void setValues(LiveValues v) { values = v; }

    /** The link service: gets the shove and GPS for its gear, pull and 0-60, GPS samples, and a log line. */
    void setLink(LinkService s) { link = s; }

    /** Safe to call again while running: only the GPS is turned on or off to match. */
    void start(Context c, boolean wantGps) {
        Context app = c.getApplicationContext();
        if (!running) {
            running = true;
            sensors = app.getSystemService(SensorManager.class);
            Sensor s = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
            plainAccelerometer = false;
            if (s == null && sensors != null) {
                // Many tablets without a gyroscope offer no linear acceleration: take gravity out here instead.
                s = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                plainAccelerometer = s != null;
            }
            if (s != null) sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME, main);
            if (link != null) link.noteFromScreen("tablet motion sensor: " + (s == null ? "none found (G-force stays blank)"
                    : plainAccelerometer ? "accelerometer, gravity taken out in software" : "linear acceleration")
                    + (s == null ? "" : " (" + s.getName() + ")"));
            main.postDelayed(tick, PUBLISH_MS);
        }
        if (wantGps && !gpsOn) startGps(app);
        else if (!wantGps && gpsOn) stopGps();
    }

    void stop() {
        if (!running) return;
        running = false;
        main.removeCallbacks(tick);
        if (sensors != null) sensors.unregisterListener(this);
        stopGps();
    }

    private void startGps(Context app) {
        try {
            if (app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
            locations = app.getSystemService(LocationManager.class);
            if (locations == null || !locations.getAllProviders().contains(LocationManager.GPS_PROVIDER)) return;
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 0, this, Looper.getMainLooper());
            gpsOn = true;
        } catch (RuntimeException e) {
            // permission taken away, or no usable GPS after all: the GPS gauges stay blank
        }
    }

    private void stopGps() {
        gpsOn = false;
        if (locations == null) return;
        try {
            locations.removeUpdates(this);
        } catch (RuntimeException ignored) {
            // nothing was registered
        }
    }

    /**
     * Hands each new speed reading from the car to Motion once, with the time it was read: the raw reading, before
     * the speed correction, so Speedo vs GPS keeps showing the speedometer's own error.
     */
    private void feedDash() {
        LiveValues v = values;
        if (v == null) return;
        Long at = v.timeOf("speed");
        if (at == null || at == lastDashMs) return;
        Double mph = v.raw("speed", at);
        if (mph == null || !at.equals(v.timeOf("speed"))) return;      // replaced while we looked: next time
        lastDashMs = at;
        motion.dash(mph, at);
    }

    private void publish() {
        if (!running) return;
        main.postDelayed(tick, PUBLISH_MS);
        feedDash();
        LiveValues v = values;
        if (v == null) return;
        long now = LinkService.now();
        put(v, "gps_mph", motion.gpsMph(now), now);
        put(v, "g_force", motion.gForce(now), now);
        put(v, "speedo_err", motion.speedoError(now), now);
        onPublished.run();
    }

    private static void put(LiveValues v, String id, Double value, long now) {
        if (value != null) v.put(id, value, now);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (!running || e.values.length < 3) return;
        feedDash();        // first, so a speed reading and the shove that follows it stay in order
        long now = LinkService.now();
        double x = e.values[0], y = e.values[1], z = e.values[2];
        if (plainAccelerometer) {
            double[] a = gravity.linear(x, y, z, now);
            x = a[0];
            y = a[1];
            z = a[2];
        }
        motion.accel(x, y, z, now);
        LinkService s = link;
        if (s != null) s.carMotion().accel(x, y, z, now);          // the first shove times the 0-60 start
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override
    public void onLocationChanged(Location l) {
        if (!running || !l.hasSpeed()) return;
        feedDash();
        long now = LinkService.now();
        double gps = l.getSpeed() * MPH_PER_MPS;
        motion.gps(gps, now);
        LinkService s = link;
        LiveValues v = values;
        if (s == null) return;
        s.carMotion().gps(gps, now);                           // stands in for road speed when the car's is gone
        Long at = v == null ? null : v.timeOf("speed");
        Double raw = at == null ? null : v.raw("speed", now);
        if (raw != null && now - at <= PAIR_WITHIN_MS) s.speedGps().sample(gps, raw, now);    // for "Set from GPS"
    }

    // Before Android 11 these three have no default and are called, so they must exist.
    @Override
    public void onProviderEnabled(String provider) { }

    @Override
    public void onProviderDisabled(String provider) { }

    @Override
    @SuppressWarnings("deprecation")
    public void onStatusChanged(String provider, int status, Bundle extras) { }
}
