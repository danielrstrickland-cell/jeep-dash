package com.strickland.jeepdash.core;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Fuel flow, economy and fuel used, worked out by the screen: neither engine computer reports fuel use. The
 * Cayenne's comes from its air flow and lambda, the Jeep's (no air-flow sensor) from injector time and engine
 * speed. Everything is multiplied by a per-car factor that the fill-up calibration corrects. Times are in
 * milliseconds on one clock, supplied by the caller. Not thread-safe: the dash uses it on the main thread.
 */
public final class FuelUse {
    static final double AIR_PER_FUEL = 14.7;            // petrol burnt exactly (lambda 1.0), by weight
    static final double LB_PER_GAL = 6.17;
    static final int JEEP_INJECTORS = 8;
    /**
     * lb/h one Jeep injector flows when held open. 24 is a starting figure for the 5.9's stock injectors;
     * the fill-up calibration corrects it (and any error in the timing reading) through the factor.
     */
    static final double JEEP_INJECTOR_LB_H = 24;
    static final double LAMBDA_LOW = 0.5, LAMBDA_HIGH = 2.5;       // anything outside is not a real reading
    /** A reading older than this is not used; a step between readings longer than this adds nothing. */
    public static final long FRESH_MS = 2000, GAP_MS = 2000;
    /** Flow and economy go blank when no new readings came for this long. */
    public static final long STALE_MS = 2000;
    static final double STOPPED_MPH = 1, MAX_MPG = 99, MIN_GAL_FOR_AVERAGE = 0.01;
    /** Fill-up calibration guards. */
    public static final double MIN_CALIBRATE_GAL = 2, MIN_STEP = 0.5, MAX_STEP = 2, MIN_FACTOR = 0.3, MAX_FACTOR = 3;

    public static final List<String> GAUGES = Collections.unmodifiableList(
            Arrays.asList("mpg", "mpg_avg", "fuel_used", "fuel_gph"));
    private static final List<String> PORSCHE_INPUTS = Collections.unmodifiableList(
            Arrays.asList("maf", "lambda_r", "lambda_l", "speed"));
    private static final List<String> JEEP_INPUTS = Collections.unmodifiableList(
            Arrays.asList("inj_ms", "rpm", "speed"));
    private static final long NEVER = Long.MIN_VALUE / 2;          // halved so "now - NEVER" cannot overflow

    private double factor = 1;
    private Double gph, mph;                    // the last sample's (factor applied); null when missing
    private long lastMs = NEVER;
    private double driveGal, driveMiles, fillGal;
    private boolean fillStarted;
    private long driveStart;

    /** What the values worked out here are made from, on each car. */
    public static List<String> inputs(boolean porsche) { return porsche ? PORSCHE_INPUTS : JEEP_INPUTS; }

    /** True when the inputs have to be read: a fuel gauge is on screen, or a fill-up period is being counted. */
    public static boolean needsInputs(Collection<String> shownIds, boolean fillStarted) {
        if (fillStarted) return true;
        for (String id : GAUGES) if (shownIds.contains(id)) return true;
        return false;
    }

    /** A reading only if it was read within FRESH_MS, so a dropped link cannot keep fuel flowing. */
    public static Double fresh(LiveValues v, String id, long nowMs) {
        Long at = v.timeOf(id);
        if (at == null || nowMs - at > FRESH_MS) return null;
        return v.get(id, nowMs);
    }

    /** The Cayenne: fuel lb/h = air lb/h ÷ (14.7 × lambda); lambda is the banks' average, 1.0 when missing. */
    public void porsche(Double mafLbH, Double lambdaR, Double lambdaL, Double mph, long nowMs) {
        Double lb = null;
        if (mafLbH != null) {
            boolean r = plausible(lambdaR), l = plausible(lambdaL);
            double lambda = r && l ? (lambdaR + lambdaL) / 2 : r ? lambdaR : l ? lambdaL : 1.0;
            lb = Math.max(0, mafLbH) / (AIR_PER_FUEL * lambda);
        }
        sample(lb, mph, nowMs);
    }

