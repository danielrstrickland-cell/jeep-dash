package com.strickland.jeepdash.core;

import java.time.Instant;

public class AutoNightTest {
    public static void nothingKnownMeansNoOpinion() {
        T.ok(new AutoNight().dark(0, 0) == null, "no sensor, no fix");
    }

    public static void firstReadingSetsTheStart() {
        AutoNight a = new AutoNight();
        a.lux(5, 0);
        T.eq(Boolean.TRUE, a.dark(0, 0), "dark room at start");
        AutoNight b = new AutoNight();
        b.lux(300, 0);
        T.eq(Boolean.FALSE, b.dark(0, 0), "bright room at start");
        AutoNight c = new AutoNight();
        c.lux(30, 0);
        T.eq(Boolean.FALSE, c.dark(0, 0), "in between starts light");
    }

    public static void darkNeedsTenSecondsUnder15() {
        AutoNight a = new AutoNight();
        a.lux(500, 0);
        a.lux(10, 1000);
        T.eq(Boolean.FALSE, a.dark(1000, 0), "just got dim");
        T.eq(Boolean.FALSE, a.dark(10_999, 0), "9.99 s is not enough");
        T.eq(Boolean.TRUE, a.dark(11_000, 0), "10 s turns dark");
    }

    public static void lightNeedsTenSecondsOver60() {
        AutoNight a = new AutoNight();
        a.lux(2, 0);
        a.lux(100, 5000);
        T.eq(Boolean.TRUE, a.dark(14_000, 0), "still dark at 9 s");
        T.eq(Boolean.FALSE, a.dark(15_000, 0), "light at 10 s");
    }

    public static void aBlipRestartsTheWait() {
        AutoNight a = new AutoNight();
        a.lux(500, 0);
        a.lux(5, 1000);
        a.lux(200, 8000);                       // a bright blip before the 10 s
        a.lux(5, 9000);                         // dim again: the wait starts over
        T.eq(Boolean.FALSE, a.dark(18_999, 0), "9.99 s after the restart");
        T.eq(Boolean.TRUE, a.dark(19_000, 0), "10 s after the restart");
    }

    public static void inTheMiddleNothingChanges() {
        AutoNight a = new AutoNight();
        a.lux(2, 0);
        for (long t = 1000; t < 60_000; t += 1000) a.lux(40, t);     // between 15 and 60: stays dark
        T.eq(Boolean.TRUE, a.dark(60_000, 0), "dark holds at 40 lux");
        AutoNight b = new AutoNight();
        b.lux(500, 0);
        for (long t = 1000; t < 60_000; t += 1000) b.lux(40, t);     // stays light
        T.eq(Boolean.FALSE, b.dark(60_000, 0), "light holds at 40 lux");
    }

    public static void sensorBeatsTheSun() {
        AutoNight a = new AutoNight();
        a.place(26.7, -80.0);
        long noon = Instant.parse("2026-11-24T17:00:00Z").toEpochMilli();       // midday in Florida
        T.eq(Boolean.FALSE, a.dark(0, noon), "sun only: day");
        a.lux(1, 0);
        T.eq(Boolean.TRUE, a.dark(0, noon), "sensor says dark even at noon");
    }

    public static void withoutASensorTheSunDecides() {
        AutoNight a = new AutoNight();
        a.place(26.7, -80.03);                                                    // Palm Beach
        T.eq(Boolean.FALSE, a.dark(0, Instant.parse("2026-11-24T17:00:00Z").toEpochMilli()), "midday");
        T.eq(Boolean.TRUE, a.dark(0, Instant.parse("2026-11-25T03:00:00Z").toEpochMilli()), "10 pm");
        T.eq(Boolean.TRUE, a.dark(0, Instant.parse("2026-11-24T09:00:00Z").toEpochMilli()), "4 am");
    }

    public static void sunsetAndSunriseAreRoughlyRight() {
        // Palm Beach, 24 Nov 2026: sunrise about 6:45 am EST (11:45Z), sunset about 5:30 pm EST (22:30Z)
        double lat = 26.7, lon = -80.03;
        T.ok(!SunTimes.isDark(lat, lon, Instant.parse("2026-11-24T22:15:00Z").toEpochMilli()), "5:15 pm still light");
        T.ok(SunTimes.isDark(lat, lon, Instant.parse("2026-11-24T22:50:00Z").toEpochMilli()), "5:50 pm dark");
        T.ok(SunTimes.isDark(lat, lon, Instant.parse("2026-11-24T11:25:00Z").toEpochMilli()), "6:25 am dark");
        T.ok(!SunTimes.isDark(lat, lon, Instant.parse("2026-11-24T12:05:00Z").toEpochMilli()), "7:05 am light");
        T.ok(SunTimes.elevationDeg(lat, lon, Instant.parse("2026-11-24T17:00:00Z").toEpochMilli()) > 35, "sun high at noon");
        // the far side of the world at the same moment is night
        T.ok(SunTimes.isDark(-26.7, 99.97, Instant.parse("2026-11-24T17:00:00Z").toEpochMilli()), "opposite side dark");
    }

    public static void modeLabels() {
        T.eq("Auto", NightHours.label("Auto"), "auto");
        T.eq("Always light", NightHours.label("Light"), "light");
        T.eq("Always dark", NightHours.label("Dark"), "dark");
        T.eq("Always light", NightHours.label(null), "unset reads light");
        T.eq("Dark", NightHours.fromLabel("Always dark"), "back");
        T.eq("Auto", NightHours.fromLabel("Auto"), "back auto");
        T.ok(NightHours.fromLabel("Purple") == null, "unknown");
    }
}
