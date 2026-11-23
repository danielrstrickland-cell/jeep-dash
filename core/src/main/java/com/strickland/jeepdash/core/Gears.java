package com.strickland.jeepdash.core;

/**
 * Which gear the transmission is in, worked out from engine speed against road speed: every gear has its
 * own engine rpm per mph. The gear ratios are the transmission's own; the axle and tyres are only a
 * starting guess, and the car's real top-gear figure is learned from steady highway driving. It cannot
 * tell where the shifter is, and reverse looks like a low gear.
 */
public final class Gears {
    /** Engine rpm per mph for a ratio of 1.0 with a 1.0 axle and a one-inch tyre. */
    private static final double RPM_PER_MPH = 336.13;
    static final double MIN_MPH = 3, MIN_RPM = 300;
    /** A gear fits from a little under its locked figure (reading error) up to this much converter slip. */
    static final double LOW = 0.93, HIGH = 1.22;
    static final long STALE_MS = 3000;
    /** Readings in a row before the shown gear changes, or goes blank. */
    static final int TO_CHANGE = 2, TO_BLANK = 3;
    /** Learning: highway speed, readings this steady, this many in a row. */
    static final double LEARN_MPH = 50, STEADY_MPH_PER_S = 1.0, STEADY_RATIO = 0.015, GAP_S = 2;
    static final int STEADY_RUN = 3;
    /** First guess: how far from the stock figure a top gear may be, and how many readings settle it. */
    static final double FIRST_WINDOW = 0.20;
    static final int FIRST_READINGS = 15;
    /** Afterwards: readings this close refine it; a steady run this long well below it replaces it. */
    static final double REFINE_WINDOW = 0.06, REFINE_RATE = 0.01, LOWER_FROM = 0.92, LOWER_TO = 0.60;
    static final int LOWER_READINGS = 30;

    private final double[] ratios;
    private final double stockTop;
    private double top;                         // engine rpm per mph in top gear with the converter locked
    private boolean learned;
    private double firstSum, lowSum;
    private int firstN, lowN, steady;
    private double lastMph = Double.NaN, lastRatio = Double.NaN;
    private int shown, pending, pendingN;       // 0 = no gear fits
    private long lastMs = Long.MIN_VALUE / 2;

    public Gears(double[] ratios, double stockTopRpmPerMph) {
        this.ratios = ratios.clone();
        this.stockTop = stockTopRpmPerMph;
        this.top = stockTopRpmPerMph;
    }

    /**
     * The Jeep's four-speed automatic (46RE) on the owner's 31x10.5R15 tyres. The axle is stock; 3.73 is
     * the starting figure and a 3.55 is close enough to read correctly until the real figure is learned.
     */
    public static Gears jeep() {
        return new Gears(new double[]{2.45, 1.45, 1.00, 0.69}, 0.69 * 3.73 * RPM_PER_MPH / 31);
    }

    /** The Cayenne's six-speed Tiptronic S, starting from a 3.70 final drive on 28.7-inch tyres. */
    public static Gears porsche() {
        return new Gears(new double[]{4.15, 2.37, 1.56, 1.16, 0.86, 0.69}, 0.69 * 3.70 * RPM_PER_MPH / 28.7);
    }

    public int count() { return ratios.length; }

    public double ratio(int gear) { return ratios[gear - 1]; }

    /** Engine rpm per mph in top gear: the stock guess until the car's own figure has been learned. */
    public synchronized double topRpmPerMph() { return top; }

    /** True once the top-gear figure is this car's own and not the stock guess. */
    public synchronized boolean learned() { return learned; }

    /** Puts back a figure learned on an earlier drive; one that makes no sense for this car is ignored. */
    public synchronized void restore(double learnedTop) {
        if (!(learnedTop > stockTop * 0.5 && learnedTop < stockTop * 2)) return;
        top = learnedTop;
        learned = true;
    }

    /** One pair of readings taken close together. */
    public synchronized void sample(double rpm, double mph, long nowMs) {
        double seconds = (nowMs - lastMs) / 1000.0;
        lastMs = nowMs;
        boolean moving = mph >= MIN_MPH && rpm >= MIN_RPM;
        double ratio = moving ? rpm / mph : Double.NaN;
        if (moving) learn(ratio, mph, seconds);
        else steady = 0;
        lastMph = mph;
        lastRatio = ratio;

        int fits = moving ? fit(ratio) : 0;
        if (fits == pending) pendingN++;
        else { pending = fits; pendingN = 1; }
        if (pendingN >= (pending == 0 ? TO_BLANK : TO_CHANGE)) shown = pending;
    }

    /** The gear number, or null when stopped, between gears or without recent readings. */
    public synchronized Double gear(long nowMs) {
        return shown == 0 || nowMs - lastMs > STALE_MS ? null : (double) shown;
    }

    private double perMph(int gear) { return top * ratios[gear - 1] / ratios[ratios.length - 1]; }

    private int fit(double ratio) {
        if (ratio >= perMph(1) * LOW) return 1;         // however far above: pulling away, converter slipping
        for (int g = 2; g <= ratios.length; g++) {
            double upper = Math.min(perMph(g) * HIGH, perMph(g - 1) * LOW);
            if (ratio >= perMph(g) * LOW && ratio < upper) return g;
        }
        return 0;
    }

    private void learn(double ratio, double mph, double seconds) {
        boolean calm = mph >= LEARN_MPH && seconds > 0 && seconds <= GAP_S
                && Math.abs(mph - lastMph) <= STEADY_MPH_PER_S * seconds
                && Math.abs(ratio - lastRatio) <= ratio * STEADY_RATIO;
        steady = calm ? steady + 1 : 0;
        if (steady < STEADY_RUN) return;
        if (!learned) {
            if (Math.abs(ratio / stockTop - 1) > FIRST_WINDOW) return;
            firstSum += ratio;
            if (++firstN >= FIRST_READINGS) {
                top = firstSum / firstN;
                learned = true;
            }
            return;
        }
        double off = ratio / top;
        if (Math.abs(off - 1) <= REFINE_WINDOW) {
            top += REFINE_RATE * (ratio - top);
            lowN = 0;
            lowSum = 0;
        } else if (off < LOWER_FROM && off > LOWER_TO) {
            // steady at highway speed with the engine turning slower than "top gear": that was not top gear
            lowSum += ratio;
            if (++lowN >= LOWER_READINGS) {
                top = lowSum / lowN;
                lowN = 0;
                lowSum = 0;
            }
        }
    }
}
