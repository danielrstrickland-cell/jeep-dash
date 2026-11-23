package com.strickland.jeepdash.core;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** Media volume that rises a few steps with road speed and comes back when it falls. */
public class SpeedVolumeTest {

    private static SpeedVolume at(SpeedVolume.Strength s) { return new SpeedVolume(s); }

    public static void nothingBelowTwentyMph() {
        SpeedVolume v = at(SpeedVolume.Strength.HIGH);
        T.eq(7, v.step(7, 15, 0.0, 0), "stopped");
        T.eq(7, v.step(7, 15, 19.9, 100), "just under 20");
    }

    public static void oneStepFromTwentyThenOnePerStrengthsSpacing() {
        SpeedVolume low = at(SpeedVolume.Strength.LOW);
        T.eq(6, low.step(5, 15, 20.0, 0), "low: first step at 20");
        T.eq(6, low.step(6, 15, 49.0, 100), "low: still one under 50");
        T.eq(7, low.step(6, 15, 50.0, 200), "low: two at 50");
        SpeedVolume med = at(SpeedVolume.Strength.MEDIUM);
        T.eq(7, med.step(5, 15, 40.0, 0), "medium: two at 40");
        T.eq(8, med.step(7, 15, 60.0, 100), "medium: three at 60");
        SpeedVolume high = at(SpeedVolume.Strength.HIGH);
        T.eq(8, high.step(5, 15, 44.0, 0), "high: three at 44");
    }

    public static void extraStepsAreCappedPerStrength() {
        T.eq(4, SpeedVolume.extra(SpeedVolume.Strength.LOW, 200), "low at most 4");
        T.eq(5, SpeedVolume.extra(SpeedVolume.Strength.MEDIUM, 200), "medium at most 5");
        T.eq(6, SpeedVolume.extra(SpeedVolume.Strength.HIGH, 200), "high at most 6");
        T.eq(0, SpeedVolume.extra(SpeedVolume.Strength.OFF, 200), "off adds nothing");
    }

    public static void neverAboveTheStreamMaximum() {
        SpeedVolume v = at(SpeedVolume.Strength.HIGH);
        T.eq(15, v.step(13, 15, 90.0, 0), "13 + 6 stops at 15");
        T.eq(13, v.step(15, 15, 0.0, 100), "and comes back to the owner's 13");
    }

    public static void hysteresisStopsItHuntingBetweenTwoSteps() {
        SpeedVolume v = at(SpeedVolume.Strength.MEDIUM);       // steps at 20, 40, 60...
        T.eq(7, v.step(5, 15, 41.0, 0), "two at 41");
        T.eq(7, v.step(7, 15, 39.0, 100), "39 holds two");
        T.eq(7, v.step(7, 15, 36.5, 200), "36.5 still holds");
        T.eq(6, v.step(7, 15, 35.9, 300), "4 mph under 40 drops one");
        T.eq(6, v.step(6, 15, 39.9, 400), "back up to 39.9: not yet");
        T.eq(7, v.step(6, 15, 40.0, 500), "40 again: up");
        T.eq(5, v.step(7, 15, 15.0, 600), "under 16: nothing");
    }

    public static void staleSpeedHoldsThenGoesBackToTheBaseAfterFiveSeconds() {
        SpeedVolume v = at(SpeedVolume.Strength.MEDIUM);
        T.eq(8, v.step(5, 15, 60.0, 0), "three at 60");
        T.eq(8, v.step(8, 15, null, 1000), "missing speed: hold");
        T.eq(8, v.step(8, 15, null, 4999), "still holding");
        T.eq(5, v.step(8, 15, null, 5000), "5 s without speed: back to the base");
        T.eq(5, v.step(5, 15, null, 9000), "and stays there");
    }

    public static void noSpeedEverIsTheBase() {
        SpeedVolume v = at(SpeedVolume.Strength.HIGH);
        T.eq(9, v.step(9, 15, null, 0), "never a reading");
    }

    public static void aHandChangeBecomesTheNewBase() {
        SpeedVolume v = at(SpeedVolume.Strength.MEDIUM);
        T.eq(8, v.step(5, 15, 60.0, 0), "base 5 + 3");
        T.eq(10, v.step(10, 15, 60.0, 100), "owner turned it up to 10: kept");
        T.eq(7, v.step(10, 15, 0.0, 200), "stopped: the owner's base is now 10 - 3");
        T.eq(7, v.base(), "base");
    }

