package com.strickland.jeepdash.core;

import java.util.function.LongSupplier;

/**
 * Whether the dash should keep its hands off the dongle because the Jeep Tune app is using it.
 * Jeep Tune says busy=true when a read starts and again every 30 s; busy=false when it ends. If the
 * heartbeat stops for {@link #HEARTBEAT_TIMEOUT_MS} (Tune crashed or was closed) the pause ends by itself.
 */
public final class PauseGate {
    public static final long HEARTBEAT_TIMEOUT_MS = 90000;

    private final LongSupplier clock;
    private boolean paused;
    private boolean endedByTimeout;
    private long lastBusyMs;

    public PauseGate(LongSupplier clockMs) { this.clock = clockMs; }

    /** Jeep Tune's broadcast: busy=true starts or renews the pause, busy=false ends it. */
    public synchronized void onBusy(boolean busy) {
        if (busy) {
            paused = true;
            endedByTimeout = false;
            lastBusyMs = clock.getAsLong();
        } else {
            paused = false;
            endedByTimeout = false;
        }
    }

    /** True while the dongle must be left alone. Applies the 90 s failsafe. */
    public synchronized boolean isPaused() {
        if (paused && clock.getAsLong() - lastBusyMs >= HEARTBEAT_TIMEOUT_MS) {
            paused = false;
            endedByTimeout = true;
        }
        return paused;
    }

    /** True when the last pause ended because the heartbeat stopped rather than because Tune said it was done. */
    public synchronized boolean endedByTimeout() {
        isPaused();
        return endedByTimeout;
    }
}
