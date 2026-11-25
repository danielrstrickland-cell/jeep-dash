package com.strickland.jeepdash.core;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * "Scan all values" (Jeep only): asks the engine computer for every id of its F4 live-data table, 00 to ef,
 * one at a time, three passes over the lot, and keeps each raw answer for a CSV. Meant to be run with the
 * engine idling warm in Park, so values that move can be told from values that do not. The link thread asks
 * {@link #next} for the id to read, reads it through PollRunner.scanRead (the same request live data uses,
 * nothing else) and hands the answer back to {@link #record}. Fed by the link thread and read by the screen,
 * so every method is synchronized.
 */
public final class ValueScan {
    public static final int PASSES = 3;
    static final int IDS = Protocols.LAST_F4_ID + 1;
    /** This many ids in a row with no answer: the key went off or the line dropped, so the scan stops. */
    static final int SILENT_IN_A_ROW = 24;
    private static final String NO_ANSWER = "no answer";

    private final String[][] answers = new String[IDS][PASSES];      // null = not read yet
    private int done, silent;
    private boolean cancelled, stoppedSilent;

    /** Every read the scan makes. */
    public int total() { return IDS * PASSES; }

    /** Reads made so far. */
    public synchronized int done() { return done; }

    /** The pass under way, 1 to 3 (3 once finished). */
    public synchronized int pass() { return Math.min(PASSES, done / IDS + 1); }

    /** The id to read next, or -1 when the scan is over (finished, cancelled or stopped). */
    public synchronized int next() { return finished() ? -1 : done % IDS; }

    /** The answer to the id last given by {@link #next}: the raw bytes, or null when nothing came back. */
    public synchronized void record(int id, byte[] answer) {
        if (finished() || id != done % IDS) return;
        answers[id][done / IDS] = answer == null ? NO_ANSWER : Hex.str(answer);
        done++;
        silent = answer == null ? silent + 1 : 0;
        if (silent >= SILENT_IN_A_ROW) stoppedSilent = true;
    }

    /** The owner tapped Cancel: nothing more is read; what was read is kept. */
    public synchronized void cancel() { if (!finished()) cancelled = true; }

    public synchronized boolean cancelled() { return cancelled; }

    public synchronized boolean stoppedSilent() { return stoppedSilent; }

    public synchronized boolean finished() { return cancelled || stoppedSilent || done >= total(); }

    /** One line for the progress window: "Pass 2 of 3: id 78 of ef (50%)", or how it ended. */
    public synchronized String progress() {
        if (cancelled) return "Cancelled after " + done + " of " + total() + " reads";
        if (stoppedSilent)
            return "Stopped: the engine computer stopped answering (" + done + " of " + total() + " reads made)";
        if (done >= total()) return "Done: " + total() + " reads";
        return String.format(Locale.US, "Pass %d of %d: id %02x of %02x (%d%%)", pass(), PASSES, done % IDS,
                Protocols.LAST_F4_ID, Math.round(100.0 * done / total()));
    }

    /**
     * The scan as CSV: a header, then one row per id with what the dash calls it (if anything) and the raw
     * answer from each pass ("no answer" when none came, empty when the scan ended before that read). Ids are
     * written 0x0a so a spreadsheet keeps them as text.
     */
    public synchronized String csv(Map<Integer, String> knownAs) {
        StringBuilder sb = new StringBuilder("id,known_as");
        for (int p = 1; p <= PASSES; p++) sb.append(",pass_").append(p);
        sb.append('\n');
        for (int id = 0; id < IDS; id++) {
            String k = knownAs.get(id);
            sb.append(String.format(Locale.US, "0x%02x,", id)).append(k == null ? "" : k);
            for (int p = 0; p < PASSES; p++) sb.append(',').append(answers[id][p] == null ? "" : answers[id][p]);
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Which ids the dash already reads, by the value's id; the two halves of a two-id value are named as such. */
    public static Map<Integer, String> knownAs(Collection<Param> params) {
        Map<Integer, String> out = new TreeMap<>();
        for (Param p : params) {
            if (p.sciIds == null) continue;
            if (p.sciIds.length == 1) name(out, p.sciIds[0], p.id);
            else {
                name(out, p.sciIds[0], p.id + " (high byte)");
                name(out, p.sciIds[1], p.id + " (low byte)");
            }
        }
        return out;
    }

    private static void name(Map<Integer, String> out, int id, String name) {
        out.merge(id, name, (a, b) -> a + "; " + b);
    }
}
