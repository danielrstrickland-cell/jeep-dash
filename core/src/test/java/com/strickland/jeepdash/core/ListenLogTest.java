package com.strickland.jeepdash.core;

/** Listen mode's log lines, labels and timing report. */
public class ListenLogTest {
    public static void linesCarryChannelDirectionAndHex() {
        ListenLog l = new ListenLog();
        l.rx(1000, "spp", new byte[]{1, (byte) 0xAB});
        l.mark(1500, "EQ 1k +1");
        String t = l.text();
        T.ok(t.contains(" spp RX 01 ab"), "hex line: " + t);
        T.ok(t.contains("MARK === EQ 1k +1 ==="), "marker line");
        T.eq(2, l.lines(), "two lines");
    }

    public static void timingStatistics() {
        ListenLog l = new ListenLog();
        l.rx(0, "c", new byte[]{1});
        l.rx(100, "c", new byte[]{1});
        l.rx(400, "c", new byte[]{1});
        String r = l.timingReport();
        T.ok(r.contains("3 msgs") && r.contains("avg 200.0") && r.contains("min 100") && r.contains("max 300"), r);
    }

    public static void presetsCoverTheRequestedChanges() {
        T.ok(ListenLog.PRESETS.contains("EQ 62.5 +1") && ListenLog.PRESETS.contains("EQ 16k -1"), "eq");
        T.ok(ListenLog.PRESETS.contains("TA Sub -1") && ListenLog.PRESETS.contains("Track change"), "ta, track");
        T.eq(13 * 2 + 5 * 2 + 15, ListenLog.PRESETS.size(), "count");
    }

    public static void tailKeepsTheLastLines() {
        ListenLog l = new ListenLog();
        for (int i = 0; i < 10; i++) l.info(i, "m" + i);
        String t = l.tail(3);
        T.ok(t.contains("m9") && t.contains("m7") && !t.contains("m6"), t);
    }
}
