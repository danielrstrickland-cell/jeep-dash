package com.strickland.jeepdash.scene;

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
import android.view.Display;
import android.view.WindowManager;

import com.strickland.jeepdash.core.HoloEffects;
import com.strickland.jeepdash.core.LiveValues;
import com.strickland.jeepdash.core.MountDetector;
import com.strickland.jeepdash.core.Parallax;
import com.strickland.jeepdash.core.TruckMotion;

/**
 * Feeds the truck's motion model from whatever there is: the accelerometer (mount detection and G when mounted),
 * GPS (turn rate and speed when not), the car's road speed and pull; and the parallax from the tablet's rotation
 * sensor (or its tilt when there is none). Puts the realistic Body roll and Body pitch readings beside the car's.
 * Runs only while the dash shows the truck or those readings. Main thread.
 */
public final class MotionFeed implements SensorEventListener, LocationListener {
    private static final long STEP_MS = 33, MIN_STEP_MS = 12, PUBLISH_MS = 100;

    public final TruckMotion motion = new TruckMotion(0);
    public final Parallax parallax = new Parallax();
    private final HoloEffects.Calm calm = new HoloEffects.Calm();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::tick;
    private final float[] rot = new float[9], remapped = new float[9], angles = new float[3];
    private SensorManager sensors;
    private LocationManager locations;
    private Display display;
    private LiveValues values;
    private boolean running, gpsOn, rotationSensor, calmSetting = true, onCalm;
    private long lastStepMs, lastPublishMs;
    private Runnable onCalmChange;

    public void setValues(LiveValues v) { values = v; }

    /** Calm while moving: the owner's setting, and what to do when calm comes or goes. */
    public void setCalm(boolean enabled, Runnable onChange) {
        calmSetting = enabled;
        onCalmChange = onChange;
    }

    public boolean calm() { return onCalm; }

    public void setStrength(double s) { motion.setStrength(s); }

    @SuppressWarnings("deprecation")
    public void start(Context c) {
        if (running) return;
        running = true;
        Context app = c.getApplicationContext();
        sensors = app.getSystemService(SensorManager.class);
        WindowManager wm = c.getSystemService(WindowManager.class);
        display = wm == null ? null : wm.getDefaultDisplay();
        if (sensors != null) {
            Sensor a = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (a != null) sensors.registerListener(this, a, SensorManager.SENSOR_DELAY_GAME, main);
            Sensor r = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
            if (r == null) r = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            rotationSensor = r != null;
            if (r != null) sensors.registerListener(this, r, SensorManager.SENSOR_DELAY_GAME, main);
        }
        startGps(app);
        lastStepMs = 0;
        main.post(tick);
    }

    public void stop() {
        if (!running) return;
        running = false;
        main.removeCallbacks(tick);
        if (sensors != null) sensors.unregisterListener(this);
        if (locations != null && gpsOn) {
            try {
                locations.removeUpdates(this);
            } catch (RuntimeException ignored) {
                // nothing was registered
            }
        }
        gpsOn = false;
    }

    /** Location may just have been allowed. */
    public void retryGps(Context c) { if (running && !gpsOn) startGps(c.getApplicationContext()); }

    private void startGps(Context app) {
        try {
            if (app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
            locations = app.getSystemService(LocationManager.class);
            if (locations == null || !locations.getAllProviders().contains(LocationManager.GPS_PROVIDER)) return;
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 0, this, Looper.getMainLooper());
            gpsOn = true;
        } catch (RuntimeException e) {
            // no usable GPS: the truck still moves with the motion sensor when mounted
        }
    }

    /** Steps the model to now (the 3D view calls this every frame; the timer covers the rest). */
    public void step(long nowMs) {
        if (lastStepMs != 0 && nowMs - lastStepMs < MIN_STEP_MS) return;
        double dt = lastStepMs == 0 ? STEP_MS / 1000.0 : (nowMs - lastStepMs) / 1000.0;
        lastStepMs = nowMs;
        LiveValues v = values;
        if (v != null) {
            Long at = v.timeOf("speed");
            Double mph = v.get("speed", nowMs);
            if (mph != null && at != null) motion.road(mph, at);
            Double pull = v.get("g_accel", nowMs);
            Long pAt = v.timeOf("g_accel");
            if (pull != null && pAt != null) motion.pull(pull, pAt);
        }
        motion.step(nowMs, dt);
        boolean c = calm.update(motion.speedMps() * 2.23694, calmSetting);
        parallax.setCalm(motion.mount.mounted() || motion.speedMps() > 2);
        if (c != onCalm) {
            onCalm = c;
            if (onCalmChange != null) onCalmChange.run();
        }
        if (v != null && nowMs - lastPublishMs >= PUBLISH_MS) {
            lastPublishMs = nowMs;
            v.put("roll", motion.realRollDeg(), nowMs);
            v.put("pitch", motion.realPitchDeg(), nowMs);
        }
    }

    private void tick() {
        if (!running) return;
        main.postDelayed(tick, STEP_MS);
        step(now());
    }

    private static long now() { return android.os.SystemClock.elapsedRealtime(); }

    private int rotation() { return display == null ? 0 : display.getRotation(); }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (!running) return;
        long t = now();
        int type = e.sensor == null ? -1 : e.sensor.getType();
        if (type == Sensor.TYPE_ACCELEROMETER && e.values.length >= 3) {
            double[] s = MountDetector.toScreen(rotation(), e.values[0], e.values[1], e.values[2]);
            motion.mount.sample(s[0], s[1], s[2], t);
            if (!rotationSensor) {
                // no rotation sensor: tilt left/right and back/forward stand in for turning
                double side = Math.toDegrees(Math.atan2(s[0], s[1])), back = Math.toDegrees(Math.atan2(s[2], s[1]));
                parallax.sample(side, back, t);
            }
        } else if ((type == Sensor.TYPE_GAME_ROTATION_VECTOR || type == Sensor.TYPE_ROTATION_VECTOR) && e.values.length >= 3) {
            SensorManager.getRotationMatrixFromVector(rot, e.values);
            int x = SensorManager.AXIS_X, y = SensorManager.AXIS_Y;
            switch (rotation()) {
                case 1: x = SensorManager.AXIS_Y; y = SensorManager.AXIS_MINUS_X; break;
                case 2: x = SensorManager.AXIS_MINUS_X; y = SensorManager.AXIS_MINUS_Y; break;
                case 3: x = SensorManager.AXIS_MINUS_Y; y = SensorManager.AXIS_X; break;
                default: break;
            }
            SensorManager.remapCoordinateSystem(rot, x, y, remapped);
            SensorManager.getOrientation(remapped, angles);
            parallax.sample(-Math.toDegrees(angles[0]), Math.toDegrees(angles[1]), t);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override
    public void onLocationChanged(Location l) {
        if (!running || !l.hasSpeed()) return;
        motion.gps.fix(l.getSpeed(), l.hasBearing() ? l.getBearing() : Double.NaN, now());
    }

    @Override
    public void onProviderEnabled(String provider) { }

    @Override
    public void onProviderDisabled(String provider) { }

    @Override
    @SuppressWarnings("deprecation")
    public void onStatusChanged(String provider, int status, Bundle extras) { }
}
