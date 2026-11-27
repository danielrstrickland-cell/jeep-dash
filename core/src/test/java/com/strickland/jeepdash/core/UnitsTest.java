package com.strickland.jeepdash.core;

import java.util.Arrays;
import java.util.Collections;

/** Showing a gauge in another unit: which units each value offers, and the arithmetic. */
public class UnitsTest {

    public static void eachFamilyOffersItsUnitsWithTheBaseFirst() {
        T.eq(Arrays.asList("psi", "kPa", "bar"), Units.choices("psi"), "boost");
        T.eq(Arrays.asList("kPa", "psi", "bar"), Units.choices("kPa"), "the Jeep's manifold");
        T.eq(Arrays.asList("°F", "°C"), Units.choices("°F"), "temperatures");
        T.eq(Arrays.asList("mph", "km/h"), Units.choices("mph"), "speed");
        T.eq(Arrays.asList("lb/h", "g/s", "kg/h"), Units.choices("lb/h"), "air flow");
        T.eq(Arrays.asList("gal", "L"), Units.choices("gal"), "fuel used");
        T.eq(Arrays.asList("mpg", "km/L"), Units.choices("mpg"), "economy");
        T.eq(Arrays.asList("gal/h", "L/h"), Units.choices("gal/h"), "fuel flow");
    }

    public static void aUnitWithNoFamilyHasNoChoice() {
        for (String u : new String[]{"V", "rpm", "%", "ms", "°", "deg", "ratio", "steps", "s", "g", "of 4", "0-7", "nonsense"})
            T.eq(Collections.emptyList(), Units.choices(u), u + " has no alternatives");
        T.ok(!Units.choices("psi").contains("V"), "volts are the raw sensor signal, not a pressure unit");
    }

    public static void convertsBetweenUnitsOfOneFamily() {
        T.near(100, Units.convert(212, "°F", "°C"), 1e-9, "boiling");
        T.near(-40, Units.convert(-40, "°C", "°F"), 1e-9, "the crossover");
        T.near(6.89476, Units.convert(1, "psi", "kPa"), 1e-4, "psi to kPa");
        T.near(1.01325, Units.convert(101.325, "kPa", "bar"), 1e-4, "kPa to bar");
        T.near(-1.0342, Units.convert(-15, "psi", "bar"), 1e-3, "vacuum stays below zero");
        T.near(100, Units.convert(62.1371, "mph", "km/h"), 1e-3, "mph to km/h");
        T.near(1, Units.convert(7.93664, "lb/h", "g/s"), 1e-4, "lb/h to g/s");
        T.near(2.20462, Units.convert(1, "kg/h", "lb/h"), 1e-4, "kg/h to lb/h");
        T.near(3.78541, Units.convert(1, "gal", "L"), 1e-4, "gal to L");
        T.near(3.78541, Units.convert(1, "gal/h", "L/h"), 1e-4, "gal/h to L/h");
        T.near(10, Units.convert(23.5215, "mpg", "km/L"), 1e-3, "mpg to km/L");
        T.near(42, Units.convert(42, "psi", "psi"), 0, "the same unit is left alone");
    }

    public static void convertingAndBackGivesTheSameReading() {
        String[][] pairs = {{"°F", "°C"}, {"psi", "bar"}, {"kPa", "psi"}, {"lb/h", "kg/h"}, {"mpg", "km/L"}};
        for (String[] p : pairs)
            T.near(123.4, Units.convert(Units.convert(123.4, p[0], p[1]), p[1], p[0]), 1e-9, p[0] + " and " + p[1]);
    }

    public static void aGaugeInAnotherUnitGetsAConvertedScale() {
        Param coolant = Catalog.jeep("coolant");
        Param c = Units.display(coolant, "°C");
        T.eq("coolant", c.id, "still the same value");
        T.eq("°C", c.unit, "new label");
        T.eq("°F", c.baseUnit, "remembers what it is read in");
        T.near(37.78, c.min, 0.01, "scale start");
        T.near(126.67, c.max, 0.01, "scale end");
        T.eq(coolant.name, c.name, "same name");
        T.ok(!c.tablet && c.sciIds == coolant.sciIds, "still read the same way");
        T.ok(Units.display(coolant, null) == coolant, "no unit chosen: the value as it is");
        T.ok(Units.display(coolant, "°F") == coolant, "the base unit: the value as it is");
        T.ok(Units.display(coolant, "kPa") == coolant, "a unit from another family is ignored");
        T.eq("°F", coolant.baseUnit, "a plain value's base unit is its own");
    }

    public static void everyBaseUnitInBothCatalogsIsSpelledAsTheUnitsClassExpects() {
        java.util.List<Param> all = new java.util.ArrayList<>(Catalog.all());
        all.addAll(PorscheCatalog.all());
        int offered = 0;
        for (Param p : all) if (!Units.choices(p.unit).isEmpty()) offered++;
        T.ok(offered >= 20, "temperatures, pressures, speeds, air flow and the fuel gauges all offer a choice: " + offered);
        T.ok(Units.choices(Catalog.jeep("map_v").unit).isEmpty(), "the MAP sensor voltage stays in volts");
    }

    public static void aGaugeCanHaveItsOwnRangeAndRedZone() {
        Param p = Catalog.byId("coolant");
        GaugeSpec g = new GaugeSpec("g1", "coolant", GaugeType.DIAL, 0, 0, 100, 100);
        T.ok(Units.forGauge(p, g) == p, "nothing set: the value as it is");
        g.rangeMin = 150.0;
        g.rangeMax = 250.0;
        g.redFrom = 225.0;
        Param d = Units.forGauge(p, g);
        T.near(150, d.min, 1e-9, "own low end");
        T.near(250, d.max, 1e-9, "own high end");
        T.near(225, d.redLine, 1e-9, "own red zone");
        g.unit = "°C";
        d = Units.forGauge(p, g);
        T.near(Units.convert(150, "°F", "°C"), d.min, 1e-6, "range shown in the gauge's unit");
        T.near(Units.convert(225, "°F", "°C"), d.redLine, 1e-6, "and the red zone");
        g.unit = null;
        g.rangeMax = 100.0;
        d = Units.forGauge(p, g);
        T.near(p.min, d.min, 1e-9, "an upside-down range is ignored");
        T.near(225, d.redLine, 1e-9, "but the red zone still stands");
    }
}
