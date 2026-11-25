package com.strickland.jeepdash.core;

/**
 * Everything the 3D truck does on its own, from whatever data there is: the tablet's motion sensor when it is
 * mounted (MountDetector), otherwise GPS (GpsTurn) and the car's road speed and pull. The two are blended
 * smoothly; the result is clamped, slew-limited and smoothed so a knock of a loose tablet never throws the truck
 * about, then drives the body (BodyMotion), the wheels' spin and the front wheels' steer. Another scene (a night
 * trail, say) can use the same numbers. Not thread-safe: step it from one thread.
 */
public final class TruckMotion {
    static final double G = 9.80665, MPS_PER_MPH = 0.44704;
    public static final double G_LIMIT = 0.8, G_SLEW = 3.0;              // g, g per second
    static final double G_SMOOTH_S = 0.12, STEER_SMOOTH_S = 0.3;
    static final double BLEND_UP_PER_S = 0.5, BLEND_DOWN_PER_S = 1.5;
    /** The ZJ: 105.9 in wheelbase, 31 in tyres. */
    public static final double WHEELBASE_M = 2.69, TIRE_RADIUS_M = 0.3937, MAX_STEER_DEG = 32;
    static final long STALE_MS = 3000;
    private static final long NEVER = Long.MIN_VALUE / 2;

    public final MountDetector mount = new MountDetector();
    public final GpsTurn gps = new GpsTurn();
    private final BodyMotion shown, real = new BodyMotion(0);

    private double weight, lat, lon, steer, wheel, speed;
    private double roadMps, pullG;
    private long roadMs = NEVER, pullMs = NEVER;

    /** @param strength 0 realistic .. 1 exaggerated, for the truck on screen (the readings stay realistic) */
    public TruckMotion(double strength) { shown = new BodyMotion(strength); }

    public void setStrength(double s) { shown.setStrength(s); }

    /** The car's road speed reading (corrected), mph. */
    public void road(double mph, long ms) {
        roadMps = Math.max(0, mph) * MPS_PER_MPH;
        roadMs = ms;
    }

    /** The car-made pull / braking value (g), worked out from road speed. */
    public void pull(double g, long ms) {
        pullG = g;
        pullMs = ms;
    }

    public void step(long nowMs, double dtSec) {
        if (!(dtSec > 0)) return;
        dtSec = Math.min(dtSec, 0.5);
        boolean mounted = mount.mounted();
        weight = mounted ? Math.min(1, weight + BLEND_UP_PER_S * dtSec) : Math.max(0, weight - BLEND_DOWN_PER_S * dtSec);

        speed = nowMs - roadMs <= STALE_MS ? roadMps : gps.speed(nowMs);
        double gLat = gps.latG(nowMs);
        Double gpsLon = gps.lonG(nowMs);
        double gLon = nowMs - pullMs <= STALE_MS ? pullG : gpsLon != null ? gpsLon : 0;
        Double sl = mount.latG(), sf = mount.lonG();
        double sLat = sl != null ? sl : gLat, sLon = sf != null ? sf : gLon;

        double wantLat = clamp(weight * sLat + (1 - weight) * gLat, G_LIMIT);
        double wantLon = clamp(weight * sLon + (1 - weight) * gLon, G_LIMIT);
        double k = 1 - Math.exp(-dtSec / G_SMOOTH_S), slew = G_SLEW * dtSec;
        lat += clamp((wantLat - lat) * k, slew);
        lon += clamp((wantLon - lon) * k, slew);

        double sensorYaw = sLat * G / Math.max(speed, 3);
        double yaw = weight * sensorYaw + (1 - weight) * gps.yawRate(nowMs);
        steer += (steerFor(yaw, speed) - steer) * (1 - Math.exp(-dtSec / STEER_SMOOTH_S));
        if (Math.abs(steer) < 1e-4 && speed < 0.5) steer = 0;

        wheel = (wheel + speed / TIRE_RADIUS_M * dtSec) % (2 * Math.PI);
        shown.step(lat, lon, dtSec);
        real.step(lat, lon, dtSec);
    }

    /** Front wheel angle (degrees, positive left) for a turn rate at a speed: the bicycle model, capped. */
    public static double steerFor(double yawRad, double mps) {
        if (mps < 0.5) return 0;
        double d = Math.toDegrees(Math.atan(WHEELBASE_M * yawRad / mps));
        return clamp(d, MAX_STEER_DEG) * Math.min(1, mps / 2);
    }

    /** 0 = all GPS, 1 = all motion sensor. */
    public double sensorWeight() { return weight; }

    public double latG() { return lat; }

    public double lonG() { return lon; }

    /** The truck on screen, at the chosen strength. */
    public double rollDeg() { return shown.rollDeg(); }

    public double pitchDeg() { return shown.pitchDeg(); }

    /** The realistic figures, for the Body roll and Body pitch readings. */
    public double realRollDeg() { return real.rollDeg(); }

    public double realPitchDeg() { return real.pitchDeg(); }

    public double steerDeg() { return steer; }

    /** How far the wheels have turned, radians (0..2π). */
    public double wheelTurnRad() { return wheel; }

    public double speedMps() { return speed; }

    /** True while anything on the truck is moving: worth drawing often. */
    public boolean moving() { return speed > 0.3 || !shown.settled() || Math.abs(steer) > 0.05; }

    static double clamp(double v, double limit) { return Math.max(-limit, Math.min(limit, v)); }
}
