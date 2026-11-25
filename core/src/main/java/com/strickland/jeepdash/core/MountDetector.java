package com.strickland.jeepdash.core;

/**
 * Tells from the tablet's accelerometer whether it is fixed in a mount, and if so turns its readings into the
 * vehicle's lateral and fore-aft G. Mounted means: standing up (not lying flat), gravity pointing the same way
 * for a few seconds, and little jostle. The mount is taken to face the driver, so forward is into the screen.
 * A knock (one reading far from the rest) is skipped; several in a row, a big turn of the tablet, laying it
 * flat or heavy jostling end the mount at once. Readings are in m/s², gravity included, already turned to the
 * screen's axes (see toScreen); times in milliseconds on one clock.
 */
public final class MountDetector {
    static final double G = 9.80665;
    static final double FAST_MS = 400, SLOW_MS = 4000, JOSTLE_MS = 800, KNOCK_MS = 2000, FRAME_MS = 10000, OUT_MS = 150;
    static final double SETTLE_DEG = 12, LEAN_DEG = 25, BREAK_DEG = 40;      // fast against slow gravity
    static final double JOSTLE_OK = 2.0, JOSTLE_BREAK = 4.5;                // m/s², root mean square
    static final double KNOCK = 6.0, KNOCKS_BREAK = 4;                       // m/s² off the fast gravity
    static final double FLAT = 0.85, FLAT_BREAK = 0.92;                      // share of gravity along the screen's z
    static final double REST = 0.4;                                          // m/s²: near rest, gravity may be relearned
    static final long SETTLE_MS = 5000, LEAN_MS = 6000, RESTART_MS = 3000;
    static final double LIMIT_G = 1.2;

    private final double[] fast = new double[3], slow = new double[3], frame = new double[3];
    private double jostle, knocks, lat, lon;
    private long lastMs = Long.MIN_VALUE / 2, steadySince = -1, leanSince = -1;
    private boolean started, mounted;

    /**
     * Device axes to the screen's (x right, y up, z out of the screen) for a display rotation 0-3 (Android's
     * Surface.ROTATION_0..270, the turn of the drawing against the device's natural way up).
     */
    public static double[] toScreen(int rotation, double x, double y, double z) {
        switch (rotation & 3) {
            case 1: return new double[]{-y, x, z};
            case 2: return new double[]{-x, -y, z};
            case 3: return new double[]{y, -x, z};
            default: return new double[]{x, y, z};
        }
    }

    public synchronized void sample(double x, double y, double z, long ms) {
        long dt = ms - lastMs;
        if (!started || dt > RESTART_MS || dt < 0) {
            set(fast, x, y, z);
            set(slow, x, y, z);
            jostle = 0;
            knocks = 0;
            mounted = false;
            steadySince = -1;
            leanSince = -1;
            started = true;
            lastMs = ms;
            return;
        }
        lastMs = ms;
        knocks *= Math.exp(-dt / KNOCK_MS);
        double dx = x - fast[0], dy = y - fast[1], dz = z - fast[2];
        if (Math.sqrt(dx * dx + dy * dy + dz * dz) > KNOCK) {
            knocks += 1;                                  // skipped: a knock says nothing about how it is held
            if (knocks > KNOCKS_BREAK) {
                // not knocks after all: it was turned or dropped. Start again from here.
                unmount();
                set(fast, x, y, z);
                set(slow, x, y, z);
                jostle = 0;
                knocks = 0;
            }
            return;
        }
        blend(fast, x, y, z, k(dt, FAST_MS));
        blend(slow, x, y, z, k(dt, SLOW_MS));
        jostle += (dx * dx + dy * dy + dz * dz - jostle) * k(dt, JOSTLE_MS);

        double tilt = angleDeg(fast, slow), rms = Math.sqrt(jostle);
        double slowMag = len(slow), fastMag = len(fast);
        double slowFlat = Math.abs(slow[2]) / Math.max(1e-9, slowMag), fastFlat = Math.abs(fast[2]) / Math.max(1e-9, fastMag);

        if (mounted) {
            boolean broken = tilt > BREAK_DEG || rms > JOSTLE_BREAK || fastFlat > FLAT_BREAK || fastMag < 6 || fastMag > 14;
            if (tilt > LEAN_DEG) {
                if (leanSince < 0) leanSince = ms;
                if (ms - leanSince >= LEAN_MS) broken = true;
            } else {
                leanSince = -1;
            }
            if (broken) {
                unmount();
            } else {
                // gravity is relearned slowly, and only near rest, so a long sweeping turn is not taken for it
                double rx = x - frame[0], ry = y - frame[1], rz = z - frame[2];
                if (Math.sqrt(rx * rx + ry * ry + rz * rz) < REST) blend(frame, x, y, z, k(dt, FRAME_MS));
                vehicleG(x, y, z, dt);
            }
        } else {
            boolean good = tilt < SETTLE_DEG && rms < JOSTLE_OK && slowFlat < FLAT && slowMag > 7.5 && slowMag < 12;
            if (!good) {
                steadySince = -1;
            } else {
                if (steadySince < 0) steadySince = ms;
                if (ms - steadySince >= SETTLE_MS) {
                    mounted = true;
                    leanSince = -1;
                    System.arraycopy(slow, 0, frame, 0, 3);
                    lat = 0;
                    lon = 0;
                }
            }
        }
    }

    public synchronized boolean mounted() { return mounted; }

    /** Lateral G, positive toward the left; null when not mounted. */
    public synchronized Double latG() { return mounted ? lat : null; }

    /** Fore-aft G, positive speeding up; null when not mounted. */
    public synchronized Double lonG() { return mounted ? lon : null; }

    private void unmount() {
        mounted = false;
        steadySince = -1;
        leanSince = -1;
    }

    /** The reading less the learned gravity, along the vehicle's left and forward. */
    private void vehicleG(double x, double y, double z, long dt) {
        double m = len(frame);
        double ux = frame[0] / m, uy = frame[1] / m, uz = frame[2] / m;
        // forward: into the screen (-z), flattened to level
        double d = -uz, fx = -ux * d, fy = -uy * d, fz = -1 - uz * d;
        double fm = Math.sqrt(fx * fx + fy * fy + fz * fz);
        fx /= fm; fy /= fm; fz /= fm;
        // left = up x forward
        double lx = uy * fz - uz * fy, ly = uz * fx - ux * fz, lz = ux * fy - uy * fx;
        double ax = x - frame[0], ay = y - frame[1], az = z - frame[2];
        double rawLat = clamp((ax * lx + ay * ly + az * lz) / G), rawLon = clamp((ax * fx + ay * fy + az * fz) / G);
        double kk = k(dt, OUT_MS);
        lat += (rawLat - lat) * kk;
        lon += (rawLon - lon) * kk;
    }

    private static double clamp(double g) { return Math.max(-LIMIT_G, Math.min(LIMIT_G, g)); }

    private static double k(long dtMs, double smoothMs) { return 1 - Math.exp(-Math.max(0, dtMs) / smoothMs); }

    private static void set(double[] v, double x, double y, double z) { v[0] = x; v[1] = y; v[2] = z; }

    private static void blend(double[] v, double x, double y, double z, double k) {
        v[0] += (x - v[0]) * k;
        v[1] += (y - v[1]) * k;
        v[2] += (z - v[2]) * k;
    }

    private static double len(double[] v) { return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); }

    private static double angleDeg(double[] a, double[] b) {
        double la = len(a), lb = len(b);
        if (la < 1e-9 || lb < 1e-9) return 180;
        double c = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (la * lb);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, c))));
    }
}
