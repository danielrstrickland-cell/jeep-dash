package com.strickland.jeepdash.core;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * The text saved when the app crashes, shown (and shareable) on the next start: build, time and thread, then
 * the full stack trace with its causes. Capped so it fits in a message.
 */
public final class CrashReport {
    public static final int MAX_CHARS = 12000;

    private CrashReport() { }

    public static String format(int build, String when, String thread, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String r = "Jeep Dash dash-" + build + " crashed " + when + " on thread " + thread + "\n" + sw;
        return r.length() <= MAX_CHARS ? r : r.substring(0, MAX_CHARS);
    }
}
