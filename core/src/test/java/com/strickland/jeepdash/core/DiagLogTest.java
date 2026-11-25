package com.strickland.jeepdash.core;

import java.util.List;

/** The app-wide diagnostics log: capping, filtering by level and tag, the badge check, and the export text. */
public class DiagLogTest {

    public static void capDropsOldestAndCountsThem() {
        DiagLog log = new DiagLog(3);
        for (int i = 0; i < 5; i++) log.info(i, DiagLog.APP, "m" + i);
        T.eq(3, log.size(), "holds at most the cap");
        T.eq(2, log.droppedCount(), "two oldest were pushed out");
        List<DiagLog.Entry> r = log.recent(0, DiagLog.INFO, null);
        T.eq("m2", r.get(0).msg, "oldest kept is m2");
        T.eq("m4", r.get(2).msg, "newest is m4");
    }

    public static void filtersByLevelAndTag() {
        DiagLog log = new DiagLog();
        log.info(1, DiagLog.BT, "linked");
        log.warn(2, DiagLog.BT, "weak signal");
        log.error(3, DiagLog.CMD, "EQ rejected");
        T.eq(2, log.recent(0, DiagLog.WARN, null).size(), "only WARN and ERROR");
        T.eq(1, log.recent(0, DiagLog.INFO, DiagLog.CMD).size(), "only the CMD line");
        T.ok(log.hasAtLeast(DiagLog.ERROR), "an error is present");
    }

    public static void recentReturnsNewestWhenCapped() {
        DiagLog log = new DiagLog();
        for (int i = 0; i < 10; i++) log.info(i, DiagLog.DSP, "d" + i);
        List<DiagLog.Entry> r = log.recent(3, DiagLog.INFO, null);
        T.eq(3, r.size(), "at most max");
        T.eq("d7", r.get(0).msg, "newest three, oldest first");
        T.eq("d9", r.get(2).msg, "ends on the newest");
    }

    public static void blankTagBecomesAppAndLevelClamps() {
        DiagLog log = new DiagLog();
        log.add(1, 99, null, "odd");
        DiagLog.Entry e = log.recent(0, DiagLog.INFO, null).get(0);
        T.eq(DiagLog.APP, e.tag, "blank tag filed under APP");
        T.eq(DiagLog.ERROR, e.level, "out-of-range level clamped to ERROR");
    }

    public static void exportHasHeaderAndLines() {
        DiagLog log = new DiagLog();
        log.error(1500, DiagLog.BT, "dropped");
        String text = log.export(42, "1:56 pm", DiagLog.INFO, null, 12000);
        T.ok(text.contains("dash-42"), "header names the build");
        T.ok(text.contains("ERROR/BT"), "line shows level and tag");
        T.ok(text.contains("dropped"), "line shows the message");
    }

    public static void exportCapsLength() {
        DiagLog log = new DiagLog();
        for (int i = 0; i < 200; i++) log.info(i, DiagLog.APP, "a long diagnostic message number " + i);
        String text = log.export(1, null, DiagLog.INFO, null, 400);
        T.ok(text.length() <= 400, "export stays within the char cap");
        T.ok(text.endsWith("…\n"), "truncation is marked");
    }
}
