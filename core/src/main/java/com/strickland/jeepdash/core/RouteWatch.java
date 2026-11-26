package com.strickland.jeepdash.core;

/**
 * Steadies the report of where the sound is going. Bluetooth to a car stereo drops and returns within
 * seconds often enough (it did on the first drive: a 24 second gap swapped the profile just before a
 * Calibrate), so a new way out only counts once it has held for a while.
 */
public final class RouteWatch {
    public static final long HOLD_MS = 15000;
    private String seen = "";
    private long since;

    /** Feed the way out as it is now; returns it once it has held steady long enough, else null. */
    public synchronized String settled(String route, long nowMs) {
        if (route == null) route = "";
        if (!route.equals(seen)) {
            seen = route;
            since = nowMs;
        }
        return !seen.isEmpty() && nowMs - since >= HOLD_MS ? seen : null;
    }
}
