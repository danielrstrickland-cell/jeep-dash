package com.strickland.jeepdash.core;

public final class SubPhysicsTest {
    private static double swing(double hz, double volts) {
        SubPhysics s = new SubPhysics();
        s.run(1.0, hz, volts);                                       // settle
        double[] r = s.run(0.5, hz, volts);
        return (r[1] - r[0]) / 2;
    }

    public static void portUnloadsTheConeAtTheTune() {
        double atTune = swing(34, 10), above = swing(60, 10), below = swing(18, 10);
        T.ok(atTune < above, "less excursion at the box tune than at 60 Hz: " + atTune + " vs " + above);
        T.ok(atTune < below, "far more below the tune, where the port unloads: " + below);
    }

    public static void fullPowerReachesAboutXmax() {
        double low = swing(45, SubPhysics.FULL_VOLTS);
        T.ok(low > SubPhysics.XMAX * 0.3 && low < SubPhysics.XMECH * 1.1,
                "about a kilowatt moves the cone a good part of Xmax and no further than the stop: " + low * 1000 + " mm");
    }

    public static void bottomsOutAtTheStop() {
        double huge = swing(20, SubPhysics.FULL_VOLTS * 3);
        T.ok(huge < SubPhysics.XMECH * 1.15, "the hard stop holds even when overdriven: " + huge * 1000 + " mm");
        SubPhysics s = new SubPhysics();
        s.run(0.5, 20, SubPhysics.FULL_VOLTS * 3);
        boolean over = false;
        for (int i = 0; i < 200 && !over; i++) {
            s.run(0.002, 20, SubPhysics.FULL_VOLTS * 3);
            over = s.overXmax();
        }
        T.ok(over, "and the readout sees it pass Xmax");
    }

    public static void settlesWhenTheMusicStops() {
        SubPhysics s = new SubPhysics();
        s.run(0.5, 40, 30);
        s.run(0.4, 40, 0);
        T.ok(Math.abs(s.cone()) < 0.0005, "rings down and settles within 0.4 s: " + s.cone());
    }

    public static void stifferMovesLess() {
        SubPhysics a = new SubPhysics(), b = new SubPhysics();
        b.setFeel(2, 1);
        a.run(1, 25, 10);
        b.run(1, 25, 10);
        double[] ra = a.run(0.5, 25, 10), rb = b.run(0.5, 25, 10);
        T.ok(rb[1] - rb[0] < ra[1] - ra[0], "a stiffer suspension swings less");
    }
}
