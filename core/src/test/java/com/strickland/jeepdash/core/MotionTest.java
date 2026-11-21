package com.strickland.jeepdash.core;

public class MotionTest {
    /** Sits still for two seconds, then pulls away at a steady rate; a speed reading every stepMs. */
    static long launch(Motion m, double mphPerSecond, long stepMs, double upTo) {
        long t = 0;
        for (; t <= 2000; t += stepMs) m.dash(0, t);
        long go = t - stepMs;
        for (double v = 0; v < upTo; t += stepMs) {
            v = mphPerSecond * (t - go) / 1000.0;
            m.dash(v, t);
        }
        return t;
    }

    public static void aLaunchIsTimedFromRestToSixty() {
        Motion m = new Motion();
        T.ok(m.zeroSixty(1000) == null, "nothing to show before the first run");
        long end = launch(m, 10, 250, 70);
        T.near(6.0, m.zeroSixty(end), 0.05, "ten mph a second reaches sixty in six seconds");
        T.near(6.0, m.zeroSixty(end + 60000), 0.05, "and the time stays on screen");
    }

    public static void theClockRunsLiveDuringTheRun() {
        Motion m = new Motion();
        long t = launch(m, 10, 250, 30);
        Double live = m.zeroSixty(t);
        T.ok(live != null && live > 2.5 && live < 3.6, "about three seconds in, got " + live);
    }

    public static void sixtyBetweenTwoReadingsIsWorkedOut() {
        Motion m = new Motion();
        long end = launch(m, 7, 300, 66);
        T.near(60 / 7.0, m.zeroSixty(end), 0.06, "not rounded to a reading");
    }

    public static void theFirstShoveStartsTheClock() {
        Motion m = new Motion();
        for (long t = 0; t <= 2000; t += 500) m.dash(0, t);
        m.accel(0, 3.0, 0, 2200);                       // the Jeep moves before the speed reading shows it
        for (long t = 2500; t <= 9000; t += 500) m.dash(10 * (t - 2200) / 1000.0, t);
        T.near(6.0, m.zeroSixty(9000), 0.06, "timed from the shove at 2.2 s, not the reading at 2.0 s");
    }

    public static void liftingOffAbandonsTheRun() {
        Motion m = new Motion();
        long t = launch(m, 10, 250, 30);
        for (double v = 30; v >= 0; v -= 5, t += 250) m.dash(v, t);
        m.dash(0, t);
        T.ok(m.zeroSixty(t + 1000) == null, "no time for a run that never reached sixty");
    }

    public static void gForceComesFromTheAccelerometer() {
        Motion m = new Motion();
        for (long t = 0; t < 2000; t += 20) m.accel(0, 4.903, 0, t);
        T.near(0.5, m.gForce(2000), 0.02, "half a g, whichever way the tablet is mounted");
        T.ok(m.gForce(9000) == null, "blank once the sensor stops reporting");
    }

    public static void pullAndBrakingComeFromTheSpeedChanging() {
        Motion m = new Motion();
        for (long t = 0; t <= 3000; t += 250) m.dash(20 + 10 * t / 1000.0, t);
        T.near(0.456, m.gAccel(3000), 0.03, "ten mph a second is 0.46 g");
        for (long t = 3250; t <= 6000; t += 250) m.dash(50 - 10 * (t - 3000) / 1000.0, t);
        T.near(-0.456, m.gAccel(6000), 0.03, "braking reads negative");
    }

    public static void gpsSpeedGoesBlankWhenFixesStop() {
        Motion m = new Motion();
        m.gps(40, 0);
        T.near(40, m.gpsMph(1000), 1e-9, "fresh fix");
        T.ok(m.gpsMph(5000) == null, "four seconds without a fix");
    }

