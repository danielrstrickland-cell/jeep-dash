package com.strickland.jeepdash.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.Map;

/**
 * The text log of Listen mode (passive Bluetooth listening to the stereo): one line per event, each with a
 * millisecond timestamp, a channel (UUID / characteristic), a direction and hex; marker lines for the labels the
 * owner taps; and the inter-message timing statistics of the speed test. Nothing here ever sends anything.
 */
public final class ListenLog {
    public static final String RX = "RX", INFO = "INFO", MARK = "MARK";

    private final StringBuilder text = new StringBuilder();
    private final Map<String, long[]> timing = new TreeMap<>();    // channel -> {count, lastMs, sumGapMs, minGap, maxGap}
    private int lines;

    /** Quick labels, grouped: each is tapped right after the matching change on the stereo. */
    public static final List<String> PRESETS = Collections.unmodifiableList(presets());

    private static List<String> presets() {
        List<String> out = new ArrayList<>();
        for (String b : new String[]{"62.5", "100", "160", "250", "400", "630", "1k", "1k6", "2k5", "4k", "6k3", "10k", "16k"}) {
            out.add("EQ " + b + " +1");
            out.add("EQ " + b + " -1");
        }
        for (String s : new String[]{"FL", "FR", "RL", "RR", "Sub"}) {
            out.add("TA " + s + " +1");
            out.add("TA " + s + " -1");
        }
        out.addAll(Arrays.asList("HPF front up", "HPF front down", "HPF rear up", "HPF rear down",
                "LPF sub up", "LPF sub down", "Slope change", "Sub level +", "Sub level -", "Sub phase",
                "Preset / EQ curve change", "Volume +", "Volume -", "Source change", "Track change"));
        return out;
    }

    public synchronized void info(long ms, String message) { line(ms, "-", INFO, message); }

    /** Bytes received on a channel. */
    public synchronized void rx(long ms, String channel, byte[] data) {
        line(ms, channel, RX, Hex.str(data));
        long[] t = timing.get(channel);
        if (t == null) {
            timing.put(channel, new long[]{1, ms, 0, Long.MAX_VALUE, 0});
            return;
        }
        long gap = Math.max(0, ms - t[1]);
        t[0]++;
        t[1] = ms;
        t[2] += gap;
        t[3] = Math.min(t[3], gap);
        t[4] = Math.max(t[4], gap);
    }

    /** A label tapped by the owner (or typed). */
    public synchronized void mark(long ms, String label) { line(ms, "-", MARK, "=== " + label + " ==="); }

    private void line(long ms, String channel, String dir, String body) {
        text.append(String.format(Locale.US, "%tF %<tT.%<tL", ms)).append(' ')
                .append(channel).append(' ').append(dir).append(' ').append(body).append('\n');
        lines++;
    }

    public synchronized int lines() { return lines; }

    public synchronized String text() { return text.toString(); }

    /** The last n lines, for the screen. */
    public synchronized String tail(int n) {
        String s = text.toString();
        int end = s.length(), start = end;
        for (int found = 0; start > 0 && found <= n; start--) if (s.charAt(start - 1) == '\n') found++;
        return start <= 0 ? s : s.substring(start + 1);
    }

    /** Speed test: per channel, how many messages and the gaps between them. */
    public synchronized String timingReport() {
        if (timing.isEmpty()) return "No messages yet.";
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, long[]> e : timing.entrySet()) {
            long[] t = e.getValue();
            b.append(e.getKey()).append(": ").append(t[0]).append(" msgs");
            if (t[0] > 1)
                b.append(String.format(Locale.US, ", gap avg %.1f ms, min %d, max %d", t[2] / (double) (t[0] - 1), t[3], t[4]));
            b.append('\n');
        }
        return b.toString().trim();
    }

    /** The saved file: a header, the timing report and the whole log. */
    public synchronized String file(String header) {
        return header + "\n\n--- timing ---\n" + timingReport() + "\n\n--- log ---\n" + text;
    }
}
