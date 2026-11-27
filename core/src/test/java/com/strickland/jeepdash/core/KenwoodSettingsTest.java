package com.strickland.jeepdash.core;

import java.util.List;

/** The stereo's sound settings as the stock app reads and writes them, the tune backup, and the write test. */
public class KenwoodSettingsTest {

    static byte[] list(String... names) {
        byte[] p = new byte[1 + names.length * 7];
        p[0] = (byte) names.length;
        for (int i = 0; i < names.length; i++) {
            byte[] n = String.format("%-7s", names[i]).getBytes();
            System.arraycopy(n, 0, p, 1 + i * 7, 7);
        }
        return p;
    }

    public static void theReadsAreOnlyReads() {
        List<KenwoodSettings.Read> reads = KenwoodSettings.reads();
        T.ok(reads.size() >= 15, "the whole tune is asked for");
        T.eq(0x50, reads.get(0).cmd, "starting as the stock app's settings menu does, with 3D/50");
        for (KenwoodSettings.Read r : reads) {
            T.ok(!KenwoodSettings.isWrite(r.cmd), r.label + " is not a write");
        }
        for (int w : new int[]{0x05, 0x09, 0x0C, 0x0F, 0x41, 0x44, 0x4A, 0x4D, 0x4F, 0x55, 0x56, 0x59, 0x5D, 0x5E})
            T.ok(KenwoodSettings.isWrite(w), String.format("3D/%02X is a write", w));
    }

    public static void anOptionListIsACountAndSevenLetterNames() {
        T.eq(java.util.Arrays.asList("OFF", "LV1", "LV2"), KenwoodSettings.options(list("OFF", "LV1", "LV2")),
                "names trimmed, in the stereo's order");
        T.eq(0, KenwoodSettings.options(new byte[0]).size(), "nothing from nothing");
        T.eq(0, KenwoodSettings.options(new byte[]{5, 'A'}).size(), "nor from a list cut short");
    }

    public static void theBackupKeepsEveryReplyAndReadsBack() {
        KenwoodSettings.Backup b = new KenwoodSettings.Backup();
        b.add(0x4B, list("OFF", "LV1", "LV2"));
        b.add(0x4C, new byte[]{1});
        b.add(0x4E, new byte[]{(byte) 0x83});
        T.eq(3, b.size(), "three settings");
        String text = b.toText("2026-11-26 20:00", "KMM-BT728HD");
        T.ok(text.contains("KMM-BT728HD"), "names the stereo");
        T.ok(text.contains("LV1"), "and decodes what it can: loudness is LV1");
        KenwoodSettings.Backup again = KenwoodSettings.Backup.fromText(text);
        T.eq(3, again.size(), "read back whole");
        T.eq(new byte[]{1}, again.get(0x4C), "byte for byte");
        T.eq(new byte[]{(byte) 0x83}, again.get(0x4E), "including the top bit");
        T.ok(KenwoodSettings.Backup.fromText("junk") .size() == 0, "junk reads as an empty backup");
    }

    public static void subLevelIsSignAndSize() {
        T.eq(-3, KenwoodSettings.subLevel((byte) 0x83), "top bit is the minus sign, as the stock app writes it");
        T.eq(5, KenwoodSettings.subLevel((byte) 5), "plain for zero and up");
        T.eq((byte) 0x83, KenwoodSettings.subLevelByte(-3), "and back");
        T.eq((byte) 10, KenwoodSettings.subLevelByte(10), "the top of the stereo's range");
    }

    public static void theWriteTestChangesOneSettingChecksItAndPutsItBack() {
        KenwoodSettings.WriteTest w = KenwoodSettings.WriteTest.loudness();
        KenwoodSettings.Msg m = w.next();
        T.eq(0x4B, m.cmd, "first the list of choices");
        T.eq(new byte[]{0}, m.payload, "asked for as a read");
        T.ok(w.next() == null, "one question at a time");
        w.onReply(0x4B, list("OFF", "LV1", "LV2"));
        T.eq(0x4C, w.next().cmd, "then what it is set to now");
        w.onReply(0x4C, new byte[]{0});
        KenwoodSettings.Msg write = w.next();
        T.eq(0x4D, write.cmd, "then the write");
        T.eq(new byte[]{1}, write.payload, "to the next choice: LV1");
        KenwoodSettings.Msg back;
        while ((back = w.next()) == null) w.tick();
        T.eq(0x4C, back.cmd, "read it back");
        w.onReply(0x4C, new byte[]{1});
        KenwoodSettings.Msg restore = w.next();
        T.eq(0x4D, restore.cmd, "then put it back");
        T.eq(new byte[]{0}, restore.payload, "to what it was: OFF");
        KenwoodSettings.Msg check;
        while ((check = w.next()) == null) w.tick();
        T.eq(0x4C, check.cmd, "and check that too");
        w.onReply(0x4C, new byte[]{0});
        T.ok(w.done(), "finished");
        T.ok(w.worked(), "the stereo took the write and the restore");
        T.ok(w.result().contains("LV1") && w.result().contains("OFF"), "the result says what it did: " + w.result());
    }

