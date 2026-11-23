package com.strickland.jeepdash.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the dash can show on the 2003 Cayenne Turbo: which group each value comes in and how it is worked
 * out. Groups and slots are from the capture of the GearWrench app on this car; every value read from the
 * car matched that app's own screen at idle, except the three marked "not on the vendor screen". Peak boost
 * is the boost reading held at its highest; the last five are worked out by the screen, not read.
 */
public final class PorscheCatalog {
    private PorscheCatalog() { }

    /** Works a reading out of its group; NaN when the group does not hold what was expected. */
    public interface Formula { double apply(KwpBlocks.Block b); }

    public static final class Value {
        public final Param param;
        public final int group;
        final Formula formula;

        Value(Param param, int group, Formula formula) {
            this.param = param;
            this.group = group;
            this.formula = formula;
        }

        public Double read(KwpBlocks.Block b) {
            double v = formula.apply(b);
            return Double.isNaN(v) ? null : v;
        }
    }

    private static final double PSI_PER_MBAR = 0.0145038, MPH_PER_KMH = 0.621371;
    private static final Map<String, Value> BY_ID = new LinkedHashMap<>();
    /** Worked out by the screen itself (road-speed changes, its GPS and motion sensor); never asked of the car. */
    private static final Map<String, Param> SCREEN = new LinkedHashMap<>();
    private static volatile double peakBoost;           // highest boost since the key came on, psi

    /** The key went off: the next drive's peak starts from zero. */
    public static void newDrive() { peakBoost = 0; }

    /** Manifold pressure minus outside air pressure, psi: below zero is vacuum, above is boost. */
    private static double boost(KwpBlocks.Block b) {
        return (b.v(3, KwpBlocks.MBAR) - b.v(4, KwpBlocks.MBAR)) * PSI_PER_MBAR;
    }

    private static double f(double c) { return c * 9 / 5 + 32; }

    private static void add(String id, String name, String unit, double min, double max, int weight, int group, Formula formula) {
        BY_ID.put(id, new Value(new Param(id, name, unit, null, null, null, null, min, max, weight), group, formula));
    }

    static {
        add("rpm", "Engine RPM", "rpm", 0, 7000, 4, 0x0a, b -> b.v(0, KwpBlocks.RPM));
        add("speed", "Speed", "mph", 0, 180, 2, 0x0a, b -> b.v(2, KwpBlocks.KMH) * MPH_PER_KMH);
        // not on the vendor screen
        add("boost", "Boost", "psi", -15, 20, 4, 0x16, PorscheCatalog::boost);
        // the highest boost since the key came on; vacuum does not count, so it starts at zero
        add("boost_peak", "Peak boost", "psi", 0, 20, 4, 0x16, b -> {
            double now = boost(b);
            if (Double.isNaN(now)) return now;
            if (now > peakBoost) peakBoost = now;
            return peakBoost;
        });
        add("coolant", "Coolant", "°F", 100, 260, 1, 0x0b, b -> f(b.v(1, KwpBlocks.TEMP_C)));
        add("oil_temp", "Oil temp", "°F", 100, 300, 1, 0x16, b -> f(b.v(6, KwpBlocks.TEMP_DIFF_C)));
        add("iat", "Intake air", "°F", 0, 200, 1, 0x0b, b -> f(b.v(2, KwpBlocks.TEMP_C)));
        add("cam_r", "Camshaft right", "deg", -15, 15, 1, 0x14, b -> b.v(1, KwpBlocks.DEGREES));
        add("cam_l", "Camshaft left", "deg", -15, 15, 1, 0x14, b -> b.v(2, KwpBlocks.DEGREES));
        add("boost_set", "Boost target", "psi", 0, 30, 1, 0x3b, b -> b.v(3, KwpBlocks.MBAR) * PSI_PER_MBAR);
        // the pressure sensor ahead of the throttle, absolute like the target (not on the vendor screen)
        add("boost_act", "Boost sensor", "psi", 0, 30, 1, 0x3b, b -> b.v(6, KwpBlocks.MBAR) * PSI_PER_MBAR);
        // 13.6 to 13.8 V with the engine running in the capture (not on the vendor screen)
        add("batt_v", "Battery", "V", 10, 16, 1, 0x0a, b -> b.v(1, KwpBlocks.VOLT));

        // The timer and pull come from how the car's own road speed changes; the other three need the
        // screen's GPS or motion sensor and stay blank on a radio that has none.
        screen("zero_sixty", "0-60 time", "s", 0, 20);
        screen("g_accel", "Pull / braking", "g", -1, 1);
        screen("gps_mph", "GPS speed", "mph", 0, 180);
        screen("g_force", "G-force", "g", 0, 1.2);
        screen("speedo_err", "Speedo vs GPS", "%", -15, 15);
        // worked out from engine speed against road speed (see Gears)
        screen("gear", "Gear", "of 6", 0, 6);
    }

    private static void screen(String id, String name, String unit, double min, double max) {
        SCREEN.put(id, Param.tablet(id, name, unit, min, max));
    }

    public static List<Param> all() {
        List<Param> out = new ArrayList<>();
        for (Value v : BY_ID.values()) out.add(v.param);
        out.addAll(SCREEN.values());
        return Collections.unmodifiableList(out);
    }

    public static Param byId(String id) {
        Value v = BY_ID.get(id);
        return v != null ? v.param : SCREEN.get(id);
    }

    public static Value value(String id) { return BY_ID.get(id); }
}
