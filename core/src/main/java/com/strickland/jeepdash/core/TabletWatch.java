package com.strickland.jeepdash.core;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The tablet's own heat and battery, watched behind every screen (the link service runs it, layout or not):
 * battery temperature, charge and charging, charge current where the tablet reports it, and Android's thermal
 * status (raised to moderate when the thermal headroom says throttling is near). Gives the readings for the
 * Tablet temp / battery / heat gauges and the drive log, the heat protection (HeatGuard), and alerts: warm at
 * 45 °C, overheating at 50 °C, low battery at 15% when not charging, each clearing a little below its mark.
 */
public final class TabletWatch {
    public static final double WARM_C = 45, HOT_C = 50, CLEAR_C = 2;
    public static final int LOW_PCT = 15, CLEAR_PCT = 2;
    public static final int OK = 0, LOW = 1, WARM = 2, HOT = 3;

    public final HeatGuard guard = new HeatGuard();
    private double tempC = Double.NaN;
    private int pct = -1, status = HeatGuard.NONE, alert = OK;
    private boolean charging;
    private long microAmps;
    private float headroom = Float.NaN;

    /** True when the watch runs: on the Jeep, whatever the dash shows. */
    public static boolean wanted(boolean porsche) { return !porsche; }

    public synchronized void battery(double tempC, int pct, boolean charging, long nowMs) {
        this.tempC = tempC;
        this.pct = pct;
        this.charging = charging;
        updateAlert();
    }

    /** Charge current in µA, positive charging; 0 when the tablet does not say. */
    public synchronized void current(long microAmps) { this.microAmps = microAmps; }

    public synchronized void thermal(int status, long nowMs) { this.status = status; }

    /** Android's thermal headroom (1.0 = throttling); NaN when unknown. */
    public synchronized void headroom(float h) { headroom = h; }

    /** The heat level that counts: the thermal status, raised to moderate when throttling is near. */
    public synchronized int heat() {
        int s = Math.max(HeatGuard.NONE, Math.min(HeatGuard.CRITICAL, status));
        if (!Float.isNaN(headroom) && headroom >= 1.0f) s = Math.max(s, HeatGuard.MODERATE);
        return s;
    }

    /** Readings for the gauges and the drive log: temperature in °F, battery %, heat level, current in mA. */
    public synchronized Map<String, Double> values() {
        Map<String, Double> v = new LinkedHashMap<>();
        if (!Double.isNaN(tempC)) v.put("tablet_temp", tempC * 9 / 5 + 32);
        if (pct >= 0) v.put("tablet_batt", (double) pct);
        v.put("tablet_heat", (double) heat());
        if (microAmps != 0) v.put("tablet_ma", microAmps / 1000.0);
        return v;
    }

    private void updateAlert() {
        int t = OK;
        if (!Double.isNaN(tempC)) {
            if (tempC >= HOT_C || alert == HOT && tempC >= HOT_C - CLEAR_C) t = HOT;
            else if (tempC >= WARM_C || alert >= WARM && tempC >= WARM_C - CLEAR_C) t = WARM;
        }
        if (t == OK && pct >= 0 && !charging && (pct <= LOW_PCT || alert == LOW && pct < LOW_PCT + CLEAR_PCT)) t = LOW;
        alert = t;
    }

    /** OK, LOW, WARM or HOT. */
    public synchronized int alert() { return alert; }

    /** The alert in words, the temperature in the given unit (°F or °C); "" when there is none. */
    public synchronized String message(String unit) {
        boolean c = "°C".equals(unit);
        String t = Double.isNaN(tempC) ? "" : String.format(Locale.US, "%.0f%s", c ? tempC : tempC * 9 / 5 + 32, c ? "°C" : "°F");
        switch (alert) {
            case HOT: return "Tablet overheating, shade or cool it (" + t + ")";
            case WARM: return "Tablet hot: " + t;
            case LOW: return "Tablet battery low: " + pct + "%";
            default: return "";
        }
    }

    /** One line for the heat icon: temperature, battery and heat status. */
    public synchronized String summary(String unit) {
        boolean c = "°C".equals(unit);
        String t = Double.isNaN(tempC) ? "unknown" : String.format(Locale.US, "%.0f%s", c ? tempC : tempC * 9 / 5 + 32, c ? "°C" : "°F");
        String b = pct < 0 ? "unknown" : pct + "%" + (charging ? ", charging" : "");
        String ma = microAmps == 0 ? "" : String.format(Locale.US, " (%+.0f mA)", microAmps / 1000.0);
        return "Battery temperature " + t + ". Battery " + b + ma + ". Heat status: " + HeatGuard.name(heat()) + ".";
    }
}
