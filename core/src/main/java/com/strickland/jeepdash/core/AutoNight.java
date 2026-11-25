package com.strickland.jeepdash.core;

/**
 * Decides light or dark for the dash's Auto brightness. With a light sensor: dark after the room has stayed
 * under 15 lux for 10 s, light after it has stayed over 60 lux for 10 s (readings in between change nothing,
 * so a passing streetlight or a tunnel does not flicker the screen). The first reading sets the starting state
 * straight away. Without a sensor reading it follows sunset and sunrise at the last GPS position; with neither
 * it has no opinion (null) and the caller leaves the screen as it is.
 */
public final class AutoNight {
    public static final double DARK_BELOW_LUX = 15, LIGHT_ABOVE_LUX = 60;
    public static final long HOLD_MS = 10_000;
    private static final long NONE = Long.MIN_VALUE;

    private boolean haveLux, dark, havePlace;
    private double lux, lat, lon;
    private long since = NONE;           // when the reading started pointing at the other state

    /** A light sensor reading at nowMs (any steady clock). */
    public void lux(double lux, long nowMs) {
        if (Double.isNaN(lux) || lux < 0) return;
        boolean first = !haveLux;
        this.lux = lux;
        if (first) {
            haveLux = true;
            dark = lux < DARK_BELOW_LUX;
            since = NONE;
            return;
        }
        boolean wants = wantsSwitch();
        if (!wants) since = NONE;
        else if (since == NONE) since = nowMs;
        settle(nowMs);
    }

    /** Where the tablet is, from a GPS fix. */
    public void place(double latDeg, double lonDeg) {
        lat = latDeg;
        lon = lonDeg;
        havePlace = true;
    }

    public boolean hasLux() { return haveLux; }

    /**
     * True for dark, false for light, null when nothing is known. nowMs is the steady clock the lux readings
     * used; wallMs is the clock time (for the sun).
     */
    public Boolean dark(long nowMs, long wallMs) {
        if (haveLux) {
            settle(nowMs);
            return dark;
        }
        if (havePlace) return SunTimes.isDark(lat, lon, wallMs);
        return null;
    }

    private boolean wantsSwitch() { return dark ? lux > LIGHT_ABOVE_LUX : lux < DARK_BELOW_LUX; }

    private void settle(long nowMs) {
        if (since != NONE && wantsSwitch() && nowMs - since >= HOLD_MS) {
            dark = !dark;
            since = NONE;
        }
    }
}
