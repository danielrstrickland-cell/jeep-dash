package com.strickland.jeepdash.core;

/** Whether the sun is up at a place and time, for the dash's night mode when there is no light sensor. */
public final class SunTimes {
    private SunTimes() { }

    private static final double J2000_MS = 946_728_000_000.0;      // 2000-01-01 12:00 UTC
    /** The sun's centre this far below the horizon is sunset or sunrise (refraction and the sun's radius). */
    public static final double HORIZON_DEG = -0.833;

    /** Height of the sun above the horizon in degrees (negative at night). Accurate to about a quarter of a degree. */
    public static double elevationDeg(double latDeg, double lonDeg, long utcMs) {
        double d = (utcMs - J2000_MS) / 86_400_000.0;
        double g = Math.toRadians(357.528 + 0.9856003 * d);
        double lambda = Math.toRadians(280.460 + 0.9856474 * d + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g));
        double eps = Math.toRadians(23.439 - 0.0000004 * d);
        double dec = Math.asin(Math.sin(eps) * Math.sin(lambda));
        double ra = Math.atan2(Math.cos(eps) * Math.sin(lambda), Math.cos(lambda));
        double lst = Math.toRadians(((18.697374558 + 24.06570982441908 * d) * 15 + lonDeg) % 360);
        double ha = lst - ra;
        double lat = Math.toRadians(latDeg);
        double s = Math.sin(lat) * Math.sin(dec) + Math.cos(lat) * Math.cos(dec) * Math.cos(ha);
        return Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, s))));
    }

    /** True from sunset to sunrise. */
    public static boolean isDark(double latDeg, double lonDeg, long utcMs) {
        return elevationDeg(latDeg, lonDeg, utcMs) < HORIZON_DEG;
    }
}
