package com.strickland.jeepdash.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Latest reading per value, with its time; a reading older than 3 s counts as missing. */
public final class LiveValues {
    public static final long STALE_MS = 3000;

    private final Map<String, double[]> latest = new ConcurrentHashMap<>();

    public void put(String id, double value, long nowMs) { latest.put(id, new double[]{value, nowMs}); }

    public Double get(String id, long nowMs) {
        double[] e = latest.get(id);
        if (e == null || nowMs - (long) e[1] > STALE_MS) return null;
        return e[0];
    }
}
