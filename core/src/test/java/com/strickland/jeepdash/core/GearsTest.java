package com.strickland.jeepdash.core;

import java.util.Arrays;

/** The gear indicator: worked out from engine speed against road speed, with the car's own gearing learned. */
public class GearsTest {

    /** Feeds n readings a quarter of a second apart at a steady speed, in a gear whose engine speed per mph is k. */
    static long drive(Gears g, double k, double mph, int n, long t) {
        for (int i = 0; i < n; i++) { t += 250; g.sample(k * mph, mph, t); }
        return t;
    }

    static double k(Gears g, int gear) { return g.topRpmPerMph() * g.ratio(gear) / g.ratio(g.count()); }

    public static void eachGearIsReadFromEngineSpeedAgainstRoadSpeed() {
        for (Gears g : new Gears[]{Gears.jeep(), Gears.porsche()}) {
            long t = 0;
            for (int gear = 1; gear <= g.count(); gear++) {
                t = drive(g, k(g, gear), 20 + 8 * gear, 3, t);
                T.eq((double) gear, g.gear(t), "gear " + gear + " of " + g.count());
            }
        }
        T.eq(4, Gears.jeep().count(), "the Jeep's four");
        T.eq(6, Gears.porsche().count(), "the Cayenne's six");
    }

    public static void aSlippingConverterStillReadsTheRightGear() {
        Gears g = Gears.jeep();
        long t = drive(g, k(g, 2) * 1.15, 25, 3, 0);
        T.eq(2.0, g.gear(t), "second, engine 15% ahead of the wheels");
        g.sample(2000, 5, t += 250);
        g.sample(2200, 7, t += 250);
        T.eq(1.0, g.gear(t), "pulling away: anything this far above first is first");
        Gears p = Gears.porsche();
        t = drive(p, k(p, 6) * 1.10, 70, 3, 0);
        T.eq(6.0, p.gear(t), "sixth with some slip is not fifth");
        t = drive(p, k(p, 5), 70, 3, t);
        T.eq(5.0, p.gear(t), "and fifth is fifth");
    }

    public static void stoppedOrSilentShowsNothing() {
        Gears g = Gears.jeep();
        long t = drive(g, k(g, 3), 40, 3, 0);
        T.eq(3.0, g.gear(t), "moving");
        T.eq(null, g.gear(t + 3001), "no readings for three seconds: blank");
        g.sample(700, 0, t += 250);
        g.sample(700, 0, t += 250);
        g.sample(700, 0, t += 250);
        T.eq(null, g.gear(t), "stopped: blank");
    }

    public static void betweenGearsShowsNothingRatherThanAGuess() {
        Gears g = Gears.jeep();
        long t = drive(g, k(g, 3) * 1.30, 35, 4, 0);        // too far above third, still below second
        T.eq(null, g.gear(t), "no gear fits");
        t = drive(g, k(g, 4) * 0.80, 40, 4, t);             // coasting with the engine near idle
        T.eq(null, g.gear(t), "below top gear: nothing fits either");
    }

    public static void oneOddReadingDoesNotFlickerTheGear() {
        Gears g = Gears.jeep();
        long t = drive(g, k(g, 3), 40, 3, 0);
        g.sample(k(g, 2) * 40, 40, t += 250);
        T.eq(3.0, g.gear(t), "one reading that looks like second is ignored");
        t = drive(g, k(g, 2), 40, 2, t);
        T.eq(2.0, g.gear(t), "two more in a row is a downshift");
    }

    public static void itLearnsThisCarsOwnGearingOnTheHighway() {
        Gears g = Gears.jeep();
        double stock = g.topRpmPerMph(), real = stock * 0.90;        // taller tyres or a different axle
        long t = drive(g, real, 65, 3, 0);
        T.eq(null, g.gear(t), "before it has learned, this car's top gear fits nothing");
        t = drive(g, real, 65, 20, t);
        T.near(real, g.topRpmPerMph(), real * 0.01, "learned from steady highway driving");
        T.eq(4.0, g.gear(t), "now top gear reads");
        t = drive(g, real * 1.00 / 0.69, 40, 3, t);
        T.eq(3.0, g.gear(t), "and the other gears follow from it");
    }

    public static void speedingUpOrSlowingDownTeachesItNothing() {
        Gears g = Gears.jeep();
        double stock = g.topRpmPerMph();
        long t = 0;
        for (int i = 0; i < 40; i++) { t += 250; g.sample(stock * 0.9 * (55 + i), 55 + i, t); }   // 4 mph a second
        T.near(stock, g.topRpmPerMph(), 1e-9, "only steady cruising counts");
        t = drive(g, stock * 0.9, 30, 40, t);
        T.near(stock, g.topRpmPerMph(), 1e-9, "and only at highway speed");
    }

    public static void aWrongFirstGuessCorrectsItselfOnceAHigherGearIsSeen() {
        Gears p = Gears.porsche();
        double stock = p.topRpmPerMph(), realTop = stock * 0.85, realFifth = realTop * 0.86 / 0.69;
        long t = drive(p, realFifth, 60, 25, 0);                     // held in fifth; it looks like a plausible top gear
        T.near(realFifth, p.topRpmPerMph(), realFifth * 0.01, "taken for top gear at first");
        t = drive(p, realTop, 70, 45, t);                            // then real sixth, steadily
        T.near(realTop, p.topRpmPerMph(), realTop * 0.01, "a steady lower engine speed at highway speed can only be a higher gear");
        T.eq(6.0, p.gear(t), "sixth reads as sixth");
        t = drive(p, realFifth, 60, 3, t);
        T.eq(5.0, p.gear(t), "and fifth as fifth");
    }

    public static void whatItLearnedSurvivesARestart() {
        Gears g = Gears.jeep();
        double real = g.topRpmPerMph() * 0.90;
        drive(g, real, 65, 25, 0);
        Gears again = Gears.jeep();
        again.restore(g.topRpmPerMph());
        long t = drive(again, real, 65, 3, 0);
        T.eq(4.0, again.gear(t), "no learning needed the second time");
        Gears fresh = Gears.jeep();
        double stock = fresh.topRpmPerMph();
        fresh.restore(0);
        fresh.restore(Double.NaN);
        fresh.restore(stock * 3);
        T.near(stock, fresh.topRpmPerMph(), 1e-9, "a saved figure that makes no sense is ignored");
    }

    public static void theGaugeIsOfferedOnBothCarsAndHasEngineAndRoadSpeedRead() {
        T.ok(Catalog.jeep("gear") != null && Catalog.jeep("gear").tablet, "Jeep: worked out, never asked of the engine");
        T.ok(PorscheCatalog.byId("gear") != null && PorscheCatalog.byId("gear").tablet && PorscheCatalog.value("gear") == null,
                "Cayenne: the same");
        T.near(4, Catalog.jeep("gear").max, 1e-9, "four gears on the Jeep's scale");
        T.near(6, PorscheCatalog.byId("gear").max, 1e-9, "six on the Cayenne's");
        T.ok(Motion.needsJeepSpeed(Arrays.asList("gear")), "road speed is read for it");
        T.ok(Motion.needsRpm(Arrays.asList("gear")), "and engine speed");
        T.ok(!Motion.needsRpm(Arrays.asList("zero_sixty", "speed")), "but not for the timer");
    }
}
