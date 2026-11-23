package com.strickland.jeepdash.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Gear, pull/braking and 0-60 run with the link; the readings they need are read whichever screen is in front. */
public class CarMotionTest {

    private static Set<String> set(String... ids) { return new LinkedHashSet<>(Arrays.asList(ids)); }

    public static void nothingOnTheDashAddsNothing() {
        T.eq(set("rpm"), Motion.withCarInputs(set("rpm"), set("coolant")), "as before");
        T.ok(!Motion.carWanted(set("rpm"), set("coolant")), "not worked out");
    }

    public static void pullAndTheTimerOnTheDashNeedSpeed() {
        T.eq(set("throttle", "speed"), Motion.withCarInputs(set("throttle"), set("g_accel")), "pull");
        T.eq(set("speed"), Motion.withCarInputs(set(), set("zero_sixty")), "0-60");
        T.ok(Motion.carWanted(Collections.<String>emptySet(), set("zero_sixty")), "worked out with the dash behind");
    }

    public static void theGearNeedsSpeedAndRpm() {
        T.eq(set("speed", "rpm"), Motion.withCarInputs(set(), set("gear")), "gear");
        T.ok(Motion.carWanted(set("gear"), set()), "on the screen in front");
    }

    public static void gpsAndSensorGaugesAreNotTheServicesJob() {
        T.eq(set(), Motion.withCarInputs(set(), set("g_force", "gps_mph", "speedo_err")), "those stay with the screen");
        T.ok(!Motion.carWanted(set("g_force"), set("speedo_err")), "not worked out by the link");
    }

    public static void theGivenSetIsNotChanged() {
        Set<String> s = set("rpm");
        Motion.withCarInputs(s, set("gear"));
        T.eq(set("rpm"), s, "copy");
    }
}
