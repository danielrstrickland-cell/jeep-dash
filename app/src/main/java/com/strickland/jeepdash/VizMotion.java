package com.strickland.jeepdash;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * The tablet's G-meter and gyro for the visualizer: how hard the Jeep is turning, braking or pulling, turned
 * into a springy "slosh" the liquids lean with, plus the turn rate. Works however the tablet is mounted, by
 * measuring against gravity. Only reads the tablet's own sensors; nothing to do with the car link.
 */
final class VizMotion implements SensorEventListener {
    private static final float G = 9.81f;

    private SensorManager sensors;
    private final float[] up = {0, 0, 1}, lin = new float[3], gyro = new float[3];
    private boolean haveLinear;
    private long lastNs;
    // the spring the liquid sits on: where it is pushed to, where it is, and how fast it moves
    private float pushX, pushY, x, y, vx, vy;
    /** Turn rate, radians a second, positive turning left. */
    volatile float yaw;

    void start(Context c) {
        sensors = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        if (sensors == null) return;
        listen(Sensor.TYPE_GRAVITY);
        haveLinear = listen(Sensor.TYPE_LINEAR_ACCELERATION);
        if (!haveLinear) listen(Sensor.TYPE_ACCELEROMETER);
        listen(Sensor.TYPE_GYROSCOPE);
    }

    void stop() {
        if (sensors != null) sensors.unregisterListener(this);
        sensors = null;
    }

    private boolean listen(int type) {
        Sensor s = sensors.getDefaultSensor(type);
        return s != null && sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float[] v = e.values;
        switch (e.sensor.getType()) {
            case Sensor.TYPE_GRAVITY: {
                float n = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
                if (n > 1) for (int i = 0; i < 3; i++) up[i] = v[i] / n;
                break;
            }
            case Sensor.TYPE_LINEAR_ACCELERATION:
                System.arraycopy(v, 0, lin, 0, 3);
                push();
                break;
            case Sensor.TYPE_ACCELEROMETER:                   // no linear sensor: take gravity off by hand
                for (int i = 0; i < 3; i++) lin[i] = v[i] - up[i] * G;
                push();
                break;
            case Sensor.TYPE_GYROSCOPE:
                System.arraycopy(v, 0, gyro, 0, 3);
                yaw = gyro[0] * up[0] + gyro[1] * up[1] + gyro[2] * up[2];
                break;
            default:
                break;
        }
    }

    /** Splits the push into sideways (screen left-right) and forward-back, both level with the road. */
    private void push() {
        float along = lin[0] * up[0] + lin[1] * up[1] + lin[2] * up[2];
        float hx = lin[0] - along * up[0], hy = lin[1] - along * up[1], hz = lin[2] - along * up[2];
        // the screen faces the driver: its back points forward. Flat on the dash, the top edge points forward.
        float fx = -up[2] * up[0], fy = -up[2] * up[1], fz = 1 - up[2] * up[2];
        float n = (float) Math.sqrt(fx * fx + fy * fy + fz * fz), sign = -1;
        if (n < 0.3f) {
            fx = -up[1] * up[0];
            fy = 1 - up[1] * up[1];
            fz = -up[1] * up[2];
            n = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
            sign = 1;
        }
        float forward = n < 1e-3f ? 0 : sign * (hx * fx + hy * fy + hz * fz) / n;
        pushX = -hx / G;          // turning left pushes the liquid right
        pushY = forward / G;      // braking (negative) throws it forward, up the screen
    }

    /** Moves the spring on by dt seconds; scale is the TUNE "G-meter tilt". Returns nothing; read sloshX/Y. */
    void step(float dt, float scale) {
        if (dt <= 0) return;
        float tx = clamp(pushX * scale), ty = clamp(pushY * scale);
        for (int i = 0; i < 2; i++) {                         // two half steps keep the spring steady
            float h = dt / 2;
            vx += ((tx - x) * 60 - vx * 7) * h;
            vy += ((ty - y) * 60 - vy * 7) * h;
            x += vx * h;
            y += vy * h;
        }
    }

    /** Where the liquid has been pushed to, about -1..1 for a hard 1 g, x right and y down the screen. */
    float sloshX() { return x; }

    float sloshY() { return y; }

    /** The push forward right now, in g: positive speeding up, negative braking. */
    float forwardG() { return pushY; }

    private static float clamp(float v) { return Math.max(-1.2f, Math.min(1.2f, v)); }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }
}
