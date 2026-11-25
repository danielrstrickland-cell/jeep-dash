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
}