    public static void aWriteTheStereoIgnoresIsReportedAndNothingElseIsWritten() {
        KenwoodSettings.WriteTest w = KenwoodSettings.WriteTest.loudness();
        w.next();
        w.onReply(0x4B, list("OFF", "LV1"));
        w.next();
        w.onReply(0x4C, new byte[]{1});
        T.eq(new byte[]{0}, w.next().payload, "from LV1 the other choice is OFF");
        KenwoodSettings.Msg back;
        while ((back = w.next()) == null) w.tick();
        w.onReply(0x4C, new byte[]{1});                                  // still LV1: the write did nothing
        T.ok(w.done(), "nothing to put back");
        T.ok(!w.worked(), "and it did not work");
        T.ok(w.result().contains("did not change"), w.result());
    }

    public static void noAnswerToTheFirstQuestionStopsTheTest() {
        KenwoodSettings.WriteTest w = KenwoodSettings.WriteTest.loudness();
        w.next();
        for (int i = 0; i < KenwoodSettings.WriteTest.PATIENCE; i++) w.tick();
        T.ok(w.done() && !w.worked(), "gives up without writing anything");
        T.ok(w.result().contains("no answer"), w.result());
    }

    public static void theManualRangesLabelTheBackup() {
        T.eq("62.5 Hz", KenwoodManual.eqBand(0), "first EQ band");
        T.eq("1.6 kHz", KenwoodManual.eqBand(7), "a kHz band");
        T.eq("16 kHz", KenwoodManual.eqBand(12), "last EQ band");
        T.eq(13, KenwoodManual.EQ_HZ.length, "13 bands");
        byte[] bands = new byte[13];
        for (int i = 0; i < 13; i++) bands[i] = 9;                       // 9 = flat when stored as 0..18
        String h = KenwoodManual.hint(0x40, bands);
        T.ok(h != null && h.contains("62.5 Hz") && h.contains("16 kHz") && h.contains("(manual)"), "bands named: " + h);
        T.ok(KenwoodManual.hint(0x40, new byte[]{100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100}) == null,
                "nothing that does not fit the manual");
        KenwoodSettings.Backup b = new KenwoodSettings.Backup();
        b.add(0x40, bands);
        String text = b.toText("now", "KMM-BT728HD");
        T.ok(text.contains("# manual: EQ bands"), "the log carries the manual's ranges");
        T.ok(text.contains("1.6 kHz"), "and each band by name");
        T.eq(1, KenwoodSettings.Backup.fromText(text).size(), "the manual lines do not confuse reading it back");
    }

    private static KenwoodSettings.Backup tune(int loud, int sub) {
        KenwoodSettings.Backup b = new KenwoodSettings.Backup();
        b.add(0x4B, list("OFF", "LV1", "LV2"));
        b.add(0x4C, new byte[]{(byte) loud});
        b.add(0x4E, new byte[]{KenwoodSettings.subLevelByte(sub)});
        b.add(0x40, new byte[13]);
        return b;
    }

    private static void run(KenwoodSettings.Restore r, java.util.Map<Integer, byte[]> stereo) {
        for (int guard = 0; guard < 200 && !r.done(); guard++) {
            KenwoodSettings.Msg m = r.next();
            if (m == null) { r.tick(); continue; }
            if (m.payload.length == 1 && m.payload[0] == 0 && stereo.containsKey(m.cmd)) r.onReply(m.cmd, stereo.get(m.cmd));
            else if (stereo.containsKey(m.cmd - 1)) stereo.put(m.cmd - 1, m.payload.clone());       // a write
        }
    }

    public static void restoreWritesOnlyWhatDiffersAndChecksIt() {
        java.util.Map<Integer, byte[]> stereo = new java.util.HashMap<>();
        stereo.put(0x4C, new byte[]{0});                                  // loudness OFF now, backup has LV1
        stereo.put(0x4E, new byte[]{KenwoodSettings.subLevelByte(-3)});   // sub level already matches
        KenwoodSettings.Restore r = new KenwoodSettings.Restore(tune(1, -3));
        run(r, stereo);
        T.ok(r.done() && r.worked(), "finished: " + r.result());
        T.eq(new byte[]{1}, stereo.get(0x4C), "loudness put back to LV1");
        T.ok(r.result().contains("Loudness") && !r.result().contains("Subwoofer level"), r.result());
        T.ok(r.notRestored().toString().contains("custom EQ bands"), "the EQ is listed as not restored: " + r.notRestored());
    }

    public static void restoreStopsAtAWriteTheStereoIgnores() {
        KenwoodSettings.Restore r = new KenwoodSettings.Restore(tune(2, 0));
        KenwoodSettings.Msg m = r.next();
        T.eq(0x4C, m.cmd, "reads loudness first");
        r.onReply(0x4C, new byte[]{0});
        T.eq(0x4D, r.next().cmd, "writes the saved value");
        KenwoodSettings.Msg c;
        while ((c = r.next()) == null) r.tick();
        r.onReply(0x4C, new byte[]{0});                                   // still OFF
        T.ok(r.done() && !r.worked(), "stops");
        T.ok(r.result().contains("did not take"), r.result());
    }

    public static void restoreRefusesValuesOutsideTheManual() {
        KenwoodSettings.Restore r = new KenwoodSettings.Restore(tune(7, 40));   // LV7 and +40 do not exist
        T.ok(r.done() && !r.worked(), "nothing to put back: " + r.result());
        T.ok(r.notRestored().toString().contains("out of range"), r.notRestored().toString());
    }
}
