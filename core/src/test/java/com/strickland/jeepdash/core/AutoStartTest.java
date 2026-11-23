package com.strickland.jeepdash.core;

/** Opening the dash when the tablet connects to the chosen Bluetooth device (the stereo). */
public class AutoStartTest {

    public static void offIsNothing() {
        T.eq(AutoStart.Action.NOTHING, AutoStart.decide(null, "stereo", false, true), "nothing chosen");
        T.eq(AutoStart.Action.NOTHING, AutoStart.decide("", "stereo", false, true), "empty choice");
    }

    public static void anotherDeviceIsNothing() {
        T.eq(AutoStart.Action.NOTHING, AutoStart.decide("stereo", "phone", false, true), "not the chosen one");
        T.eq(AutoStart.Action.NOTHING, AutoStart.decide("stereo", null, false, true), "no device in the news");
    }

    public static void theChosenDeviceOpensTheDashWhenAllowed() {
        T.eq(AutoStart.Action.OPEN, AutoStart.decide("stereo", "stereo", false, true), "may draw over others");
        T.eq(AutoStart.Action.OPEN, AutoStart.decide("Stereo", "stereo", false, true), "case does not matter");
    }

    public static void withoutDrawOverOthersItAsksWithANotification() {
        T.eq(AutoStart.Action.NOTIFY, AutoStart.decide("stereo", "stereo", false, false), "notify instead");
    }

    public static void alreadyInFrontIsNothing() {
        T.eq(AutoStart.Action.NOTHING, AutoStart.decide("stereo", "stereo", true, true), "in front already");
        T.eq(AutoStart.Action.NOTHING, AutoStart.decide("stereo", "stereo", true, false), "no notification either");
    }

    public static void theDongleIsNeverOffered() {
        T.ok(!AutoStart.offer("dongle", "dongle"), "the dongle the link remembers");
        T.ok(!AutoStart.offer("DONGLE", "dongle"), "case does not matter");
        T.ok(AutoStart.offer("stereo", "dongle"), "anything else");
        T.ok(AutoStart.offer("stereo", null), "dongle not known yet: list everything");
        T.ok(!AutoStart.offer(null, null), "a device with no key cannot be chosen");
    }
}
