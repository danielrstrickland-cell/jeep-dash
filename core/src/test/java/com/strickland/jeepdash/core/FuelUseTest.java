package com.strickland.jeepdash.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

/** Fuel flow, economy and fuel used per drive, worked out by the screen, and the fill-up calibration. */
public class FuelUseTest {

    /** Feeds the Cayenne the same readings every 100 ms for the given time; returns the new time. */
    static long cayenne(FuelUse f, double maf, Double lr, Double ll, Double mph, long ms, long t) {
        for (long end = t + ms; t < end; ) { t += 100; f.porsche(maf, lr, ll, mph, t); }
        return t;
    }

    static long jeep(FuelUse f, double injMs, double rpm, Double mph, long ms, long t) {
        for (long end = t + ms; t < end; ) { t += 100; f.jeep(injMs, rpm, mph, t); }
        return t;
    }

    public static void cayenneFuelComesFromAirFlowAndLambda() {
        FuelUse f = new FuelUse();
        f.porsche(147.0 * 6.17, 1.0, 1.0, 60.0, 1000);
        T.near(10, f.gph(1000), 1e-9, "air ÷ 14.7 at lambda 1, ÷ 6.17 lb a gallon");
        f.porsche(147.0 * 6.17, 1.1, 0.9, 60.0, 1100);
        T.near(10, f.gph(1100), 1e-9, "the two banks are averaged");
        f.porsche(147.0 * 6.17, 1.25, null, 60.0, 1200);
        T.near(8, f.gph(1200), 1e-9, "one bank alone is used when the other is missing");
        f.porsche(147.0 * 6.17, null, null, 60.0, 1300);
        T.near(10, f.gph(1300), 1e-9, "no lambda: taken as 1.0");
        T.near(6, f.mpgNow(1300), 1e-9, "60 mph on 10 gal/h");
    }

    public static void jeepFuelComesFromInjectorTimeAndEngineSpeed() {
        FuelUse f = new FuelUse();
        f.jeep(5.0, 2400.0, 50.0, 1000);
        // 8 injectors x 24 lb/h x 5 ms / 1000 x 2400 / 120 = 19.2 lb/h
        T.near(19.2 / 6.17, f.gph(1000), 1e-9, "injector duty x injector rate");
        f.jeep(null, 2400.0, 50.0, 1100);
        T.eq(null, f.gph(1100), "no injector time: no fuel reading");
        T.eq(null, f.mpgNow(1100), "and no economy either");
    }

    public static void economyNowIsZeroWhenStoppedCappedAndBlankWhenStale() {
        FuelUse f = new FuelUse();
        f.porsche(500.0, 1.0, 1.0, 0.0, 1000);
        T.near(0, f.mpgNow(1000), 0, "stopped");
        f.porsche(0.0, 1.0, 1.0, 40.0, 1100);
        T.near(99, f.mpgNow(1100), 0, "coasting with no fuel flow: the cap");
        f.porsche(10.0, 1.0, 1.0, 70.0, 1200);
        T.near(99, f.mpgNow(1200), 0, "a tiny flow is capped too");
        f.porsche(147.0 * 6.17, 1.0, 1.0, null, 1300);
        T.eq(null, f.mpgNow(1300), "no speed: no economy");
        T.near(10, f.gph(1300), 1e-9, "but the flow is still known");
        T.eq(null, f.gph(1300 + FuelUse.STALE_MS + 1), "nothing new for a while: flow goes blank");
        T.eq(null, f.mpgNow(1300 + FuelUse.STALE_MS + 1), "and so does economy");
    }

