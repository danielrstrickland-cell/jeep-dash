package com.strickland.jeepdash.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reading and clearing the Jeep's stored codes. The replies are the ones the GearWrench app got from this
 * Jeep (captures of 2026-11-20 and 2026-11-22); the four names were on its Read DTC screen.
 */
public class SciCodesTest {

    static final String SIX = "10 05 96 7e 6d 6b 6a fe 69", NONE = "10 fe 0e";

    public static void theCapturedListOfSixCodesIsRead() {
        T.eq(Arrays.asList(0x05, 0x96, 0x7e, 0x6d, 0x6b, 0x6a), SciCodes.parse(Hex.bytes(SIX)), "six codes, in order");
    }

    public static void anEmptyListMeansNoCodes() {
        T.eq(new ArrayList<Integer>(), SciCodes.parse(Hex.bytes(NONE)), "no codes stored");
    }

    public static void aDamagedOrForeignReplyIsNotAList() {
        T.eq(null, SciCodes.parse(null), "no reply");
        T.eq(null, SciCodes.parse(Hex.bytes("10 05 96 fe 00")), "wrong checksum");
        T.eq(null, SciCodes.parse(Hex.bytes("17 e0 e0 e0")), "not a code list");
        T.eq(null, SciCodes.parse(Hex.bytes("10 05 96 ab")), "no end marker");
        T.eq(null, SciCodes.parse(Hex.bytes("10")), "too short");
    }

    public static void theFourCodesOnTheGearWrenchScreenAreNamed() {
        T.eq("P1682", SciCodes.describe(0x05).pcode, "charging voltage");
        T.eq("P0463", SciCodes.describe(0x96).pcode, "fuel level sender high");
        T.eq("P0138", SciCodes.describe(0x7e).pcode, "rear oxygen sensor high");
        T.eq("P0303", SciCodes.describe(0x6d).pcode, "cylinder 3 misfire");
        T.eq("P0301", SciCodes.describe(0x6b).pcode, "cylinder 1 misfire");
        T.eq("P0300", SciCodes.describe(0x6a).pcode, "misfire on several cylinders");
        T.ok(SciCodes.describe(0x6d).text.toLowerCase().contains("cylinder #3 misfire"), "plain name: " + SciCodes.describe(0x6d).text);
    }

    public static void aCodeWithoutAConfirmedPNumberShowsItsNameAndChryslerNumber() {
        SciCodes.Code c = SciCodes.describe(0x01);
        T.eq(null, c.pcode, "no P number is guessed");
        T.ok(c.text.toLowerCase().contains("cam signal"), "name from the list: " + c.text);
        T.eq("Chrysler code 01", c.label(), "shown by its Chrysler number");
        T.eq("P0303", SciCodes.describe(0x6d).label(), "a confirmed one shows its P number");
    }

    public static void aCodeThatIsNotInTheListStillShows() {
        SciCodes.Code c = SciCodes.describe(0xc0);
        T.eq("Chrysler code C0", c.label(), "raw number");
        T.ok(c.text.length() > 0, "says it is not in the list");
    }

    public static void theListReadsAsOneLinePerCode() {
        T.eq("P0303  Cylinder #3 misfire\nChrysler code 01  No cam signal at PCM",
                SciCodes.listing(Arrays.asList(0x6d, 0x01)), "label, then name");
        T.eq("No stored codes.", SciCodes.listing(new ArrayList<Integer>()), "empty list");
        T.eq("The engine computer did not answer. Turn the key on and try again.", SciCodes.listing(null), "no answer");
    }

    public static void everyOutcomeOfAClearSaysWhatHappened() {
        for (SciCodes.Outcome o : SciCodes.Outcome.values())
            T.ok(SciCodes.say(o).length() > 10, "a sentence for " + o);
        T.ok(SciCodes.say(SciCodes.Outcome.ENGINE_RUNNING).contains("engine"), "says to stop the engine");
        T.ok(SciCodes.say(SciCodes.Outcome.CLEARED).startsWith("Codes cleared"), "says it worked");
    }

