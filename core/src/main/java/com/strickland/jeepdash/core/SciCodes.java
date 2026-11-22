package com.strickland.jeepdash.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Stored trouble codes on the Jeep line: reading the list, naming each code, and the dash's one write to
 * the engine computer, which clears them. Everything here is what the GearWrench app sent this Jeep for
 * Read DTC and Clear DTC (captures of 2026-11-20 and 2026-11-22, docs/protocol-notes.md).
 */
public final class SciCodes {
    private SciCodes() { }

    /** Asks for the stored-code list. Answer: 10, one byte per code, fe, checksum. */
    public static final String READ = "61 04 00 01 10 01";
    /** Erases the stored codes. Answer: 17 e0 when done, 17 00 when the computer refuses (engine running). */
    public static final String CLEAR = "61 04 00 01 17 01";
    /** Second half of the GearWrench app's Clear DTC: erase all fault data. */
    public static final String CLEAR_ALL = "61 04 00 02 23 01 01";

    private static final int LIST = 0x10, ERASE = 0x17, DONE = 0xe0, REFUSED = 0x00, NOT_A_CODE = 0xfd, END = 0xfe;

    public enum Outcome {
        /** The engine computer confirmed the erase. */
        CLEARED,
        /** Nothing was sent: the engine is turning. */
        ENGINE_RUNNING,
        /** Nothing was sent: the engine speed could not be read, so a stopped engine is not certain. */
        NOT_ANSWERING,
        /** The engine computer refused. */
        DENIED,
        /** The engine computer did not confirm the erase. */
        FAILED
    }

    /** How a clear went, and the codes read back afterwards (null when that read got no answer). */
    public static final class Cleared {
        public final Outcome outcome;
        public final List<Integer> left;
        Cleared(Outcome outcome, List<Integer> left) { this.outcome = outcome; this.left = left; }
    }

    /** One code: its Chrysler number, its P number where that is confirmed, and its name. */
    public static final class Code {
        public final int raw;
        public final String pcode, text;
        Code(int raw, String pcode, String text) { this.raw = raw; this.pcode = pcode; this.text = text; }

        /** The P number when it is known for certain, otherwise the Chrysler number. */
        public String label() {
            return pcode != null ? pcode : String.format(Locale.US, "Chrysler code %02X", raw);
        }
    }

    private static final Map<Integer, String> NAMES = new HashMap<>(), PCODES = new HashMap<>();
    static {
        for (String row : SciCodeNames.ROWS) {
            int bar = row.indexOf('|');
            NAMES.put(Integer.parseInt(row.substring(0, bar), 16), row.substring(bar + 1));
        }
        // P numbers only where they are certain: the four the GearWrench app showed for this Jeep, and the
        // misfire codes, whose P numbers are the same on every car.
        PCODES.put(0x05, "P1682");
        PCODES.put(0x96, "P0463");
        PCODES.put(0x7e, "P0138");
        for (int cyl = 0; cyl <= 4; cyl++) PCODES.put(0x6a + cyl, "P030" + cyl);
        for (int cyl = 5; cyl <= 8; cyl++) PCODES.put(0xae + cyl - 5, "P030" + cyl);
    }

    /** The codes in a reply to {@link #READ}, in the order sent; null when the reply is not a sound list. */
    public static List<Integer> parse(byte[] reply) {
        if (reply == null || reply.length < 3 || (reply[0] & 0xff) != LIST) return null;
        int last = reply.length - 1, sum = 0;
        for (int i = 0; i < last; i++) sum += reply[i] & 0xff;
        if ((sum & 0xff) != (reply[last] & 0xff) || (reply[last - 1] & 0xff) != END) return null;
        List<Integer> codes = new ArrayList<>();
        for (int i = 1; i < last - 1; i++) {
            int c = reply[i] & 0xff;
            if (c != NOT_A_CODE && c != END) codes.add(c);
        }
        return codes;
    }

    public static Code describe(int raw) {
        String name = NAMES.get(raw & 0xff);
        return new Code(raw & 0xff, PCODES.get(raw & 0xff), name != null ? name : "Not in the dash's list yet");
    }

    /** The engine and its computer are from a 1998 5.9, whatever body they sit in. */
    private static final String VEHICLE = "1998 Jeep Grand Cherokee 5.9";

    /** Words to search the web with for one code: its name, or its Chrysler number when the list has no name. */
    public static String searchQuery(int raw) {
        String name = NAMES.get(raw & 0xff);
        if (name == null)
            return String.format(Locale.US, "Chrysler engine computer fault code %02X hex %s", raw & 0xff, VEHICLE);
        String p = PCODES.get(raw & 0xff);
        return (p != null ? p + " " : "") + name + " " + VEHICLE;
    }

    /** A Google search for those words. */
    public static String searchUrl(String query) {
        try {
            return "https://www.google.com/search?q=" + java.net.URLEncoder.encode(query, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);     // UTF-8 is always there
        }
    }

    /** The list as the Codes screen shows it: one line per code, or a sentence when there is nothing to list. */
    public static String listing(List<Integer> codes) {
        if (codes == null) return "The engine computer did not answer. Turn the key on and try again.";
        if (codes.isEmpty()) return "No stored codes.";
        StringBuilder sb = new StringBuilder();
        for (int raw : codes) {
            Code c = describe(raw);
            if (sb.length() > 0) sb.append('\n');
            sb.append(c.label()).append("  ").append(c.text);
        }
        return sb.toString();
    }

    /** What the Codes screen says after a clear. */
    public static String say(Outcome o) {
        switch (o) {
            case CLEARED: return "Codes cleared. The engine computer confirmed it.";
            case ENGINE_RUNNING: return "Nothing was cleared: the engine is running. Stop the engine, leave the key on and try again.";
            case NOT_ANSWERING: return "Nothing was cleared: the engine computer did not answer. Turn the key on and try again.";
            case DENIED: return "The engine computer refused to clear the codes. Stop the engine, leave the key on and try again.";
            default: return "The engine computer did not confirm the clear. Read the codes again to see what is stored.";
        }
    }

    /** True when the engine computer's answer to {@link #CLEAR} says the codes are erased. */
    static boolean erased(byte[] reply) {
        return reply != null && reply.length >= 2 && (reply[0] & 0xff) == ERASE && (reply[1] & 0xff) == DONE;
    }

    /** True when the engine computer's answer to {@link #CLEAR} is a refusal. */
    static boolean refused(byte[] reply) {
        return reply != null && reply.length >= 2 && (reply[0] & 0xff) == ERASE && (reply[1] & 0xff) == REFUSED;
    }

    static List<Integer> none() { return Collections.emptyList(); }
}
