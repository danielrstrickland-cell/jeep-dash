package com.strickland.jeepdash.core;

/**
 * The physics of one Skar EVL-15 D2 in a ported box, wired to 1 ohm (coils in parallel): the cone's moving mass
 * on its spring (the spider and surround) and damping, driven by the amp's voltage through the motor, with
 * back-EMF braking it; the air in the box acting as a second spring; and the air in the port as a mass that
 * resonates at the box tune. Past Xmax the suspension stiffens and the motor loses grip, and at the mechanical
 * limit the cone hits a hard stop: that is bottoming out.
 *
 * <p>Numbers are the driver's published Thiele-Small parameters (Fs 35 Hz, Qts 0.63, Mms 387 g, Cms 0.053 mm/N,
 * Bl 21 T·m and Re 3.8 ohm in series, Sd 779 cm², Xmax 23.5 mm) and Skar's recommended box (about 3.5 ft³ net,
 * 45 in² port). Positions are metres, positive out of the box.
 */
public final class SubPhysics {
    public static final double MMS = 0.387, CMS = 0.053e-3, RMS = 18.48, SD = 0.0779, XMAX = 0.0235;
    public static final double BL = 10.5, RE = 0.95;                 // dual 2-ohm coils in parallel: 1 ohm
    public static final double XMECH = XMAX * 1.45;                   // where the coil former would hit the back plate
    private static final double VB = 0.095, SP = 0.029, RHO_C2 = 1.42e5, PORT_Q = 7;
    /** Full-power drive: about 1000 W into 1 ohm, as a peak voltage. */
    public static final double FULL_VOLTS = 44.7;

    private double x, v, xp, vp, phase;
    private double tuneHz = 34, stiff = 1, damp = 1, mp, rp, k;

    public SubPhysics() { setBox(34); }

    /** Box tuning in hertz (the port's resonance). */
    public void setBox(double hz) {
        tuneHz = Math.max(15, Math.min(60, hz));
        k = RHO_C2 / VB;
        double w = 2 * Math.PI * tuneHz;
        mp = k * SP * SP / (w * w);
        rp = w * mp / PORT_Q;
    }

    /** Scales the suspension stiffness and mechanical damping (1 = the real driver). */
    public void setFeel(double stiffness, double damping) {
        stiff = Math.max(0.2, stiffness);
        damp = Math.max(0.2, damping);
    }

    /**
     * Runs the simulation for the given time with a sine drive at hz and peak volts. Returns the lowest and
     * highest cone positions reached, {min, max}, in metres.
     */
    public double[] run(double seconds, double hz, double volts) {
        int steps = Math.max(1, (int) Math.ceil(seconds * 8000));
        double dt = seconds / steps, lo = x, hi = x;
        for (int i = 0; i < steps; i++) {
            phase += 2 * Math.PI * hz * dt;
            if (phase > 2 * Math.PI) phase -= 2 * Math.PI;
            step(volts * Math.sin(phase), dt);
            lo = Math.min(lo, x);
            hi = Math.max(hi, x);
        }
        return new double[]{lo, hi};
    }

    /** One small time step with the amp putting out volts. */
    void step(double volts, double dt) {
        double xr = x / XMAX, xr4 = xr * xr * xr * xr;
        double bl = BL / (1 + 0.3 * xr4);                                // the coil leaves the gap past Xmax
        double spring = stiff / CMS * (1 + 0.6 * xr4) * x;               // and the suspension stiffens
        double stop = Math.abs(x) > XMECH ? 4e6 * (Math.abs(x) - XMECH) * Math.signum(x) + 900 * v : 0;
        double box = -k * (SD * x + SP * xp);                            // air pressure in the box
        double amps = (volts - bl * v) / RE;                             // back-EMF brakes the cone
        double force = bl * amps - damp * RMS * v - spring + box * SD - stop;
        v += force / MMS * dt;
        x += v * dt;
        vp += (box * SP - rp * vp) / mp * dt;
        xp += vp * dt;
    }

    /** Cone position, metres, positive out. */
    public double cone() { return x; }

    /** Air speed in the port, metres a second, positive out. */
    public double portSpeed() { return vp; }

    /** True past Xmax (the readout turns red). */
    public boolean overXmax() { return Math.abs(x) > XMAX; }

    public double tune() { return tuneHz; }
}
