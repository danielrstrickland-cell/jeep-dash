package com.strickland.jeepdash.core;

/**
 * The Porsche engine computer's live data. One request returns a group of up to eight values, each as three
 * bytes: a kind, and two numbers a and b. The kind says how a and b turn into a reading, so the computer
 * itself states the unit. Only kinds checked against the GearWrench app's own display (or, for volts, a
 * plainly sensible reading) are understood here; anything else stays blank rather than risk a wrong number.
 */
public final class KwpBlocks {
    private KwpBlocks() { }

    public static final int RPM = 1, TEMP_C = 5, KMH = 7, MBAR = 18, VOLT = 21, TEMP_DIFF_C = 26, DEGREES = 34;

    /** One group's answer. */
    public static final class Block {
        private final byte[] d;      // kind, a, b, kind, a, b, ...

        Block(byte[] d) { this.d = d; }

        /** The reading in slot index, or NaN when the slot is missing or is not of the expected kind. */
        public double v(int index, int kind) {
            int at = index * 3;
            if (index < 0 || at + 2 >= d.length || (d[at] & 0xff) != kind) return Double.NaN;
            int a = d[at + 1] & 0xff, b = d[at + 2] & 0xff;
            switch (kind) {
                case RPM: return a * b * 0.2;
                case TEMP_C: return a * (b - 100) * 0.1;
                case KMH: return a * b * 0.01;
                case MBAR: return a * b * 0.04;
                case VOLT: return a * b * 0.001;
                case TEMP_DIFF_C: return b - a;
                case DEGREES: return (b - 128) * 0.01 * a;
                default: return Double.NaN;
            }
        }
    }

    /** "Read measured values, group g" addressed to the engine computer, with its checksum. */
    public static byte[] request(int group) {
        int sum = (0x82 + 0x10 + 0xf1 + 0x21 + group) & 0xff;
        return new byte[]{0x61, 0x04, 0x00, 0x06, (byte) 0x82, 0x10, (byte) 0xf1, 0x21, (byte) group, (byte) sum, 0x01};
    }

    /**
     * Checks an answer (8n f1 10 61 group ... checksum) and returns its values, or null when it is not a
     * sound answer for that group.
     */
    public static Block parse(int group, byte[] r) {
        if (r == null || r.length < 6) return null;
        int n = r[0] & 0x3f;
        if ((r[0] & 0xc0) != 0x80 || n < 2 || r.length != n + 4) return null;
        if ((r[1] & 0xff) != 0xf1 || (r[2] & 0xff) != 0x10 || (r[3] & 0xff) != 0x61 || (r[4] & 0xff) != group) return null;
        int sum = 0;
        for (int i = 0; i < r.length - 1; i++) sum += r[i] & 0xff;
        if ((sum & 0xff) != (r[r.length - 1] & 0xff)) return null;
        byte[] d = new byte[n - 2];
        System.arraycopy(r, 5, d, 0, d.length);
        return new Block(d);
    }
}
