package com.strickland.jeepdash.core;

import java.util.Arrays;

/**
 * The experimental fast refresh on the Jeep line: a shorter wait in the dongle before it hands an answer
 * back. A dongle setting only; the GearWrench app never uses it, so the runner watches for misreads and
 * goes back to the captured wait on its own.
 */
public class FastRefreshTest {

    static final String READ_RPM = "61 04 00 03 f4 0a 0b 01";

    /** A rig with fast refresh asked for and enough good reads behind it for the setting to be in use. */
    static PollRunnerTest.Rig fast() throws Exception {
        PollRunnerTest.Rig r = new PollRunnerTest.Rig("rpm");
        r.runner.setFastRefresh(true);
        r.steps(5);                                  // line setup, three good reads, then the fourth applies it
        T.ok(r.car.fastTiming, "fast wait in use");
        r.car.sent.clear();
        return r;
    }

    public static void offUnlessAskedFor() throws Exception {
        PollRunnerTest.Rig r = new PollRunnerTest.Rig("rpm");
        r.steps(30);
        T.ok(!r.car.sent.contains(Protocols.SCI_TIMING_FAST), "the shorter wait is never sent");
        T.eq(PollRunner.Fast.OFF, r.runner.fastRefresh(), "off");
    }

    public static void theShorterWaitIsADongleSettingOnTheReadList() {
        T.ok(Protocols.allowed(Hex.bytes(Protocols.SCI_TIMING_FAST)), "allowed");
        T.ok(Protocols.allowed(Hex.bytes(Protocols.SCI_TIMING)), "and the captured wait to go back to");
        T.ok(Protocols.SCI_TIMING_FAST.startsWith("60 03 "), "a dongle timing command, not a message to the Jeep");
        T.ok(Arrays.asList(Protocols.SCI_SETUP).contains(Protocols.SCI_TIMING), "the captured wait is the one in the line setup");
        T.ok(!Protocols.wantsReply(Hex.bytes(Protocols.SCI_TIMING_FAST)), "only acked");
    }

    public static void itStartsOnlyAfterThreeGoodReadsAtTheNormalWait() throws Exception {
        PollRunnerTest.Rig r = new PollRunnerTest.Rig("rpm");
        r.runner.setFastRefresh(true);
        r.steps(4);
        T.ok(!r.car.sent.contains(Protocols.SCI_TIMING_FAST), "not during setup or the first three reads");
        r.car.sent.clear();
        r.steps(1);
        T.eq(Arrays.asList(Protocols.SCI_TIMING_FAST, Protocols.SCI_FAST, READ_RPM), r.car.sent, "then set once, before a read");
        r.car.sent.clear();
        r.steps(5);
        T.ok(!r.car.sent.contains(Protocols.SCI_TIMING_FAST), "and not sent again");
        T.eq(PollRunner.Fast.ON, r.runner.fastRefresh(), "on");
    }

    public static void turningItOffPutsTheCapturedWaitBack() throws Exception {
        PollRunnerTest.Rig r = fast();
        r.runner.setFastRefresh(false);
        r.steps(1);
        T.eq(Arrays.asList(Protocols.SCI_TIMING, Protocols.SCI_FAST, READ_RPM), r.car.sent, "back to the captured wait");
        T.eq(PollRunner.Fast.OFF, r.runner.fastRefresh(), "off");
    }

    public static void misreadsThatStopAtTheNormalWaitTurnItOffForGood() throws Exception {
        PollRunnerTest.Rig r = fast();
        r.car.fastBreaksReads = true;                // every read fails while the shorter wait is set
        r.steps(3);
        T.ok(!r.car.fastTiming, "back at the captured wait after two misses in a row");
        T.eq(PollRunner.Fast.BACKED_OFF, r.runner.fastRefresh(), "and the next good read settles it");
        r.car.sent.clear();
        r.steps(40);
        T.ok(!r.car.sent.contains(Protocols.SCI_TIMING_FAST), "it is not tried again on its own");
        T.near(720, r.values.get("rpm", 0), 0.001, "readings are back");
    }

    public static void scatteredMisreadsTurnItOffToo() throws Exception {
        PollRunnerTest.Rig r = fast();
        r.car.fastDropsEvery = 3;                    // one read in three fails at the shorter wait
        r.steps(40);
        T.eq(PollRunner.Fast.BACKED_OFF, r.runner.fastRefresh(), "too many misses for the reads made");
        T.ok(!r.car.fastTiming, "back at the captured wait");
    }

    public static void aKeyTurnedOffIsNotBlamedOnIt() throws Exception {
        PollRunnerTest.Rig r = fast();
        r.car.engineAnswers = false;
        r.steps(30);
        T.ok(!r.car.fastTiming, "the captured wait while nothing answers");
        r.car.engineAnswers = true;
        r.steps(12);
        T.eq(PollRunner.Fast.ON, r.runner.fastRefresh(), "still on");
        T.ok(r.car.fastTiming, "and in use again once reads are coming back");
    }

    public static void askingAgainAfterItBackedOffGivesItAnotherTry() throws Exception {
        PollRunnerTest.Rig r = fast();
        r.car.fastBreaksReads = true;
        r.steps(3);
        r.car.fastBreaksReads = false;
        r.runner.setFastRefresh(true);
        r.steps(5);
        T.eq(PollRunner.Fast.ON, r.runner.fastRefresh(), "on again");
        T.ok(r.car.fastTiming, "in use");
    }

    public static void codesAreReadAndClearedAtTheCapturedWait() throws Exception {
        PollRunnerTest.Rig r = fast();
        r.runner.readCodes();
        T.eq(Arrays.asList(Protocols.SCI_TIMING, Protocols.SCI_SLOW, SciCodes.READ), r.car.sent, "captured wait first");
        r.car.sent.clear();
        r.steps(1);
        T.eq(Protocols.SCI_TIMING_FAST, r.car.sent.get(0), "fast again for live data");
        r.car.rpmRaw = 0;
        r.car.sent.clear();
        r.runner.clearCodes();
        int clear = r.car.sent.indexOf(SciCodes.CLEAR);
        T.ok(clear > 0 && !r.car.sent.subList(0, clear).contains(Protocols.SCI_TIMING_FAST)
                && r.car.sent.get(0).equals(Protocols.SCI_TIMING), "the clear goes out at the captured wait: " + r.car.sent);
    }

    public static void everythingSentWithItOnIsOnTheReadOnlyList() throws Exception {
        PollRunnerTest.Rig r = fast();
        r.car.fastDropsEvery = 3;
        for (int i = 0; i < 200; i++) { r.now[0] += 300; r.runner.step(); }
        for (String s : r.car.sent) T.ok(Protocols.allowed(Hex.bytes(s)), "off the read-only list: " + s);
    }
}
