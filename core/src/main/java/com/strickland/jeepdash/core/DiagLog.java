package com.strickland.jeepdash.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The app-wide diagnostics log: one rolling record every subsystem writes to when something goes wrong or is
 * worth noting — Bluetooth drops and reconnects to the stereo, commands the head unit rejects, gaps in the
 * dongle feed, calibration and DSP problems, and plain warnings. It is the self-report behind the Sound hub's
 * Diagnostics view, separate from {@link ListenLog} (only Listen mode) and {@link CrashReport} (only crashes).
 *
 * A capped ring buffer, oldest dropped first, so it never grows without bound while a drive runs. Times are
 * milliseconds on the caller's clock (as elsewhere in core); the app pairs them with wall-clock text when it
 * shows or exports the log. Fed by the link service thread and read by the UI, so every method is synchronized.
 */
public final class DiagLog {
    /** Severity, lowest to highest. INFO is normal activity; WARN is recoverable; ERROR is a failure. */
    public static final int INFO = 0, WARN = 1, ERROR = 2;

    /** Subsystem tags, so the view can filter by where a line came from. */
    public static final String BT = "BT", CMD = "CMD", DONGLE = "DONGLE",
            CAL = "CAL", DSP = "DSP", APP = "APP", GFX = "GFX";

    /** One line: when it happened, how bad, which subsystem, and the message. */
    public static final class Entry {
        public final long t;
        public final int level;
        public final String tag;
        public final String msg;
        Entry(long t, int level, String tag, String msg) {
            this.t = t; this.level = level; this.tag = tag; this.msg = msg;
        }
    }

    private final int cap;
    private final Entry[] ring;
    private int head;       // index of the oldest entry
    private int size;
    private int dropped;    // how many were pushed out of the far end, for the export header

    public DiagLog() { this(500); }

    public DiagLog(int cap) {
        this.cap = Math.max(1, cap);
        this.ring = new Entry[this.cap];
    }

    /** Adds a line. A null or blank tag is filed under {@link #APP}; the level is clamped to the known range. */
    public synchronized void add(long nowMs, int level, String tag, String msg) {
        int lv = level < INFO ? INFO : (level > ERROR ? ERROR : level);
        String tg = (tag == null || tag.isEmpty()) ? APP : tag;
        Entry e = new Entry(nowMs, lv, tg, msg == null ? "" : msg);
        if (size < cap) {
            ring[(head + size) % cap] = e;
            size++;
        } else {
            ring[head] = e;
            head = (head + 1) % cap;
            dropped++;
        }
    }

    public synchronized void info(long nowMs, String tag, String msg)  { add(nowMs, INFO, tag, msg); }
    public synchronized void warn(long nowMs, String tag, String msg)  { add(nowMs, WARN, tag, msg); }
    public synchronized void error(long nowMs, String tag, String msg) { add(nowMs, ERROR, tag, msg); }

    public synchronized int size() { return size; }

    public synchronized int droppedCount() { return dropped; }

    /** True if any entry at or above the given level is held — the view uses this to badge the Diagnostics icon. */
    public synchronized boolean hasAtLeast(int level) {
        for (int i = 0; i < size; i++) if (ring[(head + i) % cap].level >= level) return true;
        return false;
    }

    /**
     * Entries, oldest first, keeping only those at or above minLevel and (when tag is non-null) matching that
     * tag, then at most max of them from the newest end. A fresh list, safe for the caller to hold.
     */
    public synchronized List<Entry> recent(int max, int minLevel, String tag) {
        List<Entry> all = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            Entry e = ring[(head + i) % cap];
            if (e.level >= minLevel && (tag == null || e.tag.equals(tag))) all.add(e);
        }
        int from = max > 0 && all.size() > max ? all.size() - max : 0;
        return new ArrayList<>(all.subList(from, all.size()));
    }

    public synchronized void clear() { head = 0; size = 0; dropped = 0; }

    private static String levelName(int level) {
        return level == ERROR ? "ERROR" : level == WARN ? "WARN" : "INFO";
    }

    /**
     * The log as shareable text: a header with the build and how many old lines were dropped, then one line per
     * entry — {@code [+ms] LEVEL/TAG  message} — oldest first, filtered like {@link #recent}. The caller passes
     * the wall-clock time of ms 0 so a reader can place the drive; capped so it fits in a message.
     */
    public synchronized String export(int build, String startedWhen, int minLevel, String tag, int maxChars) {
        StringBuilder out = new StringBuilder();
        out.append("Jeep Dash dash-").append(build).append(" diagnostics");
        if (startedWhen != null) out.append(", clock from ").append(startedWhen);
        if (dropped > 0) out.append(" (").append(dropped).append(" older lines dropped)");
        out.append('\n');
        for (Entry e : recent(0, minLevel, tag)) {
            String line = String.format(Locale.US, "[+%d] %s/%s  %s%n",
                    e.t, levelName(e.level), e.tag, e.msg);
            if (out.length() + line.length() > maxChars) { out.append("…\n"); break; }
            out.append(line);
        }
        return out.toString();
    }
}
