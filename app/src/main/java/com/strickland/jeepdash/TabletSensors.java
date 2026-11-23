package com.strickland.jeepdash;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
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

import com.strickland.jeepdash.core.Catalog;
import com.strickland.jeepdash.core.FuelUse;
import com.strickland.jeepdash.core.Gears;
import com.strickland.jeepdash.core.LiveValues;
import com.strickland.jeepdash.core.Motion;

/**
 * The tablet's own accelerometer and GPS, turned into gauge values by Motion and put next to the Jeep's
 * readings. Runs only while the dash asks for it. A tablet without the sensor or without GPS just leaves
 * those gauges blank. Everything here happens on the main thread.
 */
final class TabletSensors implements SensorEventListener, LocationListener {
    private static final long PUBLISH_MS = 50;             // new numbers for the gauges 20 times a second
    private static final long RPM_WITHIN_MS = 1500;        // engine and road speed this close in time make a pair
    private static final double MPH_PER_MPS = 2.23694;

    private final Motion motion = new Motion();
    private final Gears gears = Catalog.porsche() ? Gears.porsche() : Gears.jeep();
    private final boolean porsche = Catalog.porsche();
    private FuelUse fuel;                                  // shared with the fill-up dialog (see FuelStore)
    private static final String GEARING_KEY = Catalog.porsche() ? "gearing_p" : "gearing";
    private SharedPreferences prefs;
    private double savedGearing;                           // the learned gearing as last written to storage
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

    /** Safe to call again while running: only the GPS is turned on or off to match. */
    void start(Context c, boolean wantGps) {
        Context app = c.getApplicationContext();
        if (!running) {
            running = true;
            if (prefs == null) {
                prefs = app.getSharedPreferences("dash", Context.MODE_PRIVATE);
                savedGearing = prefs.getFloat(GEARING_KEY, 0);
                gears.restore(savedGearing);                // what earlier drives taught it about this car
            }
            fuel = FuelStore.get(app);
            sensors = app.getSystemService(SensorManager.class);
            Sensor s = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
            if (s != null) sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME, main);
            main.postDelayed(tick, PUBLISH_MS);
        }
        if (wantGps && !gpsOn) startGps(app);
        else if (!wantGps && gpsOn) stopGps();
    }

    void stop() {
        if (!running) return;
        running = false;
        main.removeCallbacks(tick);
        FuelStore.save();
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

    /** Hands each new speed reading from the Jeep to Motion once, with the time it was read. */
    private void feedDash() {
        LiveValues v = values;
        if (v == null) return;
        Long at = v.timeOf("speed");
        if (at == null || at == lastDashMs) return;
        Double mph = v.get("speed", at);
        if (mph == null || !at.equals(v.timeOf("speed"))) return;      // replaced while we looked: next time
        lastDashMs = at;
        motion.dash(mph, at);
        // the gear comes from engine speed against road speed, read within a moment of each other
        Long rpmAt = v.timeOf("rpm");
        Double rpm = rpmAt == null ? null : v.get("rpm", rpmAt);
        if (rpm != null && Math.abs(at - rpmAt) <= RPM_WITHIN_MS) {
            gears.sample(rpm, mph, at);
            keepGearing();
        }
    }

    /** Saves the learned gearing when it has moved by more than half a percent since it was last saved. */
    private void keepGearing() {
        double now = gears.topRpmPerMph();
        if (prefs == null || savedGearing > 0 && Math.abs(now / savedGearing - 1) < 0.005) return;
        if (savedGearing == 0 && !gears.learned()) return;      // nothing learned yet: do not store the stock guess
        savedGearing = now;
        prefs.edit().putFloat(GEARING_KEY, (float) now).apply();
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
        put(v, "g_accel", motion.gAccel(now), now);
        put(v, "zero_sixty", motion.zeroSixty(now), now);
        put(v, "speedo_err", motion.speedoError(now), now);
        put(v, "gear", gears.gear(now), now);
        feedFuel(v, now);
        put(v, "mpg", fuel.mpgNow(now), now);
        put(v, "mpg_avg", fuel.driveMpg(), now);
        put(v, "fuel_used", fuel.driveGallons(), now);
        put(v, "fuel_gph", fuel.gph(now), now);
        onPublished.run();
    }

    /** Hands FuelUse the latest fuel readings; anything not read in the last 2 s counts as missing. */
    private void feedFuel(LiveValues v, long now) {
        fuel.drive(LinkService.driveStartedMs());
        if (porsche)
            fuel.porsche(FuelUse.fresh(v, "maf", now), FuelUse.fresh(v, "lambda_r", now),
                    FuelUse.fresh(v, "lambda_l", now), FuelUse.fresh(v, "speed", now), now);
        else
            fuel.jeep(FuelUse.fresh(v, "inj_ms", now), FuelUse.fresh(v, "rpm", now), FuelUse.fresh(v, "speed", now), now);
        FuelStore.keep();
    }

    private static void put(LiveValues v, String id, Double value, long now) {
        if (value != null) v.put(id, value, now);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (!running || e.values.length < 3) return;
        feedDash();        // first, so a speed reading and the shove that follows it stay in order
        motion.accel(e.values[0], e.values[1], e.values[2], LinkService.now());
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override
    public void onLocationChanged(Location l) {
        if (!running || !l.hasSpeed()) return;
        feedDash();
        motion.gps(l.getSpeed() * MPH_PER_MPS, LinkService.now());
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
