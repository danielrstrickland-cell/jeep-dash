package com.strickland.jeepdash.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the dash can show on the 2003 Cayenne Turbo: which group each value comes in and how it is worked
 * out. Groups and slots are from the two captures of the GearWrench app on this car; every value read from
 * the car matched that app's own screen at idle, except the three marked "not on the vendor screen". Peak boost
 * is the boost reading held at its highest. Boost and peak boost each come four ways (BOOST_FROM), picked in
 * the gauge's settings; the last ten are worked out by the screen, not read.
 */
public final class PorscheCatalog {
    private PorscheCatalog() { }

    /** Works a reading out of its group; NaN when the group does not hold what was expected. */
    public interface Formula { double apply(KwpBlocks.Block b); }

    public static final class Value {
        public final Param param;
        public final int group;
        /** Other groups the formula leans on (read from their latest answer), and how often to read them. */
        public final int[] also;
        public final int alsoWeight;
        final Formula formula;

        Value(Param param, int group, Formula formula) { this(param, group, formula, new int[0], 0); }

        Value(Param param, int group, Formula formula, int[] also, int alsoWeight) {
            this.param = param;
            this.group = group;
            this.formula = formula;
            this.also = also;
            this.alsoWeight = alsoWeight;
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
    /** Highest boost since the key came on, psi, one per way of working it out (see BOOST_FROM). */
    private static final Map<String, Double> PEAKS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The latest of the readings the boost choices share across groups: manifold and outside air pressure
     * (group 16), the boost sensor (3b), engine speed (0a) and intake air (0b). NaN until read.
     */
    private static volatile double mapMbar = Double.NaN, baroMbar = Double.NaN, sensorMbar = Double.NaN,
            rpmNow = Double.NaN, iatC = Double.NaN;

    /** Outside air pressure until the car has given one: standard sea level, mbar. */
    static final double STANDARD_MBAR = 1013.25;
    /** The experimental air-flow choice: the 4.5 litre V8, and the share of it taken in each turn (a guess). */
    static final double LITRES = 4.511, VOLUMETRIC = 0.85;

    /** The key went off: the next drive's peaks start from zero, and nothing carries over. */
    public static void newDrive() {
        PEAKS.clear();
        mapMbar = baroMbar = sensorMbar = rpmNow = iatC = Double.NaN;
    }

    /** Every group's answer passes through here first, so a boost choice can use another group's latest. */
    public static void seen(int group, KwpBlocks.Block b) {
        if (b == null) return;
        switch (group) {
            case 0x16: keep(b.v(3, KwpBlocks.MBAR), 0); keep(b.v(4, KwpBlocks.MBAR), 1); break;
            case 0x3b: keep(b.v(6, KwpBlocks.MBAR), 2); break;
            case 0x0a: keep(b.v(0, KwpBlocks.RPM), 3); break;
            case 0x0b: keep(b.v(2, KwpBlocks.TEMP_C), 4); break;
            default: break;
        }
    }

    private static void keep(double v, int which) {
        if (Double.isNaN(v)) return;
        switch (which) {
            case 0: mapMbar = v; break;
            case 1: baroMbar = v; break;
            case 2: sensorMbar = v; break;
            case 3: rpmNow = v; break;
            default: iatC = v; break;
        }
    }

    private static double baro() { return Double.isNaN(baroMbar) ? STANDARD_MBAR : baroMbar; }

    /** Manifold pressure minus outside air pressure, psi: below zero is vacuum, above is boost. */
    private static double boost(KwpBlocks.Block b) {
        return (b.v(3, KwpBlocks.MBAR) - b.v(4, KwpBlocks.MBAR)) * PSI_PER_MBAR;
    }

    /** The boost sensor ahead of the throttle minus outside air pressure, psi (group 3b). */
    private static double sensorBoost(KwpBlocks.Block b) {
        return (b.v(6, KwpBlocks.MBAR) - baro()) * PSI_PER_MBAR;
    }

    /** Vacuum from the manifold; once it is in boost, the average of the manifold and the boost sensor (group 16). */
    static double combined(double manifoldPsi, double sensorPsi) {
        if (Double.isNaN(manifoldPsi)) return sensorPsi;
        if (Double.isNaN(sensorPsi) || manifoldPsi < 0) return manifoldPsi;
        return (manifoldPsi + sensorPsi) / 2;
    }

    private static double combinedBoost(KwpBlocks.Block b) {
        double s = Double.isNaN(sensorMbar) ? Double.NaN : (sensorMbar - b.v(4, KwpBlocks.MBAR)) * PSI_PER_MBAR;
        return combined(boost(b), s);
    }

    /**
     * Experimental: the pressure the engine must have in the manifold to take in the measured air flow at this
     * engine speed and intake air temperature (ideal gas, 4.5 litres, the engine filling 85 % each turn), minus
     * outside air pressure, psi. NaN below 400 rpm. A third view of boost that uses neither pressure sensor.
     */
    static double fromAirFlow(double lbPerHour, double rpm, double intakeC, double outsideMbar) {
        if (Double.isNaN(lbPerHour) || Double.isNaN(rpm) || rpm < 400 || Double.isNaN(outsideMbar)) return Double.NaN;
        double kgPerS = lbPerHour * 0.45359237 / 3600, kelvin = (Double.isNaN(intakeC) ? 30 : intakeC) + 273.15;
        double m3PerS = VOLUMETRIC * LITRES / 1000 * rpm / 120;
        double pascal = kgPerS * 287.05 * kelvin / m3PerS;
        return (pascal / 100 - outsideMbar) * PSI_PER_MBAR;
    }

    private static double airFlowBoost(KwpBlocks.Block b) {
        return fromAirFlow(b.v(1, KwpBlocks.AIR_LB_H), rpmNow, iatC, b.v(4, KwpBlocks.MBAR));
    }

    /** The highest of a boost choice since the key came on; vacuum does not count, so it starts at zero. */
    private static Formula peak(String id, Formula boost) {
        return b -> {
            double now = boost.apply(b);
            if (Double.isNaN(now)) return now;
            double held = PEAKS.merge(id, Math.max(0, now), Math::max);
            return held;
        };
    }

    /**
     * The ways a boost gauge can work its reading out, picked in the gauge's settings: a gauge's value is
     * one of these ids. Boost and Peak boost each come four ways; the order is the drop-down's.
     */
    public static final String[] BOOST_FROM = {"Manifold", "Boost sensor", "Combined", "Experimental: from air flow"};
    private static final String[][] BOOST_FAMILIES = {
            {"boost", "boost_s", "boost_c", "boost_x"},
            {"boost_peak", "boost_peak_s", "boost_peak_c", "boost_peak_x"},
    };

    /** The ids a gauge showing id can switch between (in BOOST_FROM order), or null when it has no such choice. */
    public static String[] boostFamily(String id) {
        for (String[] f : BOOST_FAMILIES) for (String x : f) if (x.equals(id)) return f.clone();
        return null;
    }

    private static double f(double c) { return c * 9 / 5 + 32; }

    private static void add(String id, String name, String unit, double min, double max, int weight, int group, Formula formula) {
        add(id, name, unit, min, max, weight, group, formula, new int[0], 0);
    }

    private static void add(String id, String name, String unit, double min, double max, int weight, int group,
                            Formula formula, int[] also, int alsoWeight) {
        BY_ID.put(id, new Value(new Param(id, name, unit, null, null, null, null, min, max, weight), group, formula,
                also, alsoWeight));
    }

    static {
        add("rpm", "Engine RPM", "rpm", 0, 7000, 4, 0x0a, b -> b.v(0, KwpBlocks.RPM));
        add("speed", "Speed", "mph", 0, 180, 2, 0x0a, b -> b.v(2, KwpBlocks.KMH) * MPH_PER_KMH);
        // not on the vendor screen
        add("boost", "Boost", "psi", -15, 20, 4, 0x16, PorscheCatalog::boost);
        // the same gauge worked out three more ways, picked in its settings; outside air pressure comes from group 16
        add("boost_s", "Boost (sensor)", "psi", -15, 20, 4, 0x3b, PorscheCatalog::sensorBoost, new int[]{0x16}, 1);
        add("boost_c", "Boost (combined)", "psi", -15, 20, 4, 0x16, PorscheCatalog::combinedBoost, new int[]{0x3b}, 4);
        add("boost_x", "Boost (air flow, experimental)", "psi", -15, 20, 4, 0x16, PorscheCatalog::airFlowBoost,
                new int[]{0x0a, 0x0b}, 2);
        // the highest boost since the key came on, each way; vacuum does not count, so it starts at zero
        add("boost_peak", "Peak boost", "psi", 0, 20, 4, 0x16, peak("boost_peak", PorscheCatalog::boost));
        add("boost_peak_s", "Peak boost (sensor)", "psi", 0, 20, 4, 0x3b,
                peak("boost_peak_s", PorscheCatalog::sensorBoost), new int[]{0x16}, 1);
        add("boost_peak_c", "Peak boost (combined)", "psi", 0, 20, 4, 0x16,
                peak("boost_peak_c", PorscheCatalog::combinedBoost), new int[]{0x3b}, 4);
        add("boost_peak_x", "Peak boost (air flow, experimental)", "psi", 0, 20, 4, 0x16,
                peak("boost_peak_x", PorscheCatalog::airFlowBoost), new int[]{0x0a, 0x0b}, 2);
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

        // From the warm-idle capture of 2026-11-22: each matched the vendor screen's figure at that moment.
        add("throttle", "Throttle", "%", 0, 100, 2, 0x0b, b -> b.v(0, KwpBlocks.SHARE));
        add("pedal", "Pedal", "%", 0, 100, 2, 0x0e, b -> b.v(1, KwpBlocks.SHARE));
        add("load", "Engine load", "%", 0, 200, 2, 0x0a, b -> b.v(7, KwpBlocks.SHARE));
        add("torque", "Engine torque", "%", 0, 100, 1, 0x0b, b -> b.v(5, KwpBlocks.SHARE));
        add("timing", "Ignition timing", "deg", -20, 60, 1, 0x0b, b -> b.v(3, KwpBlocks.ADVANCE));
        add("maf", "Air flow", "lb/h", 0, 3000, 1, 0x16, b -> b.v(1, KwpBlocks.AIR_LB_H));
        add("inj_ms", "Injection time", "ms", 0, 25, 1, 0x16, b -> b.v(0, KwpBlocks.MILLISECONDS));
        add("lambda_r", "Lambda right", "ratio", 0.7, 1.3, 1, 0x0f, b -> b.v(0, KwpBlocks.LAMBDA));
        add("lambda_l", "Lambda left", "ratio", 0.7, 1.3, 1, 0x12, b -> b.v(0, KwpBlocks.LAMBDA));
        add("trim_1", "Fuel trim bank 1", "%", -25, 25, 1, 0x0f, b -> b.v(3, KwpBlocks.TRIM));
        add("trim_2", "Fuel trim bank 2", "%", -25, 25, 1, 0x12, b -> b.v(3, KwpBlocks.TRIM));
        // the computer's own worked-out exhaust temperatures either side of the catalytic converter
        add("egt_pre", "Exhaust before cat", "°F", 200, 1900, 1, 0x0a, b -> f(b.v(6, KwpBlocks.TEMP_C)));
        add("egt_post", "Exhaust after cat", "°F", 200, 1900, 1, 0x0a, b -> f(b.v(4, KwpBlocks.TEMP_C)));
        add("outside_temp", "Outside temp", "°F", -20, 130, 1, 0x17, b -> f(b.v(4, KwpBlocks.TEMP_C)));
        add("brake_vac", "Brake booster", "psi", 0, 15, 1, 0x34, b -> b.v(2, KwpBlocks.MBAR) * PSI_PER_MBAR);
        // the gearbox position as the engine computer numbers it: 0 in park (as on the vendor screen); 1 and
        // 7 were seen while the lever was moved, so 7 is probably reverse. Not yet seen while driving.
        add("gear_ecu", "Selected gear", "0-7", 0, 7, 1, 0x14, b -> b.v(0, KwpBlocks.COUNT));

        // The timer and pull come from how the car's own road speed changes; the other three need the
        // screen's GPS or motion sensor and stay blank on a radio that has none.
        screen("zero_sixty", "0-60 time", "s", 0, 20);
        screen("g_accel", "Pull / braking", "g", -1, 1);
        screen("gps_mph", "GPS speed", "mph", 0, 180);
        screen("g_force", "G-force", "g", 0, 1.2);
        screen("speedo_err", "Speedo vs GPS", "%", -15, 15);
        // worked out from engine speed against road speed (see Gears)
        screen("gear", "Gear", "of 6", 0, 6);
        // worked out from air flow and lambda (see FuelUse); the car does not report fuel use
        screen("mpg", "MPG now", "mpg", 0, 40);
        screen("mpg_avg", "MPG this drive", "mpg", 0, 40);
        screen("fuel_used", "Fuel used this drive", "gal", 0, 10);
        screen("fuel_gph", "Fuel flow", "gal/h", 0, 50);        // the turbo goes past 30 at full throttle
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
