package com.strickland.jeepdash.core;

/**
 * Body roll and pitch from lateral and fore-aft G, through a spring and damper so the body overshoots a little
 * and settles. Lateral G is positive toward the left (a left turn); the body then leans right, which is a
 * positive roll. Fore-aft G is positive speeding up; the nose then rises, which is a positive pitch.
 * Strength runs from 0 (about what a lifted ZJ on soft springs does) to 1 (exaggerated, for show).
 */
public final class BodyMotion {
    /** Degrees per g at strength 0 and 1. */
    public static final double ROLL_REAL = 6, ROLL_SHOW = 20, PITCH_REAL = 3, PITCH_SHOW = 11;
    /** Never asked to lean further than this (degrees). */
    public static final double ROLL_LIMIT = 14, PITCH_LIMIT = 9;
    static final double HZ = 1.3, ZETA = 0.5;

    private final SpringDamper roll = new SpringDamper(HZ, ZETA), pitch = new SpringDamper(HZ * 1.15, ZETA);
    private double strength;

    public BodyMotion(double strength) { setStrength(strength); }

    public void setStrength(double s) { strength = Math.max(0, Math.min(1, s)); }

    public double strength() { return strength; }

    public void step(double latG, double lonG, double dtSec) {
        roll.setTarget(clamp((ROLL_REAL + (ROLL_SHOW - ROLL_REAL) * strength) * latG, ROLL_LIMIT));
        pitch.setTarget(clamp((PITCH_REAL + (PITCH_SHOW - PITCH_REAL) * strength) * lonG, PITCH_LIMIT));
        roll.step(dtSec);
        pitch.step(dtSec);
    }

    /** Degrees, positive leaning right. */
    public double rollDeg() { return roll.value(); }

    /** Degrees, positive nose up. */
    public double pitchDeg() { return pitch.value(); }

    public boolean settled() { return roll.settled(0.03, 0.1) && pitch.settled(0.03, 0.1); }

    static double clamp(double v, double limit) { return Math.max(-limit, Math.min(limit, v)); }
}
