package com.strickland.jeepdash.core;

import java.util.Collection;

/**
 * What the tablet's own sensors add to the dash: GPS speed, G-force, pull and braking, a 0-60 timer and how
 * far off the Jeep's speed reading is. Every time is in milliseconds on one clock, supplied by the caller.
 */
public final class Motion {
    static final long STALE_MS = 3000;             // a source silent this long reads blank
    static final long DASH_GONE_MS = 1500;         // no Jeep speed for this long: GPS speed stands in
    static final double G = 9.80665, MPH = 0.44704;                // m/s² in one g; m/s in one mph
    static final double G_FORCE_SMOOTH_MS = 250, PULL_SMOOTH_MS = 400;
    static final double STOPPED_MPH = 1, SHOVE_G = 0.08, RUN_LIMIT_MS = 60000;
    static final double CRUISE_MPH = 30, STEADY_G = 0.05;          // when the two speeds are fair to compare
    static final int ERROR_SAMPLES = 10, ERROR_MEMORY = 300;
    private static final long NEVER = Long.MIN_VALUE / 2;          // halved so "now - NEVER" cannot overflow

    private double gpsMph, dashMph;
    private long gpsMs = NEVER, dashMs = NEVER;
    private double ax, ay, az;                     // smoothed acceleration, m/s²
    private long accelMs = NEVER;

    private double roadMph, pull;                  // the last road-speed reading; smoothed pull in g
    private long roadMs = NEVER, pullMs = NEVER;
    private boolean roadFromJeep;

    private boolean armed, running;                // the 0-60 timer
    private long stoppedMs, shoveMs = NEVER, startMs;
    private Double lastRun;

    private double errorPct;
    private int errorSamples;

    /** True when a value on screen is worked out from the Jeep's speed, so the speed has to be read as well. */
    public static boolean needsJeepSpeed(Collection<String> shownIds) {
        return shownIds.contains("g_accel") || shownIds.contains("zero_sixty") || shownIds.contains("speedo_err");
    }

    /** True when a value on screen cannot be worked out without GPS. */
    public static boolean needsGps(Collection<String> shownIds) {
        return shownIds.contains("gps_mph") || shownIds.contains("speedo_err");
    }

    /** A new road-speed reading from the Jeep. */
    public synchronized void dash(double mph, long nowMs) {
        dashMph = mph;
        dashMs = nowMs;
        road(mph, nowMs, true);
    }

    /** A GPS fix's speed. */
    public synchronized void gps(double mph, long nowMs) {
        gpsMph = mph;
        gpsMs = nowMs;
        if (nowMs - dashMs > DASH_GONE_MS) {
            road(mph, nowMs, false);
            return;
        }
        // Both speeds are fresh: at a steady cruise the gap between them is the speedometer's error.
        Double steady = gAccel(nowMs);
        if (mph < CRUISE_MPH || steady == null || Math.abs(steady) >= STEADY_G) return;
        errorSamples = Math.min(errorSamples + 1, ERROR_MEMORY);       // a plain average at first, then a slow one
        errorPct += ((dashMph / mph - 1) * 100 - errorPct) / errorSamples;
    }

    /** Linear acceleration in m/s², gravity already removed; the tablet may be mounted any way round. */
    public synchronized void accel(double x, double y, double z, long nowMs) {
        // Each axis is smoothed before the size is taken, so vibration cancels instead of adding up.
        double k = nowMs - accelMs > STALE_MS ? 1 : weight(nowMs - accelMs, G_FORCE_SMOOTH_MS);
        ax += (x - ax) * k;
        ay += (y - ay) * k;
        az += (z - az) * k;
        accelMs = nowMs;
        // The first shove after the last reading at rest; an earlier one was a bump, not the launch.
        if (shoveMs <= stoppedMs && Math.sqrt(x * x + y * y + z * z) / G > SHOVE_G) shoveMs = nowMs;
    }

    /** GPS speed in mph; null when the last fix is old. */
    public synchronized Double gpsMph(long nowMs) { return nowMs - gpsMs > STALE_MS ? null : gpsMph; }

    /** Total acceleration in g, whichever way it points; null when the sensor is silent. */
    public synchronized Double gForce(long nowMs) {
        return nowMs - accelMs > STALE_MS ? null : Math.sqrt(ax * ax + ay * ay + az * az) / G;
    }

    /** Forward pull (+) or braking (-) in g, from how fast the road speed is changing. */
    public synchronized Double gAccel(long nowMs) { return nowMs - pullMs > STALE_MS ? null : pull; }

    /** Seconds: the running clock during a run, otherwise the last completed 0-60 (null before the first). */
    public synchronized Double zeroSixty(long nowMs) {
        if (running && nowMs - roadMs <= STALE_MS) return Math.max(0, nowMs - startMs) / 1000.0;
        return lastRun;
    }

    /** Percent the Jeep's speed reads above (+) or below (-) GPS; null until there is enough steady cruising. */
    public synchronized Double speedoError(long nowMs) { return errorSamples < ERROR_SAMPLES ? null : errorPct; }

    /** One road-speed reading, from the Jeep or from GPS standing in: drives the pull gauge and the timer. */
    private void road(double mph, long t, boolean fromJeep) {
        // Only a reading that follows the last one closely, from the same source, can be compared with it.
        boolean follows = fromJeep == roadFromJeep && t - roadMs <= STALE_MS;
        if (follows && t <= roadMs) return;
        if (follows) {
            double g = (mph - roadMph) * MPH / G / ((t - roadMs) / 1000.0);
            pull = t - pullMs > STALE_MS ? g : pull + (g - pull) * weight(t - roadMs, PULL_SMOOTH_MS);
            pullMs = t;
        } else {
            armed = false;         // a gap in the readings: a run timed across it would be made up
            running = false;
        }
        time(mph, t);
        roadMph = mph;
        roadMs = t;
        roadFromJeep = fromJeep;
    }

    /** The 0-60 timer. roadMph and roadMs still hold the reading before this one. */
    private void time(double mph, long t) {
        if (mph < STOPPED_MPH) {
            running = false;       // never reached sixty: the last full run stays on screen
            armed = true;
            stoppedMs = t;
            return;
        }
        if (armed) {
            // The Jeep moves before the speed reading shows it; the first shove is the truer start.
            armed = false;
            running = true;
            startMs = shoveMs > stoppedMs && shoveMs <= t ? shoveMs : stoppedMs;
        }
        if (!running) return;
        // Sixty falls between two readings: work out where.
        double end = mph < 60 ? t : roadMs + (t - roadMs) * (60 - roadMph) / (mph - roadMph);
        if (end - startMs > RUN_LIMIT_MS) {
            running = false;
        } else if (mph >= 60) {
            running = false;
            lastRun = (end - startMs) / 1000.0;
        }
    }

    /** How far a smoothed value moves toward a new reading that came dtMs after the last. */
    private static double weight(long dtMs, double smoothMs) { return 1 - Math.exp(-Math.max(0, dtMs) / smoothMs); }
}
