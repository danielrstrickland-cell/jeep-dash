package com.strickland.jeepdash.core;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Volume follows speed (experimental): the tablet's media volume goes up a few steps as road speed rises, to
 * make up for road and wind noise, and comes back down as it falls. Works in the stream's own steps (often 15
 * from silent to full). Nothing is added below 20 mph; from 20 mph one step, then one more every 30, 20 or
 * 12 mph (low, medium, high), up to 4, 5 or 6 steps and never past full.
 *
 * The owner's own level is the base. If the volume found is not the one set here last time, the owner changed
 * it by hand: that becomes the new base, less the steps added for speed (never below silent). Not thread-safe:
 * one caller (the link service's ticker) at a time.
 */
public final class SpeedVolume {

    public enum Strength {
        OFF(0, 0), LOW(30, 4), MEDIUM(20, 5), HIGH(12, 6);

        final double mphPerStep;
        final int maxExtra;

        Strength(double mphPerStep, int maxExtra) {
            this.mphPerStep = mphPerStep;
            this.maxExtra = maxExtra;
        }

        /** The saved name, OFF when nothing or something unknown was saved. */
        public static Strength of(String name) {
            if (name != null) for (Strength s : values()) if (s.name().equals(name)) return s;
            return OFF;
        }
    }

    static final double FROM_MPH = 20;
    /** Speed has to fall this far under a step's start before that step is taken off again. */
    static final double HYSTERESIS_MPH = 4;
    /** With no speed reading for this long the volume goes back to the base; until then it holds. */
    public static final long NO_SPEED_MS = 5000;
    private static final long NEVER = Long.MIN_VALUE / 2;          // halved so "now - NEVER" cannot overflow

    private final Strength strength;
    private int base;
    private int lastSet = -1;               // -1: nothing set since it started or was released
    private int level;                      // speed steps wanted now (before the stream's top is applied)
    private long lastSpeedMs = NEVER;

    public SpeedVolume(Strength strength) { this.strength = strength; }

    /** Speed steps for a road speed, without hysteresis. */
    public static int extra(Strength s, double mph) {
        if (s == Strength.OFF || mph < FROM_MPH) return 0;
        return Math.min(s.maxExtra, 1 + (int) Math.floor((mph - FROM_MPH) / s.mphPerStep));
    }

    /**
     * The step to set now.
     *
     * @param current the stream's step right now
     * @param max     the stream's top step
     * @param mph     road speed, or null when the reading is stale or missing (the volume holds, then after
     *                NO_SPEED_MS goes back to the base)
     */
    public int step(int current, int max, Double mph, long nowMs) {
        if (lastSet < 0) base = current;
        else if (current != lastSet) base = Math.max(0, current - (lastSet - base));      // changed by hand
        if (mph != null) {
            lastSpeedMs = nowMs;
            int up = extra(strength, mph);
            if (up > level) level = up;
            else level = Math.min(level, extra(strength, mph + HYSTERESIS_MPH));
        } else if (nowMs - lastSpeedMs >= NO_SPEED_MS) {
            level = 0;
        }
        lastSet = Math.max(0, Math.min(max, base + level));
        return lastSet;
    }

    /**
     * The owner's own level, to put back when the link drops or the feature is turned off; null when nothing
     * was set here since the last release. Starts afresh afterwards.
     */
    public Integer release(int current) {
        if (lastSet < 0) return null;
        if (current != lastSet) base = Math.max(0, current - (lastSet - base));
        lastSet = -1;
        level = 0;
        lastSpeedMs = NEVER;
        return base;
    }

    /** The owner's level as it stands. */
    public int base() { return base; }

    /** What to read from the car: the given list, plus road speed while the feature is on. Not changed in place. */
    public static Set<String> withSpeed(Set<String> ids, Strength s) {
        Set<String> out = new LinkedHashSet<>(ids);
        if (s != Strength.OFF) out.add("speed");
        return out;
    }
}