    public static void aCodeCanBeLookedUpByItsNameAndTheJeep() {
        T.eq("P0303 Cylinder #3 misfire 1998 Jeep Grand Cherokee 5.9", SciCodes.searchQuery(0x6d), "P number and name");
        T.eq("No cam signal at PCM 1998 Jeep Grand Cherokee 5.9", SciCodes.searchQuery(0x01), "name alone when the P number is not confirmed");
        T.eq("Chrysler engine computer fault code C0 hex 1998 Jeep Grand Cherokee 5.9", SciCodes.searchQuery(0xc0),
                "a code the list does not know is searched by its number");
    }

    public static void theLookUpIsAGoogleSearchAddress() {
        String url = SciCodes.searchUrl(SciCodes.searchQuery(0x6d));
        T.ok(url.startsWith("https://www.google.com/search?q="), "google search: " + url);
        T.ok(url.contains("%233") && !url.contains("#") && !url.contains(" "), "the # and the spaces are escaped: " + url);
        T.ok(url.contains("P0303") && url.contains("Cherokee"), "carries the search words: " + url);
    }

    public static void clearingIsOffTheReadOnlyListAndOnlyTheTwoCapturedFramesAreWrites() {
        T.ok(!Protocols.allowed(Hex.bytes(SciCodes.CLEAR)), "clear is not a read");
        T.ok(!Protocols.allowed(Hex.bytes(SciCodes.CLEAR_ALL)), "erase all fault data is not a read");
        T.ok(Protocols.allowedWrite(Hex.bytes(SciCodes.CLEAR)), "clear is the one write");
        T.ok(Protocols.allowedWrite(Hex.bytes(SciCodes.CLEAR_ALL)), "with its second half");
        T.ok(Protocols.allowed(Hex.bytes(SciCodes.READ)), "reading codes is a read");
        for (String no : new String[]{"61 04 00 02 23 02 01", "61 04 00 02 23 03 01", "61 04 00 01 13 01",
                "61 04 00 01 18 01", "61 04 00 01 19 01", "61 04 00 01 1c 01", "61 04 00 02 13 01 01",
                "61 04 00 01 10 01", "61 04 00 02 f4 0a 01", "60 06 ff"})
            T.ok(!Protocols.allowedWrite(Hex.bytes(no)), "not a permitted write: " + no);
        T.ok(!Protocols.allowedWrite(null), "nothing");
    }

    static PollRunnerTest.Rig idling() throws Exception {
        PollRunnerTest.Rig r = new PollRunnerTest.Rig("rpm");
        r.steps(2);
        r.car.sent.clear();
        return r;
    }

    public static void readingCodesDropsToSlowSpeedAndAsksOnce() throws Exception {
        PollRunnerTest.Rig r = idling();
        r.car.codes = "10 05 96 7e 6d fe 94";
        T.eq(Arrays.asList(0x05, 0x96, 0x7e, 0x6d), r.runner.readCodes(), "the four codes");
        T.eq(Arrays.asList(Protocols.SCI_SLOW, SciCodes.READ), r.car.sent, "slow speed, then the request");
        r.car.sent.clear();
        r.runner.step();
        T.eq(Protocols.SCI_FAST, r.car.sent.get(0), "live data carries on at fast speed");
    }

    public static void readingCodesWhenTheEngineComputerIsSilentGivesNothing() throws Exception {
        PollRunnerTest.Rig r = idling();
        r.car.engineAnswers = false;
        T.eq(null, r.runner.readCodes(), "no list");
    }

