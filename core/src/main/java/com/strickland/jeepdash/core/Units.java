package com.strickland.jeepdash.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Other units a gauge can be shown in. Values are always read, warned on and logged in their base unit (the
 * spelling used in Catalog and PorscheCatalog); a gauge set to another unit converts just before drawing.
 * Sensor volts are not a unit of pressure (they are the raw signal, a gauge of their own), so they are not here.
 */
public final class Units {
    private Units() { }

    /** One unit: its family, and how to turn it into the family's first unit (first = this x scale + offset). */
    private static final class Unit {
        final String family;
        final double scale, offset;

        Unit(String family, double scale, double offset) {
            this.family = family;
            this.scale = scale;
            this.offset = offset;
        }
    }

    private static final Map<String, Unit> UNITS = new LinkedHashMap<>();

    private static void unit(String family, String name, double scale, double offset) {
        UNITS.put(name, new Unit(family, scale, offset));
    }

    static {
        unit("pressure", "psi", 1, 0);
        unit("pressure", "kPa", 0.1450377, 0);
        unit("pressure", "bar", 14.50377, 0);
        unit("temperature", "°F", 1, 0);
        unit("temperature", "°C", 1.8, 32);
        unit("speed", "mph", 1, 0);
        unit("speed", "km/h", 0.6213712, 0);
        unit("air flow", "lb/h", 1, 0);
        unit("air flow", "g/s", 7.936641, 0);           // 3.6 kg/h
        unit("air flow", "kg/h", 2.204623, 0);
        unit("volume", "gal", 1, 0);                    // US gallons
        unit("volume", "L", 0.2641720, 0);
        unit("economy", "mpg", 1, 0);
        unit("economy", "km/L", 2.352146, 0);
        unit("fuel flow", "gal/h", 1, 0);
        unit("fuel flow", "L/h", 0.2641720, 0);
    }

    /** The units a value read in this unit can be shown in, the base unit first; empty when there is no choice. */
    public static List<String> choices(String baseUnit) {
        Unit base = baseUnit == null ? null : UNITS.get(baseUnit);
        if (base == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        out.add(baseUnit);
        for (Map.Entry<String, Unit> e : UNITS.entrySet())
            if (e.getValue().family.equals(base.family) && !e.getKey().equals(baseUnit)) out.add(e.getKey());
        return out;
    }

    /** True when a value read in baseUnit can be shown in unit (the base unit itself included). */
    public static boolean offers(String baseUnit, String unit) { return unit != null && choices(baseUnit).contains(unit); }

    /** A reading in one unit as the same reading in another of its family; left alone if they do not match. */
    public static double convert(double v, String from, String to) {
        if (from == null || from.equals(to)) return v;
        Unit a = UNITS.get(from), b = UNITS.get(to);
        if (a == null || b == null || !a.family.equals(b.family)) return v;
        return (v * a.scale + a.offset - b.offset) / b.scale;
    }

    /** The value as the gauge shows it: the same Param when no other unit is chosen (or it does not fit). */
    public static Param display(Param p, String unit) {
        if (p == null || unit == null || unit.equals(p.unit) || !offers(p.baseUnit, unit)) return p;
        return p.inUnit(unit, convert(p.min, p.unit, unit), convert(p.max, p.unit, unit));
    }
}
