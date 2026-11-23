package com.strickland.jeepdash.core;

/** The per-car speed correction, the reading it applies to, and setting it from GPS. */
public class SpeedCorrectionTest {

    public static void correctionIsKeptWithinFifteenPercent() {
        T.near(15, SpeedCorrection.clamp(22), 1e-9, "top");
        T.near(-15, SpeedCorrection.clamp(-40), 1e-9, "bottom");
        T.near(3.6, SpeedCorrection.clamp(3.6), 1e-9, "inside");
        T.near(0, SpeedCorrection.clamp(Double.NaN), 1e-9, "nonsense is none");
        T.near(1.036, SpeedCorrection.factor(3.6), 1e-9, "factor");
    }

    public static void aScaledValueIsCorrectedAndItsRawFigureKept() {
        LiveValues v = new LiveValues();
        v.setScale("speed", 1.05);
        v.put("speed", 60, 1000);
        T.near(63, v.get("speed", 1000), 1e-9, "everything that reads speed gets the corrected figure");
        T.near(60, v.raw("speed", 1000), 1e-9, "the raw figure for Speedo vs GPS");
        T.eq(1000L, v.timeOf("speed"), "same time");
        T.eq(null, v.raw("speed", 1000 + LiveValues.STALE_MS + 1), "raw goes stale with it");
        v.put("rpm", 2000, 1000);
        T.near(2000, v.get("rpm", 1000), 1e-9, "other values untouched");
        T.near(2000, v.raw("rpm", 1000), 1e-9, "raw of an unscaled value is the value");
    }

    public static void noCorrectionIsTheReadingAsItCame() {
        LiveValues v = new LiveValues();
        v.setScale("speed", 1.05);
        v.setScale("speed", 1.0);
        v.put("speed", 60, 0);
        T.near(60, v.get("speed", 0), 1e-9, "factor 1 removes it");
        T.near(60, v.raw("speed", 0), 1e-9, "raw the same");
    }

    public static void clearForgetsTheRawFigureToo() {
        LiveValues v = new LiveValues();
        v.setScale("speed", 1.1);
        v.put("speed", 50, 0);
        v.clear();
        T.eq(null, v.raw("speed", 0), "nothing outlives the link");
    }

    public static void theGearingFigureFollowsTheCorrection() {
        // rpm per corrected mph: a bigger correction means more mph for the same rpm
        T.near(336.13 * 1.0 / 1.036, SpeedCorrection.rescaleTop(336.13, 0, 3.6), 1e-9, "0 to +3.6%");
        T.near(336.13, SpeedCorrection.rescaleTop(SpeedCorrection.rescaleTop(336.13, 0, 3.6), 3.6, 0), 1e-9, "and back");
    }

    /** One GPS fix a second with the car's raw reading beside it. */
    private static long drive(SpeedCorrection.Gps g, long t, int seconds, double gps, double car) {
        for (int i = 0; i < seconds; i++, t += 1000) g.sample(gps, car, t);
        return t;
    }

    public static void gpsNeedsAMinuteAboveFortyMph() {
        SpeedCorrection.Gps g = new SpeedCorrection.Gps();
        long t = drive(g, 0, 50, 62.2, 60);
        T.eq(null, g.measuredPct(), "50 s is not enough");
        t = drive(g, t, 30, 35, 33.8);
        T.eq(null, g.measuredPct(), "time under 40 mph does not count");
        drive(g, t, 12, 62.2, 60);
        T.ok(g.countedMs() >= SpeedCorrection.Gps.NEED_MS, "now a minute: " + g.countedMs());
        T.near(3.7, g.measuredPct(), 1e-9, "GPS 3.7% above the car's reading, to a tenth");
    }

    public static void gapsAndGlitchesDoNotCount() {
        SpeedCorrection.Gps g = new SpeedCorrection.Gps();
        long t = 0;
        for (int i = 0; i < 100; i++, t += 5000) g.sample(60, 58, t);      // fixes too far apart
        T.eq(0L, g.countedMs(), "a gap counts no time");
        t = drive(g, t, 100, 60, 30);
        T.eq(0L, g.countedMs(), "half the speed is a glitch, not a tyre");
        drive(g, t, 100, 60, 0);
        T.eq(0L, g.countedMs(), "no car reading");
    }

    public static void aMeasurementOutsideTheRangeIsReportedNotStretched() {
        SpeedCorrection.Gps g = new SpeedCorrection.Gps();
        drive(g, 0, 70, 72, 60);
        T.near(20, g.measuredPct(), 0.01, "measured 20%");
        T.ok(!SpeedCorrection.usable(g.measuredPct()), "outside +-15: not used");
        T.ok(SpeedCorrection.usable(3.6), "inside: used");
        T.ok(!SpeedCorrection.usable(null), "nothing measured");
    }
}
