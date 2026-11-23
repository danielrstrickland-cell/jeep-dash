package com.strickland.jeepdash.core;

/**
 * For tablets without the linear-acceleration sensor (no gyroscope): takes gravity out of the plain
 * accelerometer. Gravity is a slow-moving average of the readings (time constant 2 s), so a push or a brake
 * shows at once while turning the tablet to a new angle settles back to nothing in a few seconds. The first
 * reading, or the first after a long silence, is taken as gravity. Times in milliseconds on one clock.
 */
public final class Gravity {
    static final double TIME_MS = 2000, RESTART_MS = 3000;
    private static final long NEVER = Long.MIN_VALUE / 2;

    private double gx, gy, gz;
    private long lastMs = NEVER;

    /** The reading less gravity, in the same units (m/s²), as {x, y, z}. */
    public synchronized double[] linear(double x, double y, double z, long nowMs) {
        long dt = nowMs - lastMs;
        if (dt > RESTART_MS || dt < 0) {
            gx = x;
            gy = y;
            gz = z;
        } else {
            double k = 1 - Math.exp(-dt / TIME_MS);
            gx += (x - gx) * k;
            gy += (y - gy) * k;
            gz += (z - gz) * k;
        }
        lastMs = nowMs;
        return new double[]{x - gx, y - gy, z - gz};
    }
}
