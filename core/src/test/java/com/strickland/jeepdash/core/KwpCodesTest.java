package com.strickland.jeepdash.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Stored codes on the Cayenne. The frames are the GearWrench app's Read DTC and Clear DTC on this car (2026-11-22). */
public class KwpCodesTest {
    static final String READ = "61 04 00 08 84 10 f1 18 00 ff 00 9c ff", CLEAR = "61 04 00 07 83 10 f1 14 ff 00 97 01";
    static final String STOPPED = KwpTest.withSum("9a f1 10 61 0a 01 c8 00 15 46 c2 07 7d 00 14 64 7e 05 32 68 19 12 07 05 32 82 21 85 1d");

    static KwpTest.Rig stopped() {
        KwpTest.Rig r = new KwpTest.Rig("rpm");
        r.car.replies.put(0x0a, STOPPED);
        return r;
    }

    public static void theFramesAreTheCapturedOnes() {
        T.eq(READ, KwpCodes.READ, "Read DTC");
        T.eq(CLEAR, KwpCodes.CLEAR, "Clear DTC");
    }

    public static void theCapturedListHoldsP1525() {
        T.eq(Arrays.asList(0x1525), KwpCodes.parse(Hex.bytes("85 f1 10 58 01 15 25 34 4d")), "one code");
        T.eq("P1525", KwpCodes.label(0x1525), "as the vendor app showed it");
        T.eq("Cylinder bank 1, camshaft adjustment, electrical circuit fault", KwpCodes.text(0x1525), "the vendor app's wording");
        T.eq("P1525  Cylinder bank 1, camshaft adjustment, electrical circuit fault", KwpCodes.listing(Arrays.asList(0x1525)), "one line");
    }

    public static void anEmptyListAndSeveralCodes() {
        T.eq(Collections.emptyList(), KwpCodes.parse(Hex.bytes("82 f1 10 58 00 db")), "nothing stored");
        T.eq("No stored codes.", KwpCodes.listing(Collections.<Integer>emptyList()), "said plainly");
        T.eq(Arrays.asList(0x0301, 0x1525), KwpCodes.parse(Hex.bytes(KwpTest.withSum("88 f1 10 58 02 03 01 24 15 25 34"))), "two codes, in order");
        // a long list comes with its length in a byte of its own
        StringBuilder sb = new StringBuilder("80 f1 10 44 58 16");
        for (int i = 1; i <= 22; i++) sb.append(String.format(" 03 %02x 24", i));
        List<Integer> many = KwpCodes.parse(Hex.bytes(KwpTest.withSum(sb.toString())));
        T.ok(many != null && many.size() == 22 && many.get(21) == 0x0316, "twenty-two codes");
    }

    public static void anythingElseIsNotAList() {
        T.ok(KwpCodes.parse(null) == null, "no answer");
        T.ok(KwpCodes.parse(Hex.bytes("85 f1 10 58 01 15 25 34 4e")) == null, "checksum does not add up");
        T.ok(KwpCodes.parse(Hex.bytes(KwpTest.withSum("83 f1 10 7f 18 11"))) == null, "the computer said no");
        T.ok(KwpCodes.parse(Hex.bytes(KwpTest.withSum("85 f1 10 58 02 15 25 34"))) == null, "says two codes, holds one");
        T.ok(KwpCodes.parse(Hex.bytes(KwpTest.withSum("85 f1 11 58 01 15 25 34"))) == null, "another computer");
        T.eq("The engine computer did not answer. Turn the key on and try again.", KwpCodes.listing(null), "said plainly");
    }

    public static void codeLettersFollowTheStandard() {
        T.eq("P0301", KwpCodes.label(0x0301), "powertrain");
        T.eq("C0123", KwpCodes.label(0x4123), "chassis");
        T.eq("B1A00", KwpCodes.label(0x9a00), "body");
        T.eq("U0100", KwpCodes.label(0xc100), "network");
        T.eq("P0301  Tap to look it up", KwpCodes.listing(Arrays.asList(0x0301)), "no name in the dash: the number and a way to find it");
    }

    public static void lookingACodeUpNamesTheCar() {
        T.eq("P1525 Cylinder bank 1, camshaft adjustment, electrical circuit fault 2003 Porsche Cayenne Turbo", KwpCodes.searchQuery(0x1525), "named code");
        T.eq("P0301 2003 Porsche Cayenne Turbo", KwpCodes.searchQuery(0x0301), "number only");
    }

    public static void theCodesScreenSpeaksForWhicheverCarIsChosen() {
        T.eq("P0463  " + SciCodes.describe(0x96).text, CodeText.listing(Arrays.asList(0x96)), "the Jeep's list");
        T.eq(SciCodes.searchQuery(0x96), CodeText.searchQuery(0x96), "the Jeep's search");
        try {
            Catalog.useVehicle("porsche");
            T.eq(KwpCodes.listing(Arrays.asList(0x1525)), CodeText.listing(Arrays.asList(0x1525)), "the Porsche's list");
            T.eq(KwpCodes.searchQuery(0x1525), CodeText.searchQuery(0x1525), "the Porsche's search");
        } finally {
            Catalog.useVehicle("jeep");
        }
    }

