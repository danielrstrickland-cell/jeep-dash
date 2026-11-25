package com.strickland.jeepdash.core;

/**
 * Turn rate and G from GPS fixes alone, for when the tablet is not in its mount: lateral G is the heading's rate
 * of change times speed, fore-aft G the speed's rate of change. Below walking pace the heading is noise and is
 * not used; a heading that jumps faster than any truck can turn is skipped. Times in milliseconds.
 */
public final class GpsTurn {
    static final double G = 9.80665;
    static final double MIN_MPS = 2.5, MAX_YAW = 1.2, SMOOTH_MS = 600, LIMIT_G = 1.0;
    static final long STALE_MS = 3000;
    private static final long NEVER = Long.MIN_VALUE / 2;

    private double lastMps, lastBearing = Double.NaN, yaw, lon;
    private long lastMs = NEVER;
    private boolean haveLon;

    /** One fix: speed in m/s and the compass heading in degrees (NaN when the fix has none). */
    public synchronized void fix(double mps, double bearingDeg, long ms) {
        long dtMs = ms - lastMs;
        boolean follows = dtMs > 50 && dtMs <= STALE_MS;
        if (follows) {
            double dt = dtMs / 1000.0, k = 1 - Math.exp(-dtMs / SMOOTH_MS);
            double a = clamp((mps - lastMps) / dt / G, 0.8);
            lon = haveLon ? lon + (a - lon) * k : a;
            haveLon = true;
            if (!Double.isNaN(bearingDeg) && !Double.isNaN(lastBearing) && mps >= MIN_MPS && lastMps >= MIN_MPS) {
                double d = bearingDeg - lastBearing;
                d -= 360 * Math.floor((d + 180) / 360);          // -180..180
                double w = -Math.toRadians(d) / dt;              // compass turns clockwise: right is negative
                if (Math.abs(w) <= MAX_YAW * 1.5) yaw += (clamp(w, MAX_YAW) - yaw) * k;
            } else {
                yaw *= 1 - k;
            }
        } else {
            yaw = 0;
            haveLon = false;
        }
        lastMps = mps;
        lastBearing = bearingDeg;
        lastMs = ms;
    }

    /** Radians a second, positive turning left; 0 when unknown or stale. */
    public synchronized double yawRate(long nowMs) { return nowMs - lastMs > STALE_MS ? 0 : yaw; }

    /** Lateral G, positive toward the left; 0 when unknown. */
    public synchronized double latG(long nowMs) { return clamp(speed(nowMs) * yawRate(nowMs) / G, LIMIT_G); }

    /** Fore-aft G from the change in GPS speed; null when there is none fresh. */
    public synchronized Double lonG(long nowMs) { return haveLon && nowMs - lastMs <= STALE_MS ? lon : null; }

    /** The last GPS speed in m/s; 0 when stale. */
    public synchronized double speed(long nowMs) { return nowMs - lastMs > STALE_MS ? 0 : lastMps; }

    /** True when a fix came in lately. */
    public synchronized boolean fresh(long nowMs) { return nowMs - lastMs <= STALE_MS; }

    static double clamp(double v, double limit) { return Math.max(-limit, Math.min(limit, v)); }
}
