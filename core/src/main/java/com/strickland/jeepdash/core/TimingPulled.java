package com.strickland.jeepdash.core;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * "Timing pulled" (Jeep only): how far the engine computer's actual timing sits below its own main spark table
 * (see SparkTable) for the engine speed and manifold pressure right now. Knock and hot intake air are what
 * usually pull it. Worked out by the link service from the Jeep's readings; times are in milliseconds on one
 * clock, supplied by the caller.
 *
 * It only reads where the table is the timing the engine computer uses: engine warm, 1100 to 4800 rpm, and not
 * at closed throttle or while the engine speed drops fast (a lift-off or an upshift), where it uses other spark
 * logic. Anywhere else it reads blank. Tuned on the logged drives of 2026-11-24 so that ordinary driving reads
 * 0 most of the time (docs/superpowers/specs/2026-11-20-jeep-dash-design.md).
 */
public final class TimingPulled {
    public static final String GAUGE = "spark_pulled";
    public static final List<String> INPUTS = Collections.unmodifiableList(Arrays.asList("rpm", "map_kpa", "spark", "coolant"));

    static final double WARM_F = 180;
    /** Below this it is idle or closed-throttle coasting, where the engine computer uses its idle spark. */
    static final double MIN_RPM = 1100;
    static final double MAX_RPM = 4800;                 // the table's last row
    /** Manifold pressure this low with the engine above idle means the throttle is shut. */
    static final double CLOSED_KPA = 25;
    /** Engine speed falling faster than this (rpm a second) is a lift-off or an upshift. */
    static final double FALL_RPM_PER_S = 250;
    /** Blank for this long after a lift-off, an upshift or a shut throttle. */
    static final long QUIET_MS = 2000;
    /** And for this long after it gets warm and into the speed band (pulling away from a stop, for one). */
    static final long SETTLE_MS = 2000;
    /** The smoothing: the middle one of the last three readings, none older than this. */
    static final int SMOOTH = 3;
    static final long WINDOW_MS = 3500;
    /** A timing reading is only compared with engine speed and pressure read this close to it. */
    static final long TOGETHER_MS = 1500;
    /** Two engine-speed readings further apart than this say nothing about how fast it is falling. */
    static final long RATE_GAP_MS = 3000;
    private static final long NEVER = Long.MIN_VALUE / 2;          // halved so "t - NEVER" cannot overflow

    private long lastSparkMs = NEVER, lastRpmMs = NEVER, quietUntil = NEVER, openSince = NEVER;
    private double lastRpm;
    private final ArrayDeque<double[]> recent = new ArrayDeque<>();      // {time, degrees pulled}
    private Double shown;

    /** True when the gauge is on screen, so its inputs have to be read. */
    public static boolean needsInputs(Collection<String> shownIds) { return shownIds.contains(GAUGE); }

    /**
     * What to read: the given list, plus engine speed, manifold pressure, timing and coolant when the gauge is
     * on the screen in front or on the dash's layout (it is worked out behind other screens too). Never on the
     * Porsche. The given set is not changed.
     */
    public static Set<String> withInputs(Set<String> ids, Collection<String> dashShown, boolean porsche) {
        Set<String> out = new LinkedHashSet<>(ids);
        if (!porsche && (needsInputs(ids) || needsInputs(dashShown))) out.addAll(INPUTS);
        return out;
    }

    /**
     * Takes the latest readings and gives what the gauge shows: degrees pulled (0 or more), or null for blank.
     * Each new timing reading is one step; between them the last answer stands.
     */
    public synchronized Double update(LiveValues v, long nowMs) {
        Double rpm = v.get("rpm", nowMs), kpa = v.get("map_kpa", nowMs), spark = v.get("spark", nowMs),
                coolant = v.get("coolant", nowMs);
        Long rpmMs = v.timeOf("rpm"), kpaMs = v.timeOf("map_kpa"), sparkMs = v.timeOf("spark");
        if (rpm == null || kpa == null || spark == null || coolant == null || rpmMs == null || kpaMs == null || sparkMs == null) {
            reset();
            return null;
        }
        if (rpmMs > lastRpmMs) {
            if (rpmMs - lastRpmMs <= RATE_GAP_MS && (rpm - lastRpm) / ((rpmMs - lastRpmMs) / 1000.0) < -FALL_RPM_PER_S)
                quietUntil = Math.max(quietUntil, rpmMs + QUIET_MS);
            lastRpm = rpm;
            lastRpmMs = rpmMs;
        }
        if (sparkMs == lastSparkMs) return shown;
        lastSparkMs = sparkMs;
        if (Math.abs(rpmMs - sparkMs) > TOGETHER_MS || Math.abs(kpaMs - sparkMs) > TOGETHER_MS) return shown;

        long t = sparkMs;
        if (kpa < CLOSED_KPA) quietUntil = Math.max(quietUntil, t + QUIET_MS);
        boolean band = coolant >= WARM_F && rpm >= MIN_RPM && rpm <= MAX_RPM;
        if (!band) openSince = NEVER;
        else if (openSince == NEVER) openSince = t;
        if (!band || t <= quietUntil || t - openSince < SETTLE_MS) {
            recent.clear();
            shown = null;
            return null;
        }
        recent.addLast(new double[]{t, Math.max(0, SparkTable.advance(rpm, kpa) - spark)});
        while (recent.size() > SMOOTH) recent.removeFirst();
        for (Iterator<double[]> it = recent.iterator(); it.hasNext(); ) if (t - it.next()[0] > WINDOW_MS) it.remove();
        shown = middle();
        return shown;
    }

    /** The middle of the readings kept (the average of the two middle ones when there are two). */
    private double middle() {
        double[] d = new double[recent.size()];
        int i = 0;
        for (double[] e : recent) d[i++] = e[1];
        Arrays.sort(d);
        return d.length % 2 == 1 ? d[d.length / 2] : (d[d.length / 2 - 1] + d[d.length / 2]) / 2;
    }

    /** A reading went missing (or the link dropped): start again from nothing. */
    private void reset() {
        recent.clear();
        shown = null;
        lastSparkMs = lastRpmMs = openSince = NEVER;
    }
}