    public static void aHandLevelStandsExactlyAtTheSpeedItWasSet() {
        SpeedVolume v = at(SpeedVolume.Strength.HIGH);
        T.eq(8, v.step(5, 15, 44.0, 0), "base 5 + 3");
        T.eq(1, v.step(1, 15, 44.0, 100), "owner turned it right down at speed: 1 stands");
        T.eq(1, v.step(1, 15, 45.0, 200), "and is not pushed back up");
        T.eq(0, v.step(1, 15, 0.0, 300), "slowing takes the 3 steps off: never below 0");
        T.eq(0, v.base(), "base held at 0");
        T.eq(1, v.step(0, 15, 44.0, 400), "back up to speed: the 3 steps on again, from below 0");
    }

    public static void aHandLevelAtTheTopStandsToo() {
        SpeedVolume v = at(SpeedVolume.Strength.LOW);
        T.eq(6, v.step(5, 15, 20.0, 0), "one step");
        T.eq(15, v.step(15, 15, 20.0, 100), "owner turned it right up: 15 stands");
        T.eq(15, v.step(15, 15, 80.0, 200), "more speed: still never above the top");
        T.eq(14, v.step(15, 15, 0.0, 300), "stopped: one step off the level he set");
    }

    public static void turnedToNothingStaysNothingUntilTheOwnerRaisesIt() {
        SpeedVolume v = at(SpeedVolume.Strength.MEDIUM);
        T.eq(8, v.step(5, 15, 60.0, 0), "raised");
        T.eq(0, v.step(0, 15, 60.0, 100), "owner turned it off");
        T.eq(0, v.step(0, 15, 90.0, 200), "faster: still silent");
        T.eq(0, v.step(0, 15, 0.0, 300), "stopped: silent");
        T.eq(0, v.release(0), "released: silent");
        T.eq(0, v.step(0, 15, 60.0, 400), "starts again from silent: speed adds nothing to nothing");
        T.eq(4, v.step(4, 15, 60.0, 500), "owner raises it: 4 stands");
        T.eq(5, v.step(4, 15, 80.0, 600), "and speed works from there again");
    }

    public static void releaseGivesBackTheOwnersLevel() {
        SpeedVolume v = at(SpeedVolume.Strength.MEDIUM);
        T.eq(null, v.release(5), "never set anything: leave it");
        T.eq(8, v.step(5, 15, 60.0, 0), "raised");
        T.eq(5, v.release(8), "link dropped or turned off: back to 5");
        T.eq(null, v.release(5), "only once");
        T.eq(6, v.step(6, 15, 0.0, 100), "starts again from the level it finds");
    }

    public static void releaseAfterAHandChangeKeepsTheOwnersChoice() {
        SpeedVolume v = at(SpeedVolume.Strength.MEDIUM);
        T.eq(8, v.step(5, 15, 60.0, 0), "raised");
        T.eq(9, v.release(12), "owner set 12 at speed: their base is 12 - 3");
    }

    public static void strengthNamesRoundTrip() {
        for (SpeedVolume.Strength s : SpeedVolume.Strength.values())
            T.eq(s, SpeedVolume.Strength.of(s.name()), s.name());
        T.eq(SpeedVolume.Strength.OFF, SpeedVolume.Strength.of(null), "nothing saved: off");
        T.eq(SpeedVolume.Strength.OFF, SpeedVolume.Strength.of("LOUD"), "unknown: off");
    }

    public static void speedIsReadWhileOn() {
        Set<String> shown = new LinkedHashSet<>(Arrays.asList("rpm"));
        T.eq(new LinkedHashSet<>(Arrays.asList("rpm")), SpeedVolume.withSpeed(shown, SpeedVolume.Strength.OFF), "off: as before");
        T.eq(new LinkedHashSet<>(Arrays.asList("rpm", "speed")), SpeedVolume.withSpeed(shown, SpeedVolume.Strength.LOW), "on: speed added");
        T.eq(1, shown.size(), "the given set is not changed");
    }
}
