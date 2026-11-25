package com.strickland.jeepdash.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Compares the engine computer's road speed with GPS speed during a cruise log, to show how far the speedometer
 * is off. A pair counts only when the GPS fix is good (accuracy 10 m or better), both speeds are at least 25 mph
 * and the speed has been steady (neither moved by 1 mph or more over the last 3 s). The answer is the median of
 * gps / pcm, so a few odd samples do not move it.
 *
 * It also works out the constant the engine computer would need for that ratio (stock 28800000, at flash
 * 0x045A). This only reports a number: nothing here writes it anywhere.
 */
public final class SpeedCalibration {
    public static final double MAX_ACCURACY_M = 10, MIN_MPH = 25, STEADY_MPH = 1;
    public static final long WINDOW_MS = 3000, MIN_SPAN_MS = 2500;
    public static final int MIN_SAMPLES = 10;
    /** The stock speed constant in the engine computer (flash address 0x045A). */
    public static final long STOCK_CONSTANT = 28_800_000L;

    private static final class Pair {
        final double pcm, gps;
        final long t;
        Pair(double pcm, double gps, long t) { this.pcm = pcm; this.gps = gps; this.t = t; }
    }

    private final ArrayDeque<Pair> window = new ArrayDeque<>();
    private final List<Double> ratios = new ArrayList<>();

    /** One pair of readings (the engine computer's raw road speed and GPS speed, both mph) taken at timeMs. */
    public void add(double pcmMph, double gpsMph, double accuracyM, long timeMs) {
        if (Double.isNaN(pcmMph) || Double.isNaN(gpsMph)) return;
        while (!window.isEmpty() && timeMs - window.peekFirst().t > WINDOW_MS) window.removeFirst();
        window.addLast(new Pair(pcmMph, gpsMph, timeMs));
        if (!(accuracyM <= MAX_ACCURACY_M) || accuracyM < 0) return;      // NaN fails too
        if (pcmMph < MIN_MPH || gpsMph < MIN_MPH) return;
        if (timeMs - window.peekFirst().t < MIN_SPAN_MS) return;
        double pMin = Double.MAX_VALUE, pMax = -Double.MAX_VALUE, gMin = Double.MAX_VALUE, gMax = -Double.MAX_VALUE;
        for (Pair p : window) {
            pMin = Math.min(pMin, p.pcm);
            pMax = Math.max(pMax, p.pcm);
            gMin = Math.min(gMin, p.gps);
            gMax = Math.max(gMax, p.gps);
        }
        if (pMax - pMin >= STEADY_MPH || gMax - gMin >= STEADY_MPH) return;
        ratios.add(gpsMph / pcmMph);
    }

    /** How many pairs have passed the filters. */
    public int samples() { return ratios.size(); }

    /** The answer, or null until there are at least MIN_SAMPLES good pairs. */
    public Result result() {
        int n = ratios.size();
        if (n < MIN_SAMPLES) return null;
        List<Double> sorted = new ArrayList<>(ratios);
        Collections.sort(sorted);
        double median = n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
        double mean = 0;
        for (double r : sorted) mean += r;
        mean /= n;
        double var = 0;
        for (double r : sorted) var += (r - mean) * (r - mean);
        return new Result(median, n, Math.sqrt(var / n));
    }

    public static final class Result {
        /** Median of gps / pcm: above 1 means the car's speed reads low. */
        public final double ratio;
        public final int samples;
        /** Standard deviation of the ratios. */
        public final double spread;

        Result(double ratio, int samples, double spread) {
            this.ratio = ratio;
            this.samples = samples;
            this.spread = spread;
        }

        /** Percent the car's speed is off GPS: positive when it reads low. */
        public double pctLow() { return (ratio - 1) * 100; }

        /** The constant that would bring the reading to GPS: 28800000 x ratio. Shown only, never written. */
        public long suggestedConstant() { return Math.round(STOCK_CONSTANT * ratio); }

        public String constantHex() { return String.format(Locale.US, "0x%08X", suggestedConstant()); }

        /** "Speedometer reads 3.6% low · 57 samples · suggested constant 0x01BF8E7B". */
        public String summary() {
            double pct = Math.round(pctLow() * 10) / 10.0;
            String head = pct == 0 ? "Speedometer matches GPS"
                    : String.format(Locale.US, "Speedometer reads %.1f%% %s", Math.abs(pct), pct > 0 ? "low" : "high");
            return head + " · " + samples + " samples · suggested constant " + constantHex();
        }
    }
}
