package com.strickland.jeepdash.core;

/** The XP look's Light / Dark / Auto setting. */
public class NightHoursTest {

    public static void lightAndDarkIgnoreTheClock() {
        for (int h = 0; h < 24; h++) {
            T.ok(!NightHours.dark("Light", h), "light at " + h);
            T.ok(NightHours.dark("Dark", h), "dark at " + h);
        }
    }

    public static void autoIsDarkFromSevenInTheEveningToSevenInTheMorning() {
        T.ok(!NightHours.dark("Auto", 18), "6 pm is still light");
        T.ok(NightHours.dark("Auto", 19), "7 pm turns dark");
        T.ok(NightHours.dark("Auto", 23), "late evening");
        T.ok(NightHours.dark("Auto", 0), "midnight");
        T.ok(NightHours.dark("Auto", 6), "6 am is still dark");
        T.ok(!NightHours.dark("Auto", 7), "7 am turns light");
        T.ok(!NightHours.dark("Auto", 12), "midday");
    }

    public static void anythingUnknownStaysLight() {
        T.ok(!NightHours.dark(null, 22), "nothing saved yet: as before");
        T.ok(!NightHours.dark("Purple", 22), "an unknown setting: as before");
    }
}
