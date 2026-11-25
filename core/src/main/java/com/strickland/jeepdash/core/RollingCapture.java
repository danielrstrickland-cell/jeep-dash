package com.strickland.jeepdash.core;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Keeps the last {@code beforeMs} of readings in memory. When {@link #trigger} is called it keeps going for
 * {@code afterMs} more and then hands back the whole window as a {@link Capture}. Times are on the clock the
 * readings are stamped with (LinkService.now()). Safe to call from several threads.
 */
public final class RollingCapture {
    public static final long DEFAULT_BEFORE_MS = 30000, DEFAULT_AFTER_MS = 30000;

    /** One finished window: rows from beforeMs before the trigger to afterMs after it. */
    public static final class Capture {
        public final String reason;
        public final long triggerMs;
        public final List<String> ids;
        public final List<Long> times;
        public final List<Double[]> rows;

        Capture(String reason, long triggerMs, List<String> ids, List<Long> times, List<Double[]> rows) {
            this.reason = reason;
            this.triggerMs = triggerMs;
            this.ids = ids;
            this.times = times;
            this.rows = rows;
        }

        /** CSV: a "# trigger=" line, then time_ms, seconds from the trigger (negative before it), one column per value. */
        public void writeCsv(Writer out) throws IOException {
            StringBuilder sb = new StringBuilder("# trigger=").append(reason).append('\n').append("time_ms,rel_s");
            for (String id : ids) sb.append(',').append(id);
            sb.append('\n');
            for (int i = 0; i < rows.size(); i++) {
                long t = times.get(i);
                sb.append(t).append(',').append(String.format(Locale.US, "%.1f", (t - triggerMs) / 1000.0));
                for (Double d : rows.get(i)) {
                    sb.append(',');
                    if (d != null) sb.append(d);
                }
                sb.append('\n');
            }
            out.write(sb.toString());
        }

        public String toCsv() {
            java.io.StringWriter w = new java.io.StringWriter();
            try { writeCsv(w); } catch (IOException e) { throw new IllegalStateException(e); }
            return w.toString();
        }
    }

    private final List<String> ids;
    private final long beforeMs, afterMs;
    private final ArrayDeque<Long> times = new ArrayDeque<>();
    private final ArrayDeque<Double[]> rows = new ArrayDeque<>();

    private String pendingReason;
    private long triggerMs, deadlineMs;
    private List<Long> preTimes, postTimes;
    private List<Double[]> preRows, postRows;

    public RollingCapture(List<String> ids) { this(ids, DEFAULT_BEFORE_MS, DEFAULT_AFTER_MS); }

    public RollingCapture(List<String> ids, long beforeMs, long afterMs) {
        this.ids = new ArrayList<>(ids);
        this.beforeMs = beforeMs;
        this.afterMs = afterMs;
    }

    public synchronized boolean pending() { return pendingReason != null; }

    /**
     * Records the readings at nowMs. Returns the finished Capture when this sample completes one that was
     * triggered, otherwise null.
     */
    public synchronized Capture sample(long nowMs, LiveValues v) {
        Double[] row = new Double[ids.size()];
        for (int i = 0; i < row.length; i++) row[i] = v.get(ids.get(i), nowMs);
        times.addLast(nowMs);
        rows.addLast(row);
        while (!times.isEmpty() && nowMs - times.peekFirst() > beforeMs) {
            times.removeFirst();
            rows.removeFirst();
        }
        if (pendingReason == null) return null;
        if (nowMs > triggerMs) {
            postTimes.add(nowMs);
            postRows.add(row);
        }
        return nowMs >= deadlineMs ? finish() : null;
    }

    /** Starts a capture: the buffered window before nowMs is kept, and afterMs more is collected. False if one is already running. */
    public synchronized boolean trigger(long nowMs, String reason) {
        if (pendingReason != null) return false;
        pendingReason = reason;
        triggerMs = nowMs;
        deadlineMs = nowMs + afterMs;
        preTimes = new ArrayList<>();
        preRows = new ArrayList<>();
        postTimes = new ArrayList<>();
        postRows = new ArrayList<>();
        java.util.Iterator<Long> ti = times.iterator();
        java.util.Iterator<Double[]> ri = rows.iterator();
        while (ti.hasNext()) {
            long t = ti.next();
            Double[] r = ri.next();
            if (t >= nowMs - beforeMs && t <= nowMs) {
                preTimes.add(t);
                preRows.add(r);
            }
        }
        return true;
    }

    /** Ends a running capture now with whatever was collected (link went away); null when none is running. */
    public synchronized Capture finish() {
        if (pendingReason == null) return null;
        List<Long> t = new ArrayList<>(preTimes);
        t.addAll(postTimes);
        List<Double[]> r = new ArrayList<>(preRows);
        r.addAll(postRows);
        Capture c = new Capture(pendingReason, triggerMs, ids, t, r);
        pendingReason = null;
        preTimes = postTimes = null;
        preRows = postRows = null;
        return c;
    }
}