    public static void fuelAndMilesAddUpOverTheDrive() {
        FuelUse f = new FuelUse();
        long t = cayenne(f, 147.0 * 6.17, 1.0, 1.0, 60.0, 3_600_000, 0);       // an hour at 10 gal/h, 60 mph
        T.near(10, f.driveGallons(), 0.01, "ten gallons in the hour");
        T.near(6, f.driveMpg(), 0.01, "sixty miles on them");
        T.near(0, f.sinceFill(), 0, "nothing counted for the fill-up until it is started");
        cayenne(f, 147.0 * 6.17, 1.0, 1.0, 0.0, 360_000, t);                   // six minutes idling at the same flow
        T.near(11, f.driveGallons(), 0.01, "idling burns fuel");
        T.near(60 / 11.0, f.driveMpg(), 0.01, "and pulls the average down");
    }

    public static void aGapInTheReadingsAddsNothing() {
        FuelUse f = new FuelUse();
        long t = jeep(f, 5.0, 2400.0, 50.0, 10_000, 0);
        double gal = f.driveGallons();
        f.jeep(5.0, 2400.0, 50.0, t + 2_500);                                  // the link dropped for 2.5 s
        T.near(gal, f.driveGallons(), 1e-12, "a gap over 2 s invents nothing");
        f.jeep(5.0, 2400.0, 50.0, t + 4_500);
        T.near(gal + 19.2 / 6.17 * 2 / 3600, f.driveGallons(), 1e-9, "a 2 s step still counts");
    }

    public static void averageWaitsForATrickleOfFuel() {
        FuelUse f = new FuelUse();
        T.eq(null, f.driveMpg(), "nothing burnt yet");
        cayenne(f, 147.0 * 6.17, 1.0, 1.0, 30.0, 3000, 0);                    // about 0.008 gal
        T.ok(f.driveGallons() < 0.01, "under a hundredth of a gallon");
        T.eq(null, f.driveMpg(), "too little to divide by");
    }

    public static void aNewDriveStartsTheDriveFiguresAgainButNotTheFillUp() {
        FuelUse f = new FuelUse();
        f.startFill();
        f.drive(5000);
        long t = cayenne(f, 147.0 * 6.17, 1.0, 1.0, 60.0, 360_000, 0);
        T.near(1, f.driveGallons(), 0.01, "a gallon this drive");
        f.drive(5000);
        T.near(1, f.driveGallons(), 0.01, "the same drive: kept");
        f.drive(900_000);
        T.near(0, f.driveGallons(), 0, "a new drive starts at zero");
        T.eq(null, f.driveMpg(), "with no average yet");
        T.near(1, f.sinceFill(), 0.01, "the fill-up estimate carries on");
        cayenne(f, 147.0 * 6.17, 1.0, 1.0, 60.0, 360_000, t + 600_000);
        T.near(2, f.sinceFill(), 0.02, "and keeps adding up");
    }

    public static void theCalibrationFactorScalesEverything() {
        FuelUse f = new FuelUse();
        f.setFactor(1.25);
        f.startFill();
        cayenne(f, 147.0 * 6.17, 1.0, 1.0, 60.0, 360_000, 0);
        T.near(12.5, f.gph(360_000), 1e-9, "flow");
        T.near(1.25, f.driveGallons(), 0.01, "fuel used");
        T.near(1.25, f.sinceFill(), 0.01, "fill-up estimate");
        T.near(4.8, f.mpgNow(360_000), 1e-9, "economy");
    }

    public static void calibratingAtAFillUp() {
        T.near(1.1, FuelUse.calibrate(1.0, 10, 11), 1e-9, "the pump put in 10% more than the dash counted");
        T.near(0.9 * 1.5, FuelUse.calibrate(0.9, 12, 18), 1e-9, "builds on the factor already in use");
        T.eq(null, FuelUse.calibrate(1.0, 1.9, 3), "under 2 gal counted: too little to go on");
        T.eq(null, FuelUse.calibrate(1.0, 10, 0), "nothing pumped");
        T.eq(null, FuelUse.calibrate(1.0, 10, Double.NaN), "nothing readable typed");
        T.near(2, FuelUse.calibrate(1.0, 5, 40), 1e-9, "one correction at most doubles");
        T.near(0.5, FuelUse.calibrate(1.0, 20, 1), 1e-9, "or halves");
        T.near(3, FuelUse.calibrate(2.5, 10, 20), 1e-9, "the factor never goes past 3");
        T.near(0.3, FuelUse.calibrate(0.4, 10, 5), 1e-9, "or under 0.3");
    }

