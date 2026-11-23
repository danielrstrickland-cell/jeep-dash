package com.strickland.jeepdash.core;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Fuel flow, economy and fuel used, worked out by the screen: neither engine computer reports fuel use. The
 * Cayenne's comes from its air flow and lambda, the Jeep's (no air-flow sensor) from injector time and engine
 * speed. Everything is multiplied by a per-car factor that the fill-up calibration corrects. Times are in
 * milliseconds on one clock, supplied by the caller. Fed by the link service's own thread and read by the
 * screens, so every method is synchronized.
 *
 * A figure that adds up over time is only shown when it is complete: while moving, the time with no fresh fuel
 * readings is counted, and past a tenth of the moving time (and more than 10 s) the drive's figures go blank
 * and the fill-up estimate cannot be calibrated from, rather than show a number that is too low.
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
    /** Incomplete: fuel readings missing for more than this share of the moving time, and for longer than MIN. */
    public static final double MAX_MISSING_SHARE = 0.10;
    public static final long MIN_MISSING_MS = 10_000;
    /** The last speed reading stands for this long when readings stop (a link lost at speed is still moving). */
    public static final long MOVING_MEMORY_MS = 60_000;
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
    private long driveMovingMs, driveMissingMs, fillMovingMs, fillMissingMs;
    private double lastKnownMph;
    private long lastKnownMphMs = NEVER;
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

    /**
     * What to read: the front screen's own list, plus the fuel inputs when the dash's gauges (whether or not the
     * dash is in front) or a fill-up being counted need them. The given set is not changed.
     */
    public static Set<String> withInputs(Set<String> requested, Collection<String> dashShown, boolean porsche,
                                         boolean fillStarted) {
        Set<String> out = new LinkedHashSet<>(requested);
        if (needsInputs(out, fillStarted) || needsInputs(dashShown, false)) out.addAll(inputs(porsche));
        return out;
    }

    /** True when fuel readings were missing for too much of the moving time to trust a total. */
    public static boolean incomplete(long movingMs, long missingMs) {
        return missingMs > MIN_MISSING_MS && missingMs > MAX_MISSING_SHARE * movingMs;
    }

    /** A reading only if it was read within FRESH_MS, so a dropped link cannot keep fuel flowing. */
    public static Double fresh(LiveValues v, String id, long nowMs) {
        Long at = v.timeOf(id);
        if (at == null || nowMs - at > FRESH_MS) return null;
        return v.get(id, nowMs);
    }

    /** The Cayenne: fuel lb/h = air lb/h ÷ (14.7 × lambda); lambda is the banks' average, 1.0 when missing. */
    public synchronized void porsche(Double mafLbH, Double lambdaR, Double lambdaL, Double mph, long nowMs) {
        Double lb = null;
        if (mafLbH != null) {
            boolean r = plausible(lambdaR), l = plausible(lambdaL);
            double lambda = r && l ? (lambdaR + lambdaL) / 2 : r ? lambdaR : l ? lambdaL : 1.0;
            lb = Math.max(0, mafLbH) / (AIR_PER_FUEL * lambda);
        }
        sample(lb, mph, nowMs);
    }

    /** The Jeep: fuel lb/h = 8 injectors × injector rate × open share (ms ÷ 1000 × one squirt every 2 turns). */
    public synchronized void jeep(Double injMs, Double rpm, Double mph, long nowMs) {
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
        if (lastMs != NEVER && dt > 0) {
            // moving, by the last speed reading if it is recent enough; a long silence counts only that long
            long since = lastMs - lastKnownMphMs;
            if (lastKnownMph >= STOPPED_MPH && since <= MOVING_MEMORY_MS) {
                long counted = Math.min(dt, MOVING_MEMORY_MS - since);
                boolean missing = dt > GAP_MS || g == null || gph == null;
                driveMovingMs += counted;
                if (missing) driveMissingMs += counted;
                if (fillStarted) {
                    fillMovingMs += counted;
                    if (missing) fillMissingMs += counted;
                }
            }
        }
        if (mphNow != null) {
            lastKnownMph = mphNow;
            lastKnownMphMs = nowMs;
        }
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
    public synchronized Double gph(long nowMs) { return nowMs - lastMs > STALE_MS ? null : gph; }

    /** Economy now: 0 when stopped, at most 99 (coasting uses next to nothing); null when not known. */
    public synchronized Double mpgNow(long nowMs) {
        Double g = gph(nowMs);
        if (g == null || mph == null) return null;
        if (mph < STOPPED_MPH) return 0.0;
        if (g <= 0) return MAX_MPG;
        return Math.min(MAX_MPG, mph / g);
    }

    /** Gallons this drive; null when fuel readings were missing for too much of it (see incomplete). */
    public synchronized Double driveGallons() { return driveComplete() ? driveGal : null; }

    /** Miles ÷ gallons this drive, once at least a hundredth of a gallon has been burnt; null when incomplete. */
    public synchronized Double driveMpg() {
        return !driveComplete() || driveGal < MIN_GAL_FOR_AVERAGE ? null : driveMiles / driveGal;
    }

    private boolean driveComplete() { return !incomplete(driveMovingMs, driveMissingMs); }

    public synchronized long driveMissingMs() { return driveMissingMs; }

    /** The share of this drive's moving time with no fresh fuel readings, 0..1. */
    public synchronized double driveMissingShare() {
        return driveMovingMs == 0 ? 0 : (double) driveMissingMs / driveMovingMs;
    }

    /** The drive that started at this time (see LinkService): a different one starts the drive figures again. */
    public synchronized void drive(long startedMs) {
        if (startedMs == driveStart) return;
        driveStart = startedMs;
        driveGal = 0;
        driveMiles = 0;
        driveMovingMs = 0;
        driveMissingMs = 0;
    }

    public synchronized double factor() { return factor; }

    public synchronized void setFactor(double f) { factor = Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, f)); }

    /** Gallons since the fill-up was marked (factor applied). */
    public synchronized double sinceFill() { return fillGal; }

    public synchronized boolean fillStarted() { return fillStarted; }

    public synchronized long fillMovingMs() { return fillMovingMs; }

    public synchronized long fillMissingMs() { return fillMissingMs; }

    /** False when fuel readings were missing for too much of the moving time since the fill-up. */
    public synchronized boolean fillComplete() { return !incomplete(fillMovingMs, fillMissingMs); }

    /** The share of the moving time since the fill-up with no fresh fuel readings, 0..1. */
    public synchronized double fillMissingShare() {
        return fillMovingMs == 0 ? 0 : (double) fillMissingMs / fillMovingMs;
    }

    /** The tank was just filled: count from here. */
    public synchronized void startFill() {
        fillStarted = true;
        fillGal = 0;
        fillMovingMs = 0;
        fillMissingMs = 0;
    }

    /** What was saved: the factor, the estimate since the fill-up, whether one was marked, and its moving times. */
    public synchronized void restore(double factor, double sinceFill, boolean fillStarted, long movingMs, long missingMs) {
        setFactor(factor);
        fillGal = Math.max(0, sinceFill);
        this.fillStarted = fillStarted;
        fillMovingMs = Math.max(0, movingMs);
        fillMissingMs = Math.max(0, Math.min(fillMovingMs, missingMs));
    }

    /** The new factor from this fill-up (see calibrate); null as well when the estimate is incomplete. */
    public synchronized Double calibrateFromFill(double pumpedGal) {
        if (!fillComplete()) return null;
        return calibrate(factor, fillGal, pumpedGal);
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
