package com.strickland.jeepdash.core;

public class CrashReportTest {
    public static void reportNamesBuildThreadAndCause() {
        Throwable cause = new IllegalStateException("inner");
        Throwable t = new RuntimeException("outer", cause);
        String r = CrashReport.format(53, "2026-11-25 10:05", "main", t);
        T.ok(r.startsWith("Jeep Dash dash-53 crashed 2026-11-25 10:05 on thread main\n"), "header line: " + r);
        T.ok(r.contains("java.lang.RuntimeException: outer"), "the exception");
        T.ok(r.contains("Caused by: java.lang.IllegalStateException: inner"), "its cause");
        T.ok(r.contains("CrashReportTest"), "the stack frames");
    }

    public static void reportIsCappedForSharing() {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 5000; i++) deep.append("x");
        String r = CrashReport.format(1, "t", "main", new RuntimeException(deep.toString()));
        T.ok(r.length() <= CrashReport.MAX_CHARS, "capped at " + CrashReport.MAX_CHARS + ", was " + r.length());
    }

    public static void printableStringsComeOutOfBinaryTombstones() {
        byte[] b = ("\u0001\u0002Abort message: 'Precondition in build:42'\u0000\u0007ab\u0000"
                + "\u0003#00 pc 0001 libfilament-jni.so (filament::Panic)\u0000").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        String s = CrashReport.printable(b, 4, 1000);
        T.eq("Abort message: 'Precondition in build:42'\n#00 pc 0001 libfilament-jni.so (filament::Panic)", s, "strings, short runs dropped");
        T.ok(CrashReport.printable(b, 4, 20).length() <= 20, "capped");
    }

    public static void exitReasonsWorthShowing() {
        T.eq("native crash", CrashReport.reasonName(5), "REASON_CRASH_NATIVE");
        T.eq("crash", CrashReport.reasonName(4), "REASON_CRASH");
        T.eq("not responding", CrashReport.reasonName(6), "REASON_ANR");
        T.ok(CrashReport.worthShowing(5) && CrashReport.worthShowing(4) && CrashReport.worthShowing(6)
                && CrashReport.worthShowing(7) && CrashReport.worthShowing(3) && CrashReport.worthShowing(9), "crashes, start-up failure, memory, resources");
        T.ok(!CrashReport.worthShowing(10) && !CrashReport.worthShowing(1) && !CrashReport.worthShowing(11), "user swipe, normal exit, update");
    }
}
