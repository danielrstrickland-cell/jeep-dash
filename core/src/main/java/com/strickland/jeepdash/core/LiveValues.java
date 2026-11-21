package com.strickland.jeepdash.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Latest reading per value, with its time; a reading older than 3 s counts as missing. */
public final class LiveValues {
    public static final long STALE_MS = 3000;

    private final Map<String, double[]> latest = new ConcurrentHashMap<>();
    private final Map<String, Long> staleMs = new ConcurrentHashMap<>();
    private long holdFrom, holdUntil = -1;
    private boolean holding;

    public void put(String id, double value, long nowMs) { latest.put(id, new double[]{value, nowMs}); }

    /** When the latest reading of this value was put, or null if there is none. */
    public Long timeOf(String id) {
        double[] e = latest.get(id);
        return e == null ? null : (long) e[1];
    }

    /** Gives one value its own stale time (slow values read only every few seconds). */
    public void setStaleMs(String id, long ms) { staleMs.put(id, ms); }

    /** Forgets every reading and any hold: the link dropped or the engine computer went silent. */
    public synchronized void clear() {
        latest.clear();
        holding = false;
        holdUntil = -1;
    }

    public void clearStaleMs() { staleMs.clear(); }

    /**
     * While the dongle is away on the other line (fromMs..untilMs), readings that were fresh at fromMs
     * stay on screen instead of blanking.
     */
    public synchronized void hold(long fromMs, long untilMs) {
        holdFrom = fromMs;
        holdUntil = untilMs;
        holding = true;
    }

    /** The dongle is back: held readings get the normal stale time from now to be refreshed. */
    public synchronized void endHold(long nowMs) {
        if (!holding) return;
        holding = false;
        holdUntil = Math.min(holdUntil, nowMs + STALE_MS);
    }

    public synchronized Double get(String id, long nowMs) {
        double[] e = latest.get(id);
        if (e == null) return null;
        long t = (long) e[1];
        Long own = staleMs.get(id);
        long stale = own != null ? own : STALE_MS;
        if (nowMs - t <= stale) return e[0];
        if (holdUntil >= 0 && nowMs <= holdUntil && holdFrom - t <= stale) return e[0];
        return null;
    }
}
