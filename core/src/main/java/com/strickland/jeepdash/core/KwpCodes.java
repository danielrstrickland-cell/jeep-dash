package com.strickland.jeepdash.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Stored trouble codes on the Cayenne's engine computer: reading the list and the dash's one write to this
 * car, which clears it. Both frames are exactly what the GearWrench app sent for Read DTC and Clear DTC on
 * this car (capture of 2026-11-22, docs/protocol-notes.md). A code is kept as its two bytes, 0x1525 for P1525.
 */
public final class KwpCodes {
    private KwpCodes() { }

    /** "Read codes by status", every group. Answer: 58, how many, then two code bytes and a status byte each. */
    public static final String READ = "61 04 00 08 84 10 f1 18 00 ff 00 9c ff";
    /** "Clear diagnostic information", every group. Answer: 54 ff 00 when done, 7f 14 and a reason when refused. */
    public static final String CLEAR = "61 04 00 07 83 10 f1 14 ff 00 97 01";

    private static final int LIST = 0x58, CLEARED = 0x54, NO = 0x7f, CLEAR_SERVICE = 0x14;
    private static final String VEHICLE = "2003 Porsche Cayenne Turbo";

    /** Names only where the vendor app showed one for this car; any other code is looked up by its number. */
    private static final Map<Integer, String> NAMES = new HashMap<>();
    static {
        NAMES.put(0x1525, "Cylinder bank 1, camshaft adjustment, electrical circuit fault");
    }

    /** What the engine computer said, without the address bytes and checksum; null when the frame is not sound. */
    private static byte[] body(byte[] r) {
        if (r == null || r.length < 5 || (r[0] & 0xc0) != 0x80) return null;
        if ((r[1] & 0xff) != 0xf1 || (r[2] & 0xff) != 0x10) return null;
        int n = r[0] & 0x3f, at = 3;
        if (n == 0) {                                   // a long answer carries its length in a byte of its own
            n = r[3] & 0xff;
            at = 4;
        }
        if (n < 1 || r.length != at + n + 1) return null;
        int sum = 0;
        for (int i = 0; i < r.length - 1; i++) sum += r[i] & 0xff;
        if ((sum & 0xff) != (r[r.length - 1] & 0xff)) return null;
        byte[] d = new byte[n];
        System.arraycopy(r, at, d, 0, n);
        return d;
    }

    /** The codes in a reply to {@link #READ}, in the order sent; null when the reply is not a sound list. */
    public static List<Integer> parse(byte[] reply) {
        byte[] d = body(reply);
        if (d == null || d.length < 2 || (d[0] & 0xff) != LIST) return null;
        int count = d[1] & 0xff;
        if (d.length != 2 + 3 * count) return null;
        List<Integer> codes = new ArrayList<>();
        for (int i = 0; i < count; i++) codes.add(((d[2 + 3 * i] & 0xff) << 8) | (d[3 + 3 * i] & 0xff));
        return codes;
    }

    /** True when the engine computer's answer to {@link #CLEAR} says the codes are erased. */
    static boolean cleared(byte[] reply) {
        byte[] d = body(reply);
        return d != null && d.length == 3 && (d[0] & 0xff) == CLEARED && (d[1] & 0xff) == 0xff && d[2] == 0;
    }

    /** True when the engine computer's answer to {@link #CLEAR} is a refusal. */
    static boolean refused(byte[] reply) {
        byte[] d = body(reply);
        return d != null && d.length >= 2 && (d[0] & 0xff) == NO && (d[1] & 0xff) == CLEAR_SERVICE;
    }

    /** The code as scan tools write it: a letter for the system, then four digits. */
    public static String label(int code) {
        return "PCBU".charAt((code >> 14) & 3) + String.format(Locale.US, "%04X", code & 0x3fff);
    }

    /** The code's name where the dash has one, otherwise a hint. */
    public static String text(int code) {
        String name = NAMES.get(code & 0xffff);
        return name != null ? name : "Tap to look it up";
    }

    /** Words to search the web with for one code. */
    public static String searchQuery(int code) {
        String name = NAMES.get(code & 0xffff);
        return label(code) + (name != null ? " " + name : "") + " " + VEHICLE;
    }

    /** The list as the Codes screen shows it: one line per code, or a sentence when there is nothing to list. */
    public static String listing(List<Integer> codes) {
        if (codes == null || codes.isEmpty()) return SciCodes.listing(codes);
        StringBuilder sb = new StringBuilder();
        for (int code : codes) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(label(code)).append("  ").append(text(code));
        }
        return sb.toString();
    }
}