    public static void readingAsksExactlyWhatTheVendorAppAsked() throws Exception {
        KwpTest.Rig r = new KwpTest.Rig("rpm");
        r.steps(2);
        T.eq(Arrays.asList(0x1525), r.runner.readCodes(), "the stored code");
        T.eq(READ, r.car.sent.get(r.car.sent.size() - 1), "the captured request, nothing after it");
        T.eq(KwpRunner.Kind.READ, r.runner.step(), "live data carries on");
        for (String s : r.car.sent) T.ok(Protocols.allowed(Hex.bytes(s)), "all of it is on the read-only list: " + s);
    }

    public static void readingWakesTheLineWhenNothingIsOnScreenYet() throws Exception {
        KwpTest.Rig r = new KwpTest.Rig();
        T.eq(Arrays.asList(0x1525), r.runner.readCodes(), "read straight after connecting");
        T.ok(r.car.sent.contains(Protocols.KWP_INIT), "the line was woken first");
        r.car.keyOn = false;
        KwpTest.Rig off = new KwpTest.Rig();
        off.car.keyOn = false;
        T.ok(off.runner.readCodes() == null, "key off: no list");
        T.ok(!off.car.sent.contains(READ), "and nothing asked of a sleeping computer");
    }

    public static void clearingWithTheEngineStoppedSendsTheOneCapturedFrame() throws Exception {
        KwpTest.Rig r = stopped();
        r.steps(2);
        int before = r.car.sent.size();
        SciCodes.Cleared c = r.runner.clearCodes();
        T.eq(SciCodes.Outcome.CLEARED, c.outcome, "the computer confirmed");
        T.eq(Collections.emptyList(), c.left, "read back: nothing stored");
        T.eq(Arrays.asList(READ, Hex.str(KwpBlocks.request(0x0a)), CLEAR, READ), r.car.sent.subList(before, r.car.sent.size()),
                "list, engine speed, the clear, list again");
        T.eq(KwpRunner.Kind.READ, r.runner.step(), "live data carries on");
    }

    public static void nothingIsClearedWhileTheEngineTurns() throws Exception {
        KwpTest.Rig r = new KwpTest.Rig("rpm");           // the idle capture: 920 rpm
        r.steps(2);
        SciCodes.Cleared c = r.runner.clearCodes();
        T.eq(SciCodes.Outcome.ENGINE_RUNNING, c.outcome, "refused by the dash itself");
        T.eq(Arrays.asList(0x1525), c.left, "the list is unchanged");
        T.ok(!r.car.sent.contains(CLEAR), "the clear was never sent");
    }

    public static void nothingIsClearedWhenTheEngineSpeedCannotBeRead() throws Exception {
        KwpTest.Rig r = stopped();
        r.steps(2);
        r.car.replies.remove(0x0a);
        T.eq(SciCodes.Outcome.NOT_ANSWERING, r.runner.clearCodes().outcome, "a stopped engine is not certain");
        T.ok(!r.car.sent.contains(CLEAR), "the clear was never sent");
        KwpTest.Rig off = stopped();
        off.car.keyOn = false;
        SciCodes.Cleared c = off.runner.clearCodes();
        T.ok(c.outcome == SciCodes.Outcome.NOT_ANSWERING && c.left == null, "key off");
        T.ok(!off.car.sent.contains(CLEAR), "nothing sent");
    }

    public static void aRefusalOrSilenceIsReportedAsSuch() throws Exception {
        KwpTest.Rig r = stopped();
        r.steps(2);
        r.car.clearReply = KwpTest.withSum("83 f1 10 7f 14 22");
        SciCodes.Cleared c = r.runner.clearCodes();
        T.eq(SciCodes.Outcome.DENIED, c.outcome, "the computer refused");
        T.eq(Arrays.asList(0x1525), c.left, "still stored");
        r.car.clearReply = null;
        T.eq(SciCodes.Outcome.FAILED, r.runner.clearCodes().outcome, "no confirmation");
    }

    public static void onlyTheCapturedClearMayBeWritten() {
        T.ok(Protocols.allowed(Hex.bytes(READ)), "reading the list is a read");
        T.ok(!Protocols.allowed(Hex.bytes(CLEAR)), "live data can never send the clear");
        T.ok(Protocols.allowedWrite(Hex.bytes(CLEAR)), "the Codes screen can");
        T.ok(Protocols.allowedWrite(Hex.bytes(SciCodes.CLEAR)), "the Jeep's clear is still allowed");
        String[] refused = {
                KwpTest.withSum("61 04 00 07 83 10 f1 14 ff 01") + " 01",      // another group of codes
                KwpTest.withSum("61 04 00 07 83 11 f1 14 ff 00") + " 01",      // another computer
                KwpTest.withSum("61 04 00 07 83 10 f1 30 01 07") + " 01",      // drive an output
                KwpTest.withSum("61 04 00 07 83 10 f1 3b 0a 00") + " 01",      // write a value
                KwpTest.withSum("61 04 00 06 82 10 f1 31 01") + " 01",         // start a routine
                KwpTest.withSum("61 04 00 06 82 10 f1 11 01") + " 01",         // reset the computer
                READ,
        };
        for (String s : refused) T.ok(!Protocols.allowedWrite(Hex.bytes(s)), "refused: " + s);
        String[] notReads = {
                KwpTest.withSum("61 04 00 08 84 10 f1 18 02 ff 00") + " ff",   // a differently worded list request
                "61 04 00 08 84 10 f1 18 00 ff 00 9c 01",
        };
        for (String s : notReads) T.ok(!Protocols.allowed(Hex.bytes(s)), "not on the read list: " + s);
    }
}
