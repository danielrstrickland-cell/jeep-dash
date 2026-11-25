package com.strickland.jeepdash.core;

/**
 * A few degrees of parallax for the Holo look from how the tablet is turned: the turn away from where it usually
 * points (a slow average, so holding it at any angle drifts back to the middle), heavily smoothed and clamped,
 * so the layers slide against each other a little. While driving with the tablet mounted it is kept tiny, so
 * the road's motion does not make the scene swim. Angles in degrees, times in milliseconds.
 */
public final class Parallax {
    public static final double LIMIT_DEG = 3, CALM_SCALE = 0.15, GAIN = 0.5;
    static final double SMOOTH_MS = 400, CENTRE_MS = 5000, SCALE_MS = 600;
    static final long RESTART_MS = 2000;

    private double refYaw, refPitch, yaw, pitch, scale = 1;
    private boolean calm, started;
    private long lastMs;

    /** Tiny parallax (mounted, or moving): on or off. */
    public synchronized void setCalm(boolean c) {
        calm = c;
        if (!started) scale = c ? CALM_SCALE : 1;
    }

    /** The tablet's heading (any zero) and its tilt up and down. */
    public synchronized void sample(double yawDeg, double pitchDeg, long ms) {
        long dt = ms - lastMs;
        if (!started || dt > RESTART_MS || dt < 0) {
            refYaw = yawDeg;
            refPitch = pitchDeg;
            yaw = 0;
            pitch = 0;
            started = true;
            lastMs = ms;
            return;
        }
        lastMs = ms;
        double dy = wrap(yawDeg - refYaw), dp = wrap(pitchDeg - refPitch);
        double kc = k(dt, CENTRE_MS), ks = k(dt, SMOOTH_MS);
        refYaw = wrap(refYaw + dy * kc);
        refPitch = wrap(refPitch + dp * kc);
        yaw += (clamp(GAIN * dy) - yaw) * ks;
        pitch += (clamp(GAIN * dp) - pitch) * ks;
        scale += ((calm ? CALM_SCALE : 1) - scale) * k(dt, SCALE_MS);
    }

    /** Turn the view this far left/right (positive: the viewer moves right). */
    public synchronized double yawDeg() { return clamp(yaw * scale); }

    /** And up/down (positive: the viewer moves up). */
    public synchronized double pitchDeg() { return clamp(pitch * scale); }

    private static double clamp(double v) { return Math.max(-LIMIT_DEG, Math.min(LIMIT_DEG, v)); }

    private static double wrap(double d) { return d - 360 * Math.floor((d + 180) / 360); }

    private static double k(long dtMs, double smoothMs) { return 1 - Math.exp(-Math.max(0, dtMs) / smoothMs); }
}
