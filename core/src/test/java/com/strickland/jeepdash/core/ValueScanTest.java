package com.strickland.jeepdash.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ValueScanTest {

    /** Runs the scan, answering each id with what the given rule says (null = no answer). */
    interface Answer { byte[] of(int id, int pass); }

    static ValueScan run(Answer a) {
        ValueScan s = new ValueScan();
        for (int id = s.next(); id >= 0; id = s.next()) s.record(id, a.of(id, s.pass()));
        return s;
    }

    static byte[] echo(int id, int value) { return new byte[]{(byte) id, (byte) value}; }

    public static void asksEveryIdOfTheTableThreeTimesInOrder() {
        ValueScan s = new ValueScan();
        T.eq(720, s.total(), "240 ids, three passes");
        List<Integer> asked = new ArrayList<>();
        for (int id = s.next(); id >= 0; id = s.next()) {
            asked.add(id);
            s.record(id, echo(id, 0));
        }
        T.eq(720, asked.size(), "every one asked");
        T.eq(0x00, asked.get(0), "starts at 00");
        T.eq(0xef, asked.get(239), "first pass ends at ef");
        T.eq(0x00, asked.get(240), "second pass starts again");
        T.eq(0xef, asked.get(719), "last");
        T.ok(s.finished() && !s.cancelled() && !s.stoppedSilent(), "finished");
        T.eq(720, s.done(), "all done");
    }

    public static void anIdIsAskedAgainUntilItsAnswerIsRecorded() {
        ValueScan s = new ValueScan();
        T.eq(0, s.next(), "first");
        T.eq(0, s.next(), "still the first: nothing recorded yet");
        s.record(0, null);
        T.eq(1, s.next(), "then the next");
        s.record(7, echo(7, 1));
        T.eq(1, s.next(), "an answer for another id does not move it on");
    }

    public static void progressSaysWhichPassAndHowFar() {
        ValueScan s = new ValueScan();
        T.eq("Pass 1 of 3: id 00 of ef (0%)", s.progress(), "start");
        for (int i = 0; i < 360; i++) {
            int id = s.next();
            s.record(id, echo(id, 0));
        }
        T.eq("Pass 2 of 3: id 78 of ef (50%)", s.progress(), "halfway");
    }

    public static void csvHasOneRowPerIdWithEachPasssAnswer() {
        ValueScan s = run((id, pass) -> id == 0x1c ? null : id == 0x18 ? echo(0x18, 0x7c + pass) : echo(id, 0));
        Map<Integer, String> known = ValueScan.knownAs(jeepParams());
        String[] lines = s.csv(known).split("\n");
        T.eq(241, lines.length, "a header and one row per id");
        T.eq("id,known_as,pass_1,pass_2,pass_3", lines[0], "header");
        T.eq("0x00,,00 00,00 00,00 00", lines[1], "an id the dash does not use");
        T.eq("0x0a,rpm (high byte),0a 00,0a 00,0a 00", lines[1 + 0x0a], "half of a two-id value");
        T.eq("0x18,map_kpa,18 7d,18 7e,18 7f", lines[1 + 0x18], "each pass's raw answer");
        T.eq("0x1c,,no answer,no answer,no answer", lines[1 + 0x1c], "no answer is said so");
        T.eq("0xef,,ef 00,ef 00,ef 00", lines[240], "last id");
    }

    public static void knownAsNamesTheDashsOwnValues() {
        Map<Integer, String> k = ValueScan.knownAs(jeepParams());
        T.eq("coolant", k.get(0x16), "one-id value");
        T.eq("rpm (low byte)", k.get(0x0b), "second id of a two-id value");
        T.eq("load", k.get(0xe3), "high id");
        T.eq(null, k.get(0x30), "not used by the dash");
    }

    public static void cancelStopsAtOnceAndKeepsWhatWasRead() {
        ValueScan s = new ValueScan();
        for (int i = 0; i < 245; i++) {
            int id = s.next();
            s.record(id, echo(id, 1));
        }
        s.cancel();
        T.eq(-1, s.next(), "nothing more to ask");
        T.ok(s.cancelled() && s.finished(), "cancelled");
        T.eq("Cancelled after 245 of 720 reads", s.progress(), "says so");
        String[] lines = s.csv(ValueScan.knownAs(jeepParams())).split("\n", -1);
        T.eq("0x03,,03 01,03 01,", lines[1 + 0x03], "read twice before the cancel");
        T.eq("0x20,,20 01,,", lines[1 + 0x20], "read once");
    }

    public static void stopsWhenTheEngineComputerGoesSilent() {
        ValueScan s = run((id, pass) -> pass == 1 && id < 0x40 ? echo(id, 0) : null);
        T.ok(s.stoppedSilent() && s.finished(), "stopped by itself");
        T.eq(0x40 + ValueScan.SILENT_IN_A_ROW, s.done(), "after that many ids in a row with no answer");
        T.ok(s.progress().startsWith("Stopped: the engine computer stopped answering"), s.progress());
    }

    public static void aFewSilentIdsDoNotStopIt() {
        ValueScan s = run((id, pass) -> id >= 0x80 && id < 0x80 + ValueScan.SILENT_IN_A_ROW - 1 ? null : echo(id, 0));
        T.ok(!s.stoppedSilent(), "a run of silent ids shorter than the limit");
        T.eq(720, s.done(), "all read");
    }

    private static List<Param> jeepParams() {
        try {
            Catalog.useVehicle("jeep");
            return Catalog.all();
        } finally {
            Catalog.useVehicle("jeep");
        }
    }
}
