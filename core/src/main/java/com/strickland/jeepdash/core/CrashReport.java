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

    /**
     * The readable text in a binary record (Android's native crash "tombstone" is protobuf): every run of at
     * least minLen printable ASCII characters, one per line, capped at maxChars.
     */
    public static String printable(byte[] data, int minLen, int maxChars) {
        StringBuilder out = new StringBuilder(), run = new StringBuilder();
        for (int i = 0; i <= data.length && out.length() < maxChars; i++) {
            int c = i < data.length ? data[i] & 0xff : 0;
            if (c >= 0x20 && c < 0x7f) {
                run.append((char) c);
                continue;
            }
            if (run.length() >= minLen) {
                if (out.length() > 0) out.append('\n');
                out.append(run);
            }
            run.setLength(0);
        }
        return out.length() <= maxChars ? out.toString() : out.substring(0, maxChars);
    }

    /** Android's ApplicationExitInfo reasons, in words. */
    public static String reasonName(int reason) {
        switch (reason) {
            case 1: return "closed itself";
            case 2: return "killed by a signal";
            case 3: return "out of memory";
            case 4: return "crash";
            case 5: return "native crash";
            case 6: return "not responding";
            case 7: return "failed to start";
            case 8: return "permission change";
            case 9: return "too much CPU or memory";
            case 10: return "closed by you";
            case 11: return "app updated";
            default: return "reason " + reason;
        }
    }

    /** Exits worth showing on the next start: crashes, start-up failures, memory and resource kills. */
    public static boolean worthShowing(int reason) {
        return reason == 2 || reason == 3 || reason == 4 || reason == 5 || reason == 6 || reason == 7 || reason == 9;
    }
}
