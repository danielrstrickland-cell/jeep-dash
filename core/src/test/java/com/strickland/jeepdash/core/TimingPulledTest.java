package com.strickland.jeepdash.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public class TimingPulledTest {

    /** kPa that lands exactly on a MAP column of the table (raw counts from the engine computer's own axis). */
    private static double kpaAtRaw(int raw) { return raw / SparkTable.MAP_RAW_PER_KPA; }

    public static void tableCellsComeBackExactlyOnTheAxisPoints() {
        T.near(20.0, SparkTable.advance(608, kpaAtRaw(36)), 1e-9, "first row, first column");
        T.near(32.0, SparkTable.advance(992, kpaAtRaw(128)), 1e-9, "992 rpm, column 4");
        T.near(38.0, SparkTable.advance(2016, kpaAtRaw(192)), 1e-9, "2016 rpm, column 6");
        T.near(28.0, SparkTable.advance(1401, kpaAtRaw(192)), 1e-9, "1401 rpm, column 6");
        T.near(17.0, SparkTable.advance(896, kpaAtRaw(192)), 1e-9, "896 rpm, column 6");
        T.near(34.0, SparkTable.advance(4402, kpaAtRaw(160)), 1e-9, "4402 rpm, column 5");
    }

    public static void tableBlendsBetweenCells() {
        // halfway between 608 and 704 rpm and between columns 5 and 6: (24 + 15 + 20 + 16) / 4
        T.near(18.75, SparkTable.advance(656, kpaAtRaw(176)), 1e-9, "bilinear middle");
        // the MAP axis is uneven at the bottom: raw 47 is halfway between 36 (column 0) and 58 (column 1)
        T.near(0.5, SparkTable.column(kpaAtRaw(47)), 1e-9, "uneven MAP axis");
    }

    public static void tableHoldsItsEdges() {
        T.near(SparkTable.advance(608, 50), SparkTable.advance(300, 50), 1e-9, "below the lowest rpm");
        T.near(SparkTable.advance(4800, 50), SparkTable.advance(6500, 50), 1e-9, "above the highest rpm");
        T.near(0, SparkTable.column(5), 1e-9, "below the lowest MAP");
        T.ok(SparkTable.column(200) < 8, "above the highest MAP stays inside the table");
    }

    // ---------------- the gauge ----------------

    private static final class Rig {
        final LiveValues v = new LiveValues();
        final TimingPulled t = new TimingPulled();
        long now;
        Double last;

        /** One second of steady readings, every value read once at the same moment. */
        Double read(double rpm, double kpa, double spark, double coolantF) {
            now += 1000;
            v.put("rpm", rpm, now);
            v.put("map_kpa", kpa, now);
            v.put("spark", spark, now);
            v.put("coolant", coolantF, now);
            last = t.update(v, now);
            return last;
        }

        /** Cruising where the table says 38: the actual timing is 38 minus what was pulled. */
        Double cruise(double pulled) { return read(2016, kpaAtRaw(192), 38 - pulled, 195); }
    }

    public static void readsTheTableMinusTheActualTiming() {
        Rig r = new Rig();
        for (int i = 0; i < 4; i++) r.cruise(5);
        T.near(5, r.last, 1e-9, "5 degrees pulled");
    }

    public static void neverReadsBelowZero() {
        Rig r = new Rig();
        for (int i = 0; i < 4; i++) r.cruise(-3);
        T.near(0, r.last, 1e-9, "more timing than the table is not negative pull");
    }

    public static void blankUntilTheConditionsHaveHeldForTwoSeconds() {
        Rig r = new Rig();
        T.eq(null, r.cruise(4), "first reading");
        T.eq(null, r.cruise(4), "one second in");
        T.near(4, r.cruise(4), 1e-9, "two seconds in");
    }

    public static void blankWhenColdOrOutsideTheRpmBand() {
        Rig cold = new Rig();
        for (int i = 0; i < 5; i++) cold.read(2016, kpaAtRaw(192), 30, 170);
        T.eq(null, cold.last, "cold engine");
        Rig low = new Rig();
        for (int i = 0; i < 5; i++) low.read(1000, 40, 19, 195);
        T.eq(null, low.last, "below 1100 rpm (idle and closed-throttle coasting)");
        Rig high = new Rig();
        for (int i = 0; i < 5; i++) high.read(5200, 90, 20, 195);
        T.eq(null, high.last, "above 4800 rpm, past the table");
    }

    public static void blankAtClosedThrottleAndForTwoSecondsAfter() {
        Rig r = new Rig();
        for (int i = 0; i < 4; i++) r.cruise(1);
        T.eq(null, r.read(2016, 22, 30, 195), "manifold below 25 kPa: throttle shut");
        T.eq(null, r.cruise(1), "one second after");
        T.eq(null, r.cruise(1), "two seconds after");
        T.near(1, r.cruise(1), 1e-9, "back once it has been open for two seconds");
    }

    public static void blankWhileTheRpmFallsFast() {
        Rig r = new Rig();
        for (int i = 0; i < 4; i++) r.read(2400, 60, 30, 195);
        T.ok(r.last != null, "steady first");
        T.eq(null, r.read(2100, 60, 20, 195), "300 rpm down in a second: lift-off or an upshift");
        T.eq(null, r.read(2100, 60, 30, 195), "one second after");
        T.eq(null, r.read(2100, 60, 30, 195), "two seconds after");
        T.ok(r.read(2100, 60, 30, 195) != null, "steady again");
        Rig slow = new Rig();
        for (int i = 0; i < 4; i++) slow.read(2400 - 150 * i, 60, 30, 195);
        T.ok(slow.last != null, "150 rpm a second is ordinary slowing, not a lift-off");
    }

    public static void aSingleOddReadingIsIgnored() {
        Rig r = new Rig();
        for (int i = 0; i < 4; i++) r.cruise(0);
        T.near(0, r.cruise(12), 1e-9, "one reading taken mid-change does not move the gauge");
        T.near(0, r.cruise(0), 1e-9, "and it is gone again");
        T.near(0, r.cruise(0), 1e-9, "steady");
        T.near(0, r.cruise(9), 1e-9, "another single one");
        T.near(9, r.cruise(9), 1e-9, "pulled on two readings in a row shows");
    }

    public static void readingsTakenFarApartAreNotCompared() {
        Rig r = new Rig();
        for (int i = 0; i < 4; i++) r.cruise(2);
        T.near(2, r.last, 1e-9, "steady");
        // a fresh timing reading, but engine speed and pressure are two seconds older (a long read list)
        r.now += 2000;
        r.v.put("spark", 20, r.now);
        T.near(2, r.t.update(r.v, r.now), 1e-9, "the 18 degree gap is not taken as pulled timing");
    }

    public static void blankWhenAReadingIsMissing() {
        Rig r = new Rig();
        for (int i = 0; i < 4; i++) r.cruise(2);
        T.eq(null, r.t.update(r.v, r.now + LiveValues.STALE_MS + 1), "readings went stale");
    }

    public static void inputsArePolledOnlyWhenTheGaugeIsOnTheDash() {
        Set<String> screen = new LinkedHashSet<>(Collections.singletonList("speed"));
        T.eq(screen, TimingPulled.withInputs(screen, Collections.singletonList("rpm"), false), "no gauge: nothing added");
        Set<String> with = TimingPulled.withInputs(screen, Arrays.asList("rpm", TimingPulled.GAUGE), false);
        T.ok(with.containsAll(Arrays.asList("rpm", "map_kpa", "spark", "coolant")), "gauge on the dash: its inputs");
        T.ok(!screen.contains("spark"), "the given set is not changed");
        T.ok(TimingPulled.withInputs(Collections.singleton(TimingPulled.GAUGE), Collections.emptyList(), false)
                .contains("spark"), "gauge on the screen in front");
        T.eq(Collections.singleton(TimingPulled.GAUGE),
                TimingPulled.withInputs(Collections.singleton(TimingPulled.GAUGE), Collections.emptyList(), true),
                "never on the Porsche");
    }

    public static void theGaugeIsAJeepValueOnly() {
        try {
            Catalog.useVehicle("jeep");
            Param p = Catalog.byId(TimingPulled.GAUGE);
            T.ok(p != null && p.tablet, "worked out by the screen, never asked of the Jeep");
            T.eq("Timing pulled", p.name, "name");
            T.near(0, p.min, 0, "scale from 0");
            T.near(15, p.max, 0, "to 15");
            Catalog.useVehicle("porsche");
            T.eq(null, Catalog.byId(TimingPulled.GAUGE), "not on the Porsche");
        } finally {
            Catalog.useVehicle("jeep");
        }
    }
}
