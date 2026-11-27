package com.strickland.jeepdash.core;

import java.util.List;

/** The sums behind the stereo tuning wizard. */
public class TuneWizardTest {
    static final double NAN = Double.NaN;

    public static void distancesAreEachSpeakersDifferenceFromTheFarthest() {
        // arrival times in ms: front left 3.0, front right 4.0, rear left 6.0, rear right 6.0, sub 5.0
        double[] d = TuneWizard.distances(new double[]{3.0, 4.0, 6.0, 6.0, 5.0});
        T.ok(Math.abs(d[0] - 3.375) < 0.011, "front left is 3 ms nearer than the rears: " + d[0]);
        T.ok(Math.abs(d[1] - 2.25) < 0.011, "front right: " + d[1]);
        T.eq(0.0, d[2], "the farthest is 0");
        T.eq(0.0, d[3], "a tie for farthest is 0 too");
        T.ok(Math.abs(d[4] - 1.13) < 0.011, "sub: " + d[4]);
    }

    public static void distancesStayInTheStereosRangeAndSkipUnmeasured() {
        double[] d = TuneWizard.distances(new double[]{0.0, NAN, 40.0, 40.0, 40.0});
        T.ok(d[0] <= KenwoodManual.DTA_MAX_FEET, "never past 20.01 ft: " + d[0]);
        T.eq(0.0, d[1], "an unmeasured speaker is left at 0");
        T.eq(0.0, TuneWizard.distances(new double[]{NAN, NAN, NAN, NAN, NAN})[0], "nothing measured, nothing proposed");
    }

    public static void crossoverAndSlopeComeFromTheStereosOwnLists() {
        T.eq(80, TuneWizard.nearestCrossover(78), "78 Hz goes to 80");
        T.eq(30, TuneWizard.nearestCrossover(5), "below the list goes to 30");
        T.eq(250, TuneWizard.nearestCrossover(900), "above the list goes to 250");
        T.eq(100, TuneWizard.nearestCrossover(105), "105 Hz goes to 100");
        T.eq(-12, TuneWizard.nearestSlope(-11), "-11 dB goes to -12");
        T.eq(-24, TuneWizard.nearestSlope(-40), "steeper than the stereo has goes to -24");
        T.eq(-6, TuneWizard.nearestSlope(0), "flatter goes to -6");
    }

    public static void subPhaseOnlyFlipsOnAClearDifference() {
        T.ok(TuneWizard.reversePhase(62, 67), "5 dB louder reversed: reverse it");
        T.ok(!TuneWizard.reversePhase(62, 62.5), "within 1 dB: leave it");
        T.ok(!TuneWizard.reversePhase(67, 62), "louder normal: leave it");
        T.ok(!TuneWizard.reversePhase(NAN, 62), "no measurement: leave it");
    }

    public static void thePlanListsOnlyWhatChangesAndKeepsTheSubLevelInRange() {
        double[] now = {0, 0, 0, 0, 0}, wanted = {0, 1.4, 3.1, 3.1, 0};
        List<TuneWizard.Change> c = TuneWizard.plan(now, wanted, false, true, 0, -4);
        T.eq(5, c.size(), "two distances, rear right, phase, level: " + c);
        T.ok(c.toString().contains("FR distance: 0.00 ft to 1.40 ft"), c.toString());
        T.ok(c.toString().contains("Sub phase: Normal (0) to Reverse (180)"), c.toString());
        T.ok(c.toString().contains("Sub level: 0 to -4"), c.toString());
        T.eq(0, TuneWizard.plan(now, now, false, false, 0, 0).size(), "nothing changed, nothing listed");
        List<TuneWizard.Change> far = TuneWizard.plan(now, now, false, false, 0, -90);
        T.ok(far.get(0).to.equals("-50"), "a sub level past the stereo's range is held at -50");
    }

    public static void theVolumeNeverGoesUpForAChange() {
        T.eq(6, TuneWizard.safeVolume(20), "from high: drop to 6");
        T.eq(3, TuneWizard.safeVolume(3), "from low: stay");
        T.eq(0, TuneWizard.safeVolume(-2), "never below 0");
    }
}