    public static void startingAFillUpPeriod() {
        FuelUse f = new FuelUse();
        T.ok(!f.fillStarted(), "nothing marked at first");
        long t = cayenne(f, 147.0 * 6.17, 1.0, 1.0, 60.0, 36_000, 0);
        T.near(0, f.sinceFill(), 0, "not counted before the first fill-up is marked");
        f.startFill();
        T.ok(f.fillStarted(), "marked");
        cayenne(f, 147.0 * 6.17, 1.0, 1.0, 60.0, 36_000, t);
        T.near(0.1, f.sinceFill(), 0.001, "counted from the fill-up");
        f.startFill();
        T.near(0, f.sinceFill(), 0, "the next fill-up starts again");
        f.restore(1.4, 7.5, true);
        T.near(1.4, f.factor(), 0, "saved factor");
        T.near(7.5, f.sinceFill(), 0, "saved estimate");
        T.ok(f.fillStarted(), "saved mark");
    }

    public static void inputsArePolledOnlyWhenNeeded() {
        T.eq(Arrays.asList("maf", "lambda_r", "lambda_l", "speed"), FuelUse.inputs(true), "the Cayenne's");
        T.eq(Arrays.asList("inj_ms", "rpm", "speed"), FuelUse.inputs(false), "the Jeep's");
        T.ok(!FuelUse.needsInputs(new HashSet<>(Arrays.asList("rpm", "coolant")), false), "no fuel gauge, no fill-up");
        T.ok(FuelUse.needsInputs(Collections.emptySet(), true), "a fill-up period is running");
        for (String id : new String[]{"mpg", "mpg_avg", "fuel_used", "fuel_gph"})
            T.ok(FuelUse.needsInputs(Collections.singleton(id), false), id + " on screen");
        for (String id : FuelUse.inputs(true)) T.ok(PorscheCatalog.byId(id) != null, id + " is a Cayenne value");
        for (String id : FuelUse.inputs(false)) T.ok(Catalog.jeep(id) != null, id + " is a Jeep value");
    }

    public static void onlyFreshReadingsAreUsed() {
        LiveValues v = new LiveValues();
        v.put("maf", 500, 1000);
        T.near(500, FuelUse.fresh(v, "maf", 1000 + FuelUse.FRESH_MS), 0, "just fresh enough");
        T.eq(null, FuelUse.fresh(v, "maf", 1001 + FuelUse.FRESH_MS), "too old to count fuel on");
        T.eq(null, FuelUse.fresh(v, "speed", 1000), "never read");
    }

    public static void theFuelGaugesAreOnBothCars() {
        for (String id : new String[]{"mpg", "mpg_avg", "fuel_used", "fuel_gph"}) {
            Param j = Catalog.jeep(id), p = PorscheCatalog.byId(id);
            T.ok(j != null && j.tablet, id + " on the Jeep, worked out by the screen");
            T.ok(p != null && p.tablet, id + " on the Cayenne, worked out by the screen");
            T.eq(j.unit, p.unit, id + " same unit");
            T.eq(j.max, p.max, id + " same scale");
        }
        T.eq("mpg", Catalog.jeep("mpg").unit, "economy unit");
        T.eq("gal", Catalog.jeep("fuel_used").unit, "fuel used unit");
        T.eq("gal/h", Catalog.jeep("fuel_gph").unit, "flow unit");
        T.near(40, Catalog.jeep("mpg_avg").max, 0, "economy scale");
        T.near(30, Catalog.jeep("fuel_gph").max, 0, "flow scale");
    }
}
