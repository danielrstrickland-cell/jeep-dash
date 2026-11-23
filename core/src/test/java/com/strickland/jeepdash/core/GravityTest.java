package com.strickland.jeepdash.core;

/** Taking gravity out of a plain accelerometer, for tablets without the linear-acceleration sensor. */
public class GravityTest {
    private static final double G = 9.80665;
    private static final long STEP = 20;              // 50 readings a second

    private static double size(double[] a) { return Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]) / G; }

    /** Holds one reading for ms; returns the last result. */
    private static double[] hold(Gravity g, double x, double y, double z, long from, long ms) {
        double[] out = null;
        for (long t = from; t <= from + ms; t += STEP) out = g.linear(x, y, z, t);
        return out;
    }

    public static void atRestItSettlesToNothingWhicheverWayRound() {
        T.near(0, size(hold(new Gravity(), 0, 0, G, 0, 10000)), 0.01, "flat");
        T.near(0, size(hold(new Gravity(), G * 0.7071, 0, G * 0.7071, 0, 10000)), 0.01, "tilted 45 degrees");
        T.near(0, size(hold(new Gravity(), 0, -G, 0, 0, 10000)), 0.01, "standing up");
        T.near(0, size(new Gravity().linear(3, 4, 8, 0)), 1e-9, "the first reading is taken as gravity");
    }

    public static void aSteadyPushReadsFullyAtFirst() {
        Gravity g = new Gravity();
        hold(g, 0, -G, 0, 0, 10000);
        double[] a = g.linear(0.3 * G, -G, 0, 10000 + STEP);
        T.near(0.3, size(a), 0.01, "0.3 g push");
        double[] later = hold(g, 0.3 * G, -G, 0, 10000 + 2 * STEP, 300);
        T.near(0.3, size(later), 0.06, "still about 0.3 g a third of a second on");
    }

    public static void turningSlowlySettlesBackToNothing() {
        Gravity g = new Gravity();
        long t = 0;
        hold(g, 0, 0, G, t, 5000);
        t = 5000;
        for (int i = 0; i <= 500; i++, t += STEP) {            // a quarter turn over 10 s
            double a = Math.PI / 2 * i / 500;
            g.linear(G * Math.sin(a), 0, G * Math.cos(a), t);
        }
        T.near(0, size(hold(g, G, 0, 0, t, 8000)), 0.01, "on its side now, at rest");
    }

    public static void aLongSilenceStartsAgain() {
        Gravity g = new Gravity();
        hold(g, 0, 0, G, 0, 5000);
        T.near(0, size(g.linear(G, 0, 0, 60000)), 1e-9, "sensor off for a minute: the new reading is gravity");
    }
}
