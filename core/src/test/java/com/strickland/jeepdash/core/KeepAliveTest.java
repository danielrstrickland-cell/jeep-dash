package com.strickland.jeepdash.core;

/** What the link service does once the dash is closed and the Jeep has gone quiet. */
public class KeepAliveTest {
    private static final long LIMIT = 120000;

    public static void aScreenOrLiveDataKeepsEverythingGoing() {
        T.eq(KeepAlive.Action.KEEP, KeepAlive.decide(true, LIMIT * 5, LIMIT, true, false, false), "a screen is open");
        T.eq(KeepAlive.Action.KEEP, KeepAlive.decide(false, LIMIT - 1, LIMIT, true, false, false), "not idle for long enough yet");
    }

    public static void idleWithNoSoundWantedStopsAsBefore() {
        T.eq(KeepAlive.Action.STOP, KeepAlive.decide(false, LIMIT + 1, LIMIT, true, false, false), "let go of the dongle and stop");
    }

    public static void idleWithTheDspOnLetsGoOfTheDongleButStaysForTheSound() {
        T.eq(KeepAlive.Action.SOUND_ONLY, KeepAlive.decide(false, LIMIT + 1, LIMIT, true, false, true),
                "the DSP carries on over other apps; the dongle search does not");
        T.eq(KeepAlive.Action.KEEP, KeepAlive.decide(false, LIMIT * 50, LIMIT, false, true, true),
                "and it stays that way for as long as the sound is wanted");
    }

    public static void switchingTheDspOffLaterStopsTheService() {
        T.eq(KeepAlive.Action.STOP, KeepAlive.decide(false, LIMIT * 50, LIMIT, false, true, false), "nothing left to stay for");
    }

    public static void aServiceAlreadyStoppedIsLeftAlone() {
        T.eq(KeepAlive.Action.KEEP, KeepAlive.decide(false, LIMIT * 50, LIMIT, false, false, false), "nothing running, nothing to do");
        T.eq(KeepAlive.Action.KEEP, KeepAlive.decide(false, LIMIT * 50, LIMIT, false, false, true), "nor is it started from here");
    }
}
