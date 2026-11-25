package com.strickland.jeepdash.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads Android's native crash record (a "tombstone", protobuf, from ApplicationExitInfo's trace) into the few
 * lines that say what happened: the signal, the abort message, the crashing thread's backtrace, other threads
 * running the app's own native code, and the app's last log lines (where a library like Filament writes why it
 * stopped). Field numbers follow AOSP's system/core/debuggerd/proto/tombstone.proto.
 */
public final class Tombstone {
    private Tombstone() { }

    private static final class Frame {
        long relPc, offset;
        String function = "", file = "";
    }

    private static final class Thread {
        int id;
        String name = "";
        final List<Frame> frames = new ArrayList<>();
    }

    /** The summary, capped at maxChars; null when the bytes are not a tombstone. */
    public static String summary(byte[] data, int maxChars) {
        try {
            return read(data, maxChars);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String read(byte[] data, int maxChars) {
        Reader r = new Reader(data, 0, data.length);
        int tid = -1;
        String signal = null, code = null, abort = null;
        List<String> causes = new ArrayList<>(), logs = new ArrayList<>();
        Map<Integer, Thread> threads = new LinkedHashMap<>();
        boolean any = false;
        while (r.more()) {
            int tag = r.tag(), field = tag >>> 3;
            if (field == 6 && (tag & 7) == 0) {
                tid = (int) r.varint();
            } else if (field == 10 && (tag & 7) == 2) {
                Reader s = r.sub();
                while (s.more()) {
                    int t = s.tag();
                    if (t >>> 3 == 2 && (t & 7) == 2) signal = s.string();
                    else if (t >>> 3 == 4 && (t & 7) == 2) code = s.string();
                    else s.skip(t);
                }
            } else if (field == 14 && (tag & 7) == 2) {
                abort = r.string();
            } else if (field == 15 && (tag & 7) == 2) {
                Reader c = r.sub();
                while (c.more()) {
                    int t = c.tag();
                    if (t >>> 3 == 1 && (t & 7) == 2) causes.add(c.string());
                    else c.skip(t);
                }
            } else if (field == 16 && (tag & 7) == 2) {
                Reader entry = r.sub();
                while (entry.more()) {
                    int t = entry.tag();
                    if (t >>> 3 == 2 && (t & 7) == 2) {
                        Thread th = thread(entry.sub());
                        threads.put(th.id, th);
                    } else {
                        entry.skip(t);
                    }
                }
            } else if (field == 18 && (tag & 7) == 2) {
                Reader buf = r.sub();
                while (buf.more()) {
                    int t = buf.tag();
                    if (t >>> 3 == 2 && (t & 7) == 2) logs.add(log(buf.sub()));
                    else buf.skip(t);
                }
            } else {
                r.skip(tag);
                continue;
            }
            any = true;
        }
        if (!any || signal == null && threads.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("Signal: ").append(signal).append(code == null ? "" : " (" + code + ")").append('\n');
        if (abort != null && !abort.isEmpty()) sb.append("Abort message: ").append(abort).append('\n');
        for (String c : causes) sb.append("Cause: ").append(c).append('\n');
        Thread crashing = threads.get(tid);
        if (crashing != null) {
            sb.append("\nCrashing thread ").append(crashing.id).append(" \"").append(crashing.name).append("\":\n");
            frames(sb, crashing, 40);
        }
        if (!logs.isEmpty()) {
            sb.append("\nLog (last lines from the app):\n");
            for (String l : logs.subList(Math.max(0, logs.size() - 60), logs.size())) sb.append(l).append('\n');
        }
        for (Thread t : threads.values()) {
            if (t == crashing || !runsAppCode(t)) continue;
            sb.append("\nThread ").append(t.id).append(" \"").append(t.name).append("\":\n");
            frames(sb, t, 12);
        }
        return sb.length() <= maxChars ? sb.toString() : sb.substring(0, maxChars);
    }

    private static boolean runsAppCode(Thread t) {
        for (Frame f : t.frames) if (f.file.startsWith("/data/app/")) return true;
        return false;
    }

    private static void frames(StringBuilder sb, Thread t, int max) {
        for (int i = 0; i < t.frames.size() && i < max; i++) {
            Frame f = t.frames.get(i);
            sb.append(String.format(java.util.Locale.US, "  #%02d pc %016x  %s", i, f.relPc, f.file));
            if (!f.function.isEmpty()) sb.append(" (").append(f.function).append('+').append(f.offset).append(')');
            sb.append('\n');
        }
    }

    private static Thread thread(Reader r) {
        Thread t = new Thread();
        while (r.more()) {
            int tag = r.tag(), field = tag >>> 3;
            if (field == 1 && (tag & 7) == 0) t.id = (int) r.varint();
            else if (field == 2 && (tag & 7) == 2) t.name = r.string();
            else if (field == 4 && (tag & 7) == 2) t.frames.add(frame(r.sub()));
            else r.skip(tag);
        }
        return t;
    }

    private static Frame frame(Reader r) {
        Frame f = new Frame();
        while (r.more()) {
            int tag = r.tag(), field = tag >>> 3;
            if (field == 1 && (tag & 7) == 0) f.relPc = r.varint();
            else if (field == 4 && (tag & 7) == 2) f.function = r.string();
            else if (field == 5 && (tag & 7) == 0) f.offset = r.varint();
            else if (field == 6 && (tag & 7) == 2) f.file = r.string();
            else r.skip(tag);
        }
        return f;
    }

    private static final String PRIORITY = "??VDIWEF";

    private static String log(Reader r) {
        String when = "", tag = "", msg = "";
        int prio = 0;
        while (r.more()) {
            int t = r.tag(), field = t >>> 3;
            if (field == 1 && (t & 7) == 2) when = r.string();
            else if (field == 4 && (t & 7) == 0) prio = (int) r.varint();
            else if (field == 5 && (t & 7) == 2) tag = r.string();
            else if (field == 6 && (t & 7) == 2) msg = r.string();
            else r.skip(t);
        }
        char p = prio >= 0 && prio < PRIORITY.length() ? PRIORITY.charAt(prio) : '?';
        return when + " " + p + " " + tag + ": " + msg.trim();
    }

    /** Protobuf wire format: just enough to walk fields and skip the rest. */
    private static final class Reader {
        private final byte[] b;
        private int p;
        private final int end;

        Reader(byte[] b, int from, int end) {
            if (from < 0 || end > b.length || from > end) throw new IllegalArgumentException("bad range");
            this.b = b;
            this.p = from;
            this.end = end;
        }

        boolean more() { return p < end; }

        int tag() {
            long t = varint();
            if (t <= 0 || (t & 7) == 3 || (t & 7) == 4 || (t & 7) > 5) throw new IllegalArgumentException("bad tag");
            return (int) t;
        }

        long varint() {
            long v = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (p >= end) throw new IllegalArgumentException("truncated");
                int c = b[p++] & 0xff;
                v |= (long) (c & 0x7f) << shift;
                if ((c & 0x80) == 0) return v;
            }
            throw new IllegalArgumentException("varint too long");
        }

        Reader sub() {
            long n = varint();
            if (n < 0 || p + n > end) throw new IllegalArgumentException("bad length");
            Reader r = new Reader(b, p, (int) (p + n));
            p += (int) n;
            return r;
        }

        String string() {
            Reader s = sub();
            return new String(b, s.p, s.end - s.p, StandardCharsets.UTF_8);
        }

        void skip(int tag) {
            switch (tag & 7) {
                case 0: varint(); break;
                case 1: advance(8); break;
                case 2: sub(); break;
                case 5: advance(4); break;
                default: throw new IllegalArgumentException("bad wire type");
            }
        }

        private void advance(int n) {
            if (p + n > end) throw new IllegalArgumentException("truncated");
            p += n;
        }
    }
}