    public static void readingCodesFromTheGenericLineSetsUpTheJeepLineAndHandsItBack() throws Exception {
        PollRunnerTest.Rig r = new PollRunnerTest.Rig("speed");
        r.steps(2);
        T.ok(r.runner.onGenericLine(), "on the generic line");
        r.car.sent.clear();
        r.car.codes = "10 6d fe 7b";
        T.eq(Arrays.asList(0x6d), r.runner.readCodes(), "read over the Jeep line");
        List<String> want = new ArrayList<>(Arrays.asList(Protocols.GENERIC_LEAVE));
        want.addAll(Arrays.asList(Protocols.SCI_SETUP));
        want.add(Protocols.SCI_SLOW);
        want.add(SciCodes.READ);
        T.eq(want, r.car.sent, "keep-alive stopped, Jeep line set up, then the read");
        T.eq(PollScheduler.Kind.SWITCH_TO_GENERIC, r.runner.step(), "live data sets its own line up again");
    }

    public static void clearingWithTheEngineStoppedSendsExactlyTheCapturedCommands() throws Exception {
        PollRunnerTest.Rig r = idling();
        r.car.rpmRaw = 0;
        r.car.codes = SIX;
        SciCodes.Cleared c = r.runner.clearCodes();
        T.eq(SciCodes.Outcome.CLEARED, c.outcome, "cleared");
        T.eq(new ArrayList<Integer>(), c.left, "nothing left afterwards");
        T.eq(Arrays.asList(Protocols.SCI_SLOW, SciCodes.READ, Protocols.SCI_FAST, "61 04 00 03 f4 0a 0b 01",
                Protocols.SCI_SLOW, SciCodes.CLEAR, Protocols.SCI_SLOW, SciCodes.CLEAR_ALL,
                Protocols.SCI_SLOW, SciCodes.READ), r.car.sent, "list, rpm check, clear, erase, read back");
    }

    public static void clearingIsRefusedWhileTheEngineRuns() throws Exception {
        PollRunnerTest.Rig r = idling();                    // 720 rpm
        r.car.codes = SIX;
        SciCodes.Cleared c = r.runner.clearCodes();
        T.eq(SciCodes.Outcome.ENGINE_RUNNING, c.outcome, "refused");
        T.ok(!r.car.sent.contains(SciCodes.CLEAR) && !r.car.sent.contains(SciCodes.CLEAR_ALL), "nothing was written");
    }

    public static void clearingIsRefusedWhenTheEngineSpeedCannotBeRead() throws Exception {
        PollRunnerTest.Rig r = idling();
        r.car.engineAnswers = false;
        SciCodes.Cleared c = r.runner.clearCodes();
        T.eq(SciCodes.Outcome.NOT_ANSWERING, c.outcome, "refused");
        T.ok(!r.car.sent.contains(SciCodes.CLEAR), "nothing was written");
    }

    public static void aRefusalFromTheEngineComputerIsReportedAndNothingMoreIsSent() throws Exception {
        PollRunnerTest.Rig r = idling();
        r.car.rpmRaw = 0;
        r.car.codes = SIX;
        r.car.clearReply = "17 00";
        SciCodes.Cleared c = r.runner.clearCodes();
        T.eq(SciCodes.Outcome.DENIED, c.outcome, "the computer said no");
        T.ok(!r.car.sent.contains(SciCodes.CLEAR_ALL), "the second half is not sent after a refusal");
        T.eq(6, c.left.size(), "the codes are still there");
    }

    public static void anUnclearAnswerToTheClearIsAFailure() throws Exception {
        PollRunnerTest.Rig r = idling();
        r.car.rpmRaw = 0;
        r.car.clearReply = "17 ff";
        T.eq(SciCodes.Outcome.FAILED, r.runner.clearCodes().outcome, "not confirmed");
    }

    public static void liveDataNeverSendsAClear() throws Exception {
        PollRunnerTest.Rig r = idling();
        r.car.rpmRaw = 0;
        r.runner.clearCodes();
        r.car.sent.clear();
        for (int i = 0; i < 200; i++) { r.now[0] += 300; r.runner.step(); }
        for (String s : r.car.sent) T.ok(Protocols.allowed(Hex.bytes(s)), "live data sent a write: " + s);
    }
}