    public static void speedoErrorNeedsASteadyCruise() {
        Motion m = new Motion();
        m.gps(50, 0);
        m.dash(52, 0);
        T.ok(m.speedoError(0) == null, "one glance is not enough");
        for (long t = 250; t <= 20000; t += 250) {
            m.dash(52, t);
            if (t % 1000 == 0) m.gps(50, t);
        }
        T.near(4.0, m.speedoError(20000), 0.2, "dash reads 4% high");
        Motion slow = new Motion();
        for (long t = 0; t <= 20000; t += 250) {
            slow.dash(21, t);
            if (t % 1000 == 0) slow.gps(20, t);
        }
        T.ok(slow.speedoError(20000) == null, "town speeds are too rough to judge by");
    }

    /** Speed readings every 250 ms from fromMph at a steady rate until toMph is passed; returns the next time. */
    static long drive(Motion m, long t, double fromMph, double mphPerSecond, double toMph) {
        long t0 = t;
        for (double v = fromMph; mphPerSecond > 0 ? v < toMph : v > toMph; t += 250) {
            v = Math.max(0, fromMph + mphPerSecond * (t - t0) / 1000.0);
            m.dash(v, t);
        }
        return t;
    }

    public static void aFailedRunPutsTheLastGoodTimeBack() {
        Motion m = new Motion();
        long t = launch(m, 10, 250, 70);
        t = drive(m, t, 70, -10, 0);
        t = drive(m, t, 0, 0.0001, 0.0001);                 // a second at rest
        long go = t - 250;
        t = drive(m, t, 2.5, 10, 30);
        T.near((t - go) / 1000.0, m.zeroSixty(t), 0.01, "the clock runs again on the second launch");
        t = drive(m, t, 30, -10, 0);
        T.near(6.0, m.zeroSixty(t), 0.05, "lifting off brings the first run's time back");
    }

    public static void aRunOverAMinuteIsNoTime() {
        Motion m = new Motion();
        for (long t = 0; t <= 2000; t += 250) m.dash(0, t);
        long t = drive(m, 2250, 0.125, 0.5, 20);
        T.ok(m.zeroSixty(t) != null, "still on the clock at 40 s");
        t = drive(m, t, 20, 0.5, 65);
        T.ok(m.zeroSixty(t) == null, "two minutes to sixty is not a run");
        Motion crawl = new Motion();
        for (long u = 0; u <= 2000; u += 250) crawl.dash(0, u);
        long u = drive(crawl, 2250, 5, 0.0001, 5.0065);     // 65 s at walking pace
        T.ok(crawl.zeroSixty(u) == null, "the clock stops showing after a minute");
    }

    public static void aGapInTheReadingsDropsTheRun() {
        Motion m = new Motion();
        long t = launch(m, 10, 250, 20);
        T.ok(m.zeroSixty(t + 5000) == null, "no live clock once the readings stop");
        m.dash(65, t + 10000);                               // the link came back at speed
        m.dash(65, t + 10250);
        T.ok(m.zeroSixty(t + 10250) == null, "a time across the gap would be made up");
        Motion parked = new Motion();
        parked.dash(0, 0);
        parked.dash(65, 20000);
        parked.dash(65, 20250);
        T.ok(parked.zeroSixty(20250) == null, "nor from a stop seen long ago");
    }

    public static void gpsStandsInWhenTheJeepSpeedIsGone() {
        Motion m = new Motion();
        for (long t = 0; t <= 2000; t += 500) m.gps(0, t);
        for (long t = 2500; t <= 9000; t += 500) m.gps(10 * (t - 2000) / 1000.0, t);
        T.near(6.0, m.zeroSixty(9000), 0.05, "the timer runs on GPS alone");
        T.near(0.456, m.gAccel(9000), 0.03, "and so does the pull gauge");
    }

    public static void theJeepSpeedWinsOverGps() {
        Motion m = new Motion();
        for (long t = 0; t <= 5000; t += 250) {
            m.dash(30, t);
            if (t % 500 == 0) m.gps(30 + 10 * t / 1000.0, t);
        }
        T.near(0, m.gAccel(5000), 0.001, "a steady Jeep reading is steady, whatever GPS says");
        T.ok(m.zeroSixty(5000) == null, "and GPS passing sixty times nothing");
    }

