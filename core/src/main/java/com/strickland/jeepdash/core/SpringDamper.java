package com.strickland.jeepdash.core;

/**
 * A spring and damper pulling a value toward a target: x'' = w²(target - x) - 2ζw x'. With ζ below 1 it
 * overshoots a little and settles, like a body on its springs. Stepped in small slices so a slow frame stays
 * stable. Used for the 3D truck's roll and pitch, its tap-to-spin and its hood.
 */
public final class SpringDamper {
    private static final double SLICE_S = 0.004, LONGEST_STEP_S = 1.0;
    private final double w, zeta;
    private double x, v, target;

    /** @param hz natural frequency; @param zeta damping ratio (0.5 overshoots about 16%) */
    public SpringDamper(double hz, double zeta) {
        this.w = 2 * Math.PI * hz;
        this.zeta = zeta;
    }

    public void setTarget(double t) { target = t; }

    public double target() { return target; }

    public double value() { return x; }

    public double velocity() { return v; }

    /** Jumps straight to a value, at rest. */
    public void reset(double value) {
        x = value;
        v = 0;
        target = value;
    }

    /** Moves value and target together (keeps a spin angle small without a visible jump). */
    public void shift(double d) {
        x += d;
        target += d;
    }

    public void step(double dtSec) {
        if (!(dtSec > 0)) return;
        dtSec = Math.min(dtSec, LONGEST_STEP_S);
        int n = (int) Math.ceil(dtSec / SLICE_S);
        double h = dtSec / n;
        for (int i = 0; i < n; i++) {
            double a = w * w * (target - x) - 2 * zeta * w * v;
            v += a * h;
            x += v * h;
        }
    }

    /** Close enough to the target and slow enough to stop animating. */
    public boolean settled(double tolX, double tolV) { return Math.abs(target - x) < tolX && Math.abs(v) < tolV; }
}
