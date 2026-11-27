package com.strickland.jeepdash.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The KMM-BT728HD's sound settings as the stock KENWOOD Remote app (2.0.5) reads and writes them. Its settings
 * screens send to the main unit in category 3D, and the stereo answers in category 5D with the same command
 * number. Reads carry one 0. Most settings come as three commands in a row: the list of choices (a count,
 * then that many 7-letter names), what it is set to now (one byte, the place in that list), and the write
 * (one byte). Pure: the app owns the link and the clock.
 */
public final class KenwoodSettings {
    public static final int CAT = 0x3D, REPLY = 0x5D;
    public static final int LOUD_LIST = 0x4B, LOUD_GET = 0x4C, LOUD_SET = 0x4D;
    public static final int BASS_LIST = 0x48, BASS_GET = 0x49, BASS_SET = 0x4A;

    /** A message to the stereo's settings: its command and payload. */
    public static final class Msg {
        public final String label;
        public final int cmd;
        public final byte[] payload;
        Msg(String label, int cmd, byte[] payload) { this.label = label; this.cmd = cmd; this.payload = payload; }
    }

    /** One read in the backup. */
    public static final class Read {
        public final String label;
        public final int cmd;
        Read(String label, int cmd) { this.label = label; this.cmd = cmd; }
    }

    private static final List<Read> READS = new ArrayList<>();
    private static void read(String label, int cmd) { READS.add(new Read(label, cmd)); }
    static {
        // the stock app's own reads (its settings screens send these as they open), then the reads that sit
        // just before a write it sends, as its setting pairs do; nothing here is one of its writes
        read("settings menu", 0x50);
        read("preset EQ list", 0x01);
        read("custom EQ", 0x04);
        read("preset EQ now", 0x0E);
        read("custom EQ bands", 0x40);
        read("filter menu", 0x06);
        read("filter choices", 0x07);
        read("filters now", 0x08);
        read("fader/balance menu", 0x0A);
        read("fader/balance now", 0x0B);
        read("setting 0D", 0x0D);
        read("setting 10", 0x10);
        read("subwoofer choices", 0x42);
        read("subwoofer now", 0x43);
        read("bass boost choices", BASS_LIST);
        read("bass boost now", BASS_GET);
        read("loudness choices", LOUD_LIST);
        read("loudness now", LOUD_GET);
        read("subwoofer level", 0x4E);
        read("speakers", 0x51);
        read("speaker sizes", 0x54);
        read("time alignment", 0x5A);
    }

    /** The commands the stock app sends as writes. None of them is in {@link #reads()}. */
    private static final int[] WRITES = {0x05, 0x09, 0x0C, 0x0F, 0x11, 0x12, 0x41, 0x44, 0x47, 0x4A, 0x4D, 0x4F,
            0x55, 0x56, 0x59, 0x5D, 0x5E};

    public static boolean isWrite(int cmd) {
        for (int w : WRITES) if (w == cmd) return true;
        return false;
    }

    /** The reads that make up a backup of the tune, in order. */
    public static List<Read> reads() { return Collections.unmodifiableList(READS); }

    /** The name of a read, for the log, or the command in hex. */
    public static String name(int cmd) {
        for (Read r : READS) if (r.cmd == cmd) return r.label;
        return String.format(Locale.US, "setting %02X", cmd);
    }

    /** A list of choices: the count, then that many names of equal width. Empty if it does not hold together. */
    public static List<String> options(byte[] p) {
        List<String> out = new ArrayList<>();
        if (p == null || p.length < 2) return out;
        int n = p[0] & 0xFF;
        if (n == 0 || (p.length - 1) < n || (p.length - 1) % n != 0) return out;
        int w = (p.length - 1) / n;
        for (int i = 0; i < n; i++) {
            StringBuilder s = new StringBuilder();
            for (int k = 0; k < w; k++) {
                int c = p[1 + i * w + k] & 0xFF;
                if (c >= 0x20 && c < 0x7F) s.append((char) c);
            }
            out.add(s.toString().trim());
        }
        return out;
    }

    /** The stock app writes the subwoofer level as size with the top bit for minus. */
    public static int subLevel(byte b) { return (b & 0x80) != 0 ? -(b & 0x7F) : b & 0x7F; }