    public static void vibrationIsNotGForce() {
        Motion m = new Motion();
        for (long t = 0; t < 3000; t += 10) m.accel(t % 20 == 0 ? 2 : -2, 0, 0, t);
        T.ok(m.gForce(3000) < 0.03, "a 0.2 g buzz reads near nothing, got " + m.gForce(3000));
    }

    public static void aBumpWhileStillStoppedIsNotTheStart() {
        Motion m = new Motion();
        m.dash(0, 0);
        m.accel(0, 0, 3.0, 500);                             // a door slams
        long end = launch(m, 10, 250, 70);
        T.near(6.0, m.zeroSixty(end), 0.05, "timed from the last reading at rest, not the bump");
    }

    public static void aShoveAfterTheFirstMovingReadingIsNotTheStart() {
        Motion m = new Motion();
        for (long t = 0; t <= 2000; t += 500) m.dash(0, t);
        m.accel(0, 3.0, 0, 2600);                           // felt at 2.6 s, before the 2.5 s reading is handed over
        for (long t = 2500; t <= 9000; t += 500) m.dash(10 * (t - 2000) / 1000.0, t);
        T.near(6.0, m.zeroSixty(9000), 0.05, "timed from the last reading at rest");
    }

    public static void aRestReadingHandedOverLateKeepsTheShove() {
        Motion m = new Motion();
        for (long t = 0; t <= 1500; t += 500) m.dash(0, t);
        m.accel(0, 3.0, 0, 2200);                           // felt before the 2.0 s reading is handed over
        m.dash(0, 2000);
        for (long t = 2500; t <= 9000; t += 500) m.dash(10 * (t - 2200) / 1000.0, t);
        T.near(6.0, m.zeroSixty(9000), 0.06, "still timed from the shove at 2.2 s");
    }

    public static void theShoveThatCountsComesAfterTheLastRestReading() {
        Motion m = new Motion();
        m.dash(0, 0);
        m.accel(0, 0, 3.0, 300);                             // a door slams
        for (long t = 500; t <= 2000; t += 500) m.dash(0, t);
        m.accel(0, 3.0, 0, 2200);
        m.accel(0, 3.0, 0, 2400);
        for (long t = 2500; t <= 9000; t += 500) m.dash(10 * (t - 2200) / 1000.0, t);
        T.near(6.0, m.zeroSixty(9000), 0.06, "timed from the first shove of the launch");
    }

    public static void theSameReadingTwiceIsCountedOnce() {
        Motion m = new Motion();
        for (long t = 0; t <= 3000; t += 250) {
            m.dash(20 + 10 * t / 1000.0, t);
            m.dash(21 + 10 * t / 1000.0, t);
        }
        T.near(0.456, m.gAccel(3000), 0.03, "no jump from two readings with one time");
    }

    public static void gaugesBuiltOnRoadSpeedNeedTheJeepsSpeedRead() {
        for (String id : new String[]{"g_accel", "zero_sixty", "speedo_err"})
            T.ok(Motion.needsJeepSpeed(java.util.Arrays.asList("rpm", id)), id + " is worked out from the Jeep's speed");
        T.ok(!Motion.needsJeepSpeed(java.util.Arrays.asList("rpm", "g_force", "gps_mph")), "these are not");
    }

    public static void onlyTheGpsGaugesNeedLocation() {
        T.ok(Motion.needsGps(java.util.Arrays.asList("rpm", "gps_mph")), "GPS speed");
        T.ok(Motion.needsGps(java.util.Arrays.asList("speedo_err")), "speedo check");
        T.ok(!Motion.needsGps(java.util.Arrays.asList("rpm", "g_force", "g_accel", "zero_sixty")), "the rest work without");
    }
}
