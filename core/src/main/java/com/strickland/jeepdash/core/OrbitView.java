package com.strickland.jeepdash.core;

/**
 * The owner's own view of the 3D truck: dragging swings the camera round it and up or down, pinching moves it in
 * or out, a double tap puts it back. Up and down stop short of the floor and of looking straight down.
 */
public final class OrbitView {
    /** Degrees the camera swings per dp dragged. */
    static final double YAW_PER_DP = 0.45, PITCH_PER_DP = 0.3;
    /** The camera's height angle stays between these (degrees above the floor). */
    static final double MIN_ELEVATION = 2, MAX_ELEVATION = 70;
    static final double MIN_ZOOM = 0.7, MAX_ZOOM = 2.2;

    private double yaw, pitch, zoom = 1;

    /** A drag of dx, dy dp: right swings the view round to the right, up raises the camera. */
    public synchronized void drag(double dxDp, double dyDp) {
        yaw = (yaw - dxDp * YAW_PER_DP) % 360;
        pitch = clampPitch(pitch + dyDp * PITCH_PER_DP);
    }

    /** A pinch: the fingers' spread now over their spread at the last call. */
    public synchronized void pinch(double ratio) {
        if (!(ratio > 0)) return;
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * ratio));
    }

    public synchronized void reset() {
        yaw = 0;
        pitch = 0;
        zoom = 1;
    }

    public synchronized double yawDeg() { return yaw; }

    public synchronized double pitchDeg() { return pitch; }

    public synchronized double zoom() { return zoom; }

    /** The owner's pitch plus another one (the parallax), kept off the floor and short of straight down. */
    public synchronized double pitchWith(double otherDeg) {
        double e = HoloFraming.ELEVATION_DEG + pitch + otherDeg;
        return Math.max(MIN_ELEVATION, Math.min(MAX_ELEVATION, e)) - HoloFraming.ELEVATION_DEG;
    }

    private static double clampPitch(double p) {
        return Math.max(MIN_ELEVATION - HoloFraming.ELEVATION_DEG, Math.min(MAX_ELEVATION - HoloFraming.ELEVATION_DEG, p));
    }
}
