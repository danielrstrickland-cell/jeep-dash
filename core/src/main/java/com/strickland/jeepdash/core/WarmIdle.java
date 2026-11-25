package com.strickland.jeepdash.core;

/**
 * Tells the learning alerts (see NormalReadings) when the car is at warm idle, the one state steady enough to
 * compare from drive to drive: stopped, engine speed in the idle band, coolant warm, and all of it for ten
 * seconds. Also when the coolant has settled on the move (warm for five minutes, rolling). Fed once a second
 * with the latest road speed, engine speed and coolant; times are in milliseconds on one clock.
 */
public final class WarmIdle {
    static final double STOPPED_MPH = 0.5;                 // the speed reading is 0
    static final double IDLE_MIN_RPM = 450, IDLE_MAX_RPM = 1200;
    static final double JEEP_WARM_F = 180, PORSCHE_WARM_F = 176;      // the Porsche's is 80 °C
    static final long STEADY_MS = 10_000;
    static final double MOVING_MPH = 10;
    static final long SETTLED_MS = 300_000;
    /** Readings further apart than this are not one steady stretch (the link dropped, or a new drive). */
    static final long GAP_MS = 5000;
    /** Engine speed seen in the idle band keeps road speed and coolant on the read list this long. */
    static final long NEAR_IDLE_MS = 10_000;
    private static final long NEVER = Long.MIN_VALUE / 2;          // halved so "now - NEVER" cannot overflow

    private final double warmF;
    private long lastMs = NEVER, idleSince = NEVER, warmSince = NEVER, idleRpmMs = NEVER;
    private boolean stoppedWarm, moving;

    public WarmIdle(boolean porsche) { warmF = porsche ? PORSCHE_WARM_F : JEEP_WARM_F; }

    /** The latest readings (null when missing). */
    public synchronized void update(Double mph, Double rpm, Double coolantF, long nowMs) {
        if (nowMs - lastMs > GAP_MS) idleSince = warmSince = NEVER;
        lastMs = nowMs;
        boolean idleRpm = rpm != null && rpm >= IDLE_MIN_RPM && rpm <= IDLE_MAX_RPM;
        if (idleRpm) idleRpmMs = nowMs;
        boolean warm = coolantF != null && coolantF >= warmF;
        stoppedWarm = mph != null && mph < STOPPED_MPH && idleRpm && warm;
        moving = mph != null && mph >= MOVING_MPH;
        if (!stoppedWarm) idleSince = NEVER;
        else if (idleSince == NEVER) idleSince = nowMs;
        if (!warm) warmSince = NEVER;
        else if (warmSince == NEVER) warmSince = nowMs;
    }

    /** Stopped, idling and warm by the latest readings: the idle values are worth reading. */
    public synchronized boolean stoppedWarm() { return stoppedWarm; }

    /** Warm idle that has held for ten seconds: its readings count. */
    public synchronized boolean idle(long nowMs) { return stoppedWarm && nowMs - idleSince >= STEADY_MS; }

    /** Rolling with the engine warm for five minutes: the coolant has settled. */
    public synchronized boolean warmDriving(long nowMs) {
        return moving && warmSince != NEVER && nowMs - warmSince >= SETTLED_MS;
    }

    /** Engine speed was in the idle band lately: road speed and coolant are read to see whether it is warm idle. */
    public synchronized boolean nearIdle(long nowMs) { return nowMs - idleRpmMs <= NEAR_IDLE_MS; }
}