    public static byte subLevelByte(int level) { return (byte) (level < 0 ? (-level) | 0x80 : level & 0x7F); }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.US, "%02X", x & 0xFF));
        return s.toString();
    }

    /** Every settings answer the stereo gave, kept byte for byte, so the tune can be put back later. */
    public static final class Backup {
        private final Map<Integer, byte[]> replies = new TreeMap<>();

        public synchronized void add(int cmd, byte[] payload) { replies.put(cmd, payload.clone()); }
        public synchronized int size() { return replies.size(); }
        public synchronized boolean has(int cmd) { return replies.containsKey(cmd); }
        public synchronized byte[] get(int cmd) { byte[] b = replies.get(cmd); return b == null ? null : b.clone(); }

        /** What a setting reads as, in words where the list of choices is known. */
        private String words(int cmd, byte[] p) {
            List<String> opts = options(p);
            if (!opts.isEmpty() && p.length > 2) return "choices " + String.join(", ", opts);
            if (p.length == 1) {
                int listCmd = cmd - 1;
                byte[] list = replies.get(listCmd);
                List<String> names = list == null ? Collections.<String>emptyList() : options(list);
                int v = p[0] & 0xFF;
                if (v < names.size()) return "set to " + names.get(v);
                if (cmd == 0x4E) return "level " + subLevel(p[0]);
                return "value " + v;
            }
            return null;
        }

        /** A text file: a header, then one line per setting, "3D/4C = 01  # loudness now: set to LV1". */
        public synchronized String toText(String when, String stereo) {
            StringBuilder s = new StringBuilder("# Kenwood tune backup\n# stereo: ").append(stereo)
                    .append("\n# taken: ").append(when).append("\n# ").append(replies.size()).append(" settings\n");
            for (Map.Entry<Integer, byte[]> e : replies.entrySet()) {
                int cmd = e.getKey();
                String w = words(cmd, e.getValue());
                s.append(String.format(Locale.US, "3D/%02X = %s  # %s%s%n", cmd, hex(e.getValue()), name(cmd),
                        w == null ? "" : ": " + w));
            }
            return s.toString();
        }

        /** Reads a backup file back; lines it cannot read are skipped. */
        public static Backup fromText(String text) {
            Backup b = new Backup();
            if (text == null) return b;
            for (String line : text.split("\\r?\\n")) {
                String l = line.trim();
                if (!l.startsWith("3D/")) continue;
                int eq = l.indexOf('=');
                if (eq < 0) continue;
                try {
                    int cmd = Integer.parseInt(l.substring(3, eq).trim(), 16);
                    String rest = l.substring(eq + 1).trim();
                    int sp = rest.indexOf(' ');
                    String h = sp < 0 ? rest : rest.substring(0, sp);
                    if (h.length() % 2 != 0) continue;
                    byte[] p = new byte[h.length() / 2];
                    for (int i = 0; i < p.length; i++) p[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
                    b.add(cmd, p);
                } catch (NumberFormatException ignored) { }
            }
            return b;
        }
    }

    /**
     * One setting changed and checked, then put back: read its choices and its value, write the next choice,
     * read it back, write the first value back and read that back. If the stereo does not take the write,
     * nothing more is written. Proves whether, and how, a write reaches the stereo.
     */
    public static final class WriteTest {
        /** How many ticks (the app's are half a second) to wait for an answer. */
        public static final int PATIENCE = 12;
        private final String name;
        private final int list, get, set;
        private int stage;                 // 0 list, 1 value, 2 write, 3 read back, 4 restore, 5 check, 6 done
        private boolean handed, worked;
        private int waited;
        private List<String> choices = new ArrayList<>();
        private int original = -1, tried = -1;
        private String result = "";

        private WriteTest(String name, int list, int get, int set) {
            this.name = name; this.list = list; this.get = get; this.set = set;
        }

        /** Loudness: audible but harmless, and easy to see on the stereo's display. */
        public static WriteTest loudness() { return new WriteTest("Loudness", LOUD_LIST, LOUD_GET, LOUD_SET); }

        private String choice(int v) { return v >= 0 && v < choices.size() ? choices.get(v) : String.valueOf(v); }

        /** The next message to send, once; null while waiting for an answer or when done. */
        public synchronized Msg next() {
            if (stage >= 6 || handed) return null;
            handed = true;
            waited = 0;
            switch (stage) {
                case 0: return new Msg(name + " choices", list, new byte[]{0});
                case 1: case 3: case 5: return new Msg(name + " now", get, new byte[]{0});
                case 2:
                    stage = 3;
                    handed = false;
                    return new Msg(name + " set to " + choice(tried), set, new byte[]{(byte) tried});
                default:
                    stage = 5;
                    handed = false;
                    return new Msg(name + " put back to " + choice(original), set, new byte[]{(byte) original});
            }
        }

        /** An answer from the stereo's settings. */
        public synchronized void onReply(int cmd, byte[] p) {
            if (stage >= 6 || !handed) return;
            if (stage == 0 && cmd == list) {
                choices = options(p);
                if (choices.size() < 2) { finish(false, "The stereo's " + name + " list did not read: " + hex(p)); return; }
                advance(1);
            } else if (cmd == get && p.length > 0) {
                int v = p[0] & 0xFF;
                if (stage == 1) {
                    original = v;
                    tried = (v + 1) % choices.size();
                    advance(2);
                } else if (stage == 3) {
                    if (v == tried) {
                        worked = true;
                        advance(4);
                    } else {
                        finish(false, name + " did not change: still " + choice(v) + " after writing " + choice(tried) + ".");
                    }
                } else if (stage == 5) {
                    finish(worked, v == original
                            ? name + " went from " + choice(original) + " to " + choice(tried) + " and back to " + choice(original) + ". Writes work."
                            : name + " changed to " + choice(tried) + " but did not go back: it reads " + choice(v)
                            + ". Set it to " + choice(original) + " on the stereo.");
                }
            }
        }

        private void advance(int to) { stage = to; handed = false; }

        private void finish(boolean ok, String text) { stage = 6; worked = ok; result = text; }

        /** Time passing (every half second): gives up on an answer that does not come. */
        public synchronized void tick() {
            if (stage >= 6 || !handed) return;
            if (++waited >= PATIENCE) {
                String what = stage == 0 ? "the list of choices" : "the value";
                finish(false, "The stereo gave no answer to the " + name + " " + what + " request."
                        + (stage >= 3 ? " Check " + name + " on the stereo: it should be " + choice(original) + "." : ""));
            }
        }

        public synchronized boolean done() { return stage >= 6; }
        public synchronized boolean worked() { return worked; }
        public synchronized String result() { return result; }
    }

    private KenwoodSettings() { }
}