    /** The Jeep: fuel lb/h = 8 injectors × injector rate × open share (ms ÷ 1000 × one squirt every 2 turns). */
    public void jeep(Double injMs, Double rpm, Double mph, long nowMs) {
        Double lb = null;
        if (injMs != null && rpm != null)
            lb = JEEP_INJECTORS * JEEP_INJECTOR_LB_H * (Math.max(0, injMs) / 1000) * (Math.max(0, rpm) / 120);
        sample(lb, mph, nowMs);
    }

    private static boolean plausible(Double lambda) {
        return lambda != null && lambda >= LAMBDA_LOW && lambda <= LAMBDA_HIGH;
    }

    private void sample(Double fuelLbH, Double mphNow, long nowMs) {
        Double g = fuelLbH == null ? null : fuelLbH / LB_PER_GAL * factor;
        long dt = nowMs - lastMs;
        if (dt > 0 && dt <= GAP_MS) {
            double hours = dt / 3_600_000.0;
            if (g != null && gph != null) {
                double used = (g + gph) / 2 * hours;
                driveGal += used;
                if (fillStarted) fillGal += used;
            }
            if (mphNow != null && mph != null) driveMiles += (mphNow + mph) / 2 * hours;
        }
        gph = g;
        mph = mphNow;
        lastMs = nowMs;
    }

    /** Fuel flow now, gal/h; null when it is not known. */
    public Double gph(long nowMs) { return nowMs - lastMs > STALE_MS ? null : gph; }

    /** Economy now: 0 when stopped, at most 99 (coasting uses next to nothing); null when not known. */
    public Double mpgNow(long nowMs) {
        Double g = gph(nowMs);
        if (g == null || mph == null) return null;
        if (mph < STOPPED_MPH) return 0.0;
        if (g <= 0) return MAX_MPG;
        return Math.min(MAX_MPG, mph / g);
    }

    public double driveGallons() { return driveGal; }

    /** Miles ÷ gallons this drive, once at least a hundredth of a gallon has been burnt. */
    public Double driveMpg() { return driveGal < MIN_GAL_FOR_AVERAGE ? null : driveMiles / driveGal; }

    /** The drive that started at this time (see LinkService): a different one starts the drive figures again. */
    public void drive(long startedMs) {
        if (startedMs == driveStart) return;
        driveStart = startedMs;
        driveGal = 0;
        driveMiles = 0;
    }

    public double factor() { return factor; }

    public void setFactor(double f) { factor = Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, f)); }

    /** Gallons since the fill-up was marked (factor applied). */
    public double sinceFill() { return fillGal; }

    public boolean fillStarted() { return fillStarted; }

    /** The tank was just filled: count from here. */
    public void startFill() {
        fillStarted = true;
        fillGal = 0;
    }

    /** What was saved: the factor, the estimate since the fill-up, and whether one was marked. */
    public void restore(double factor, double sinceFill, boolean fillStarted) {
        setFactor(factor);
        fillGal = Math.max(0, sinceFill);
        this.fillStarted = fillStarted;
    }

    /**
     * The new factor after a fill-up: the old one × what the pump put in ÷ what the dash counted. Null when
     * the dash counted under 2 gal (too little to go on) or nothing usable was pumped. One correction is
     * limited to between half and double, and the factor stays between 0.3 and 3.
     */
    public static Double calibrate(double factor, double estimatedGal, double pumpedGal) {
        if (!(estimatedGal >= MIN_CALIBRATE_GAL) || !(pumpedGal > 0) || Double.isInfinite(pumpedGal)) return null;
        double step = Math.max(MIN_STEP, Math.min(MAX_STEP, pumpedGal / estimatedGal));
        return Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, factor * step));
    }
}
