package com.strickland.jeepdash.core;

/**
 * Dongle setup sequences and read requests, exactly as captured from the GearWrench app
 * (docs/protocol-notes.md). Read-only, with one exception the owner asked for on 2026-11-22: clearing the
 * Jeep's stored codes ({@link #allowedWrite}). Nothing here runs a test or changes how the engine runs.
 */
public final class Protocols {
    private Protocols() { }

    /** Generic OBD2 (ISO 9141-2): line setup before the 5-baud wake-up. */
    public static final String[] GENERIC_SETUP_PRE = {
            "60 05 02 01", "60 01 09 c5 92", "60 03 00 50 03 e8 00 37 00 05", "61 06 01"};

    /** The GearWrench app waits this long between the line setup and the wake-up. */
    public static final long GENERIC_PRE_WAIT_MS = 5000;

    /** 5-baud wake-up to address 0x33; the dongle answers with a type-02 reply carrying keywords 08 08. */
    public static final String GENERIC_INIT = "61 05 1e 33 02 02 01 e0 00 00 00 05";

    /** First request after the wake-up (supported PIDs); an answer here means the generic line is up. */
    public static final String GENERIC_FIRST_QUERY = "61 04 00 06 68 6a f1 01 00 c4 ff";

    /** Then: keep-alive message every 600 ms, keep-alive on, reply filter for the engine computer (48 6b d1). */
    public static final String[] GENERIC_SETUP_POST = {
            "60 04 00 06 68 6a f1 01 00 c4 02 58 01", "60 06 ff", "61 06 02 00 03 48 6b d1 00 00"};

    /** Turns the dongle's generic keep-alive off. Left on, it talks over the Jeep's own line. */
    public static final String STOP_KEEPALIVE = "60 06 00";

    /** What the GearWrench app sends when it leaves generic live data. */
    public static final String[] GENERIC_LEAVE = {"60 06 00", "60 0c 13 88 ff ff ff ff", "60 06 00"};

    /** The dongle's timing for the Jeep line as the GearWrench app sets it (part of the line setup). */
    public static final String SCI_TIMING = "60 03 00 78 07 d0 00 14 00 05";
    /**
     * Experimental fast refresh: the same timing with the first figure cut from 120 ms to 30 ms. In the
     * captures every Jeep-line answer comes back about 180 ms after the request although the bytes take
     * 2 ms on the wire, which fits the dongle waiting out 120 ms of silence before it calls the answer
     * complete. The GearWrench app never sends this value, so the runner watches for misreads and goes
     * back to the captured one on its own. It only changes how the dongle listens; nothing goes to the Jeep.
     */
    public static final String SCI_TIMING_FAST = "60 03 00 1e 07 d0 00 14 00 05";

    /** Chrysler SCI (7812.5 baud) setup. */
    public static final String[] SCI_SETUP = {
            "60 05 19 01", "60 01 49 c4 21", "60 02 00 00 1e 84 00 08", SCI_TIMING,
            "61 04 00 01 10 01"};
    /** Sent before every SCI read: switches the dongle to 62500 baud. */
    public static final String SCI_FAST = "60 02 00 00 f4 24 00 08 ff";

    /**
     * Back to 7812.5 baud. The GearWrench app sends this between fast reads and slow-speed requests; the
     * trailing ff makes the dongle take the engine computer out of its fast mode too.
     */
    public static final String SCI_SLOW = "60 02 00 00 1e 84 00 08 ff";

    /**
     * Porsche Cayenne (KWP2000 on the K-line, 10400 baud), as captured from the GearWrench app: line setup,
     * the 5-baud wake-up of the engine computer (address 01, answers with keywords ef 8f), then the dongle's
     * own "still here" message every 500 ms so the computer keeps the session open.
     */
    public static final String[] KWP_SETUP_PRE = {
            "60 05 01 01", "60 01 09 c5 02", "60 02 00 00 28 b0 00 08", "60 03 00 64 01 f4 00 37 00 05",
            "60 03 03 e8 03 e8 00 37 00 05"};
    public static final String KWP_INIT = "61 05 1e 01 02 02 00 41 00 00 00 05";
    public static final String[] KWP_SETUP_POST = {
            "60 04 00 05 81 10 f1 3e c0 01 f4 01", "60 06 ff", "60 03 00 32 01 f4 00 37 00 05"};

    private static final java.util.Set<String> FIXED = new java.util.HashSet<>();
    static {
        String[][] groups = {GENERIC_SETUP_PRE, {GENERIC_INIT, GENERIC_FIRST_QUERY}, GENERIC_SETUP_POST,
                {STOP_KEEPALIVE, SCI_FAST, SCI_SLOW, SCI_TIMING_FAST}, GENERIC_LEAVE, SCI_SETUP,
                KWP_SETUP_PRE, {KWP_INIT}, KWP_SETUP_POST, {KwpCodes.READ}};
        for (String[] g : groups) for (String c : g) FIXED.add(c);
    }

    /**
     * The read-only list. True only for the captured line-setup commands above, a generic "show current
     * data" request (mode 01), a Jeep-line read of the F4 live-data table and a Porsche "read measured
     * values" request (service 21) to the engine computer. Nothing that clears codes, runs a test or writes
     * anything can pass, whatever the rest of the app asks for; clearing codes has its own list below.
     */
    public static boolean allowed(byte[] inner) {
        if (inner == null || inner.length < 2) return false;
        if (FIXED.contains(Hex.str(inner))) return true;
        if ((inner[0] & 0xff) != 0x61 || (inner[1] & 0xff) != 0x04 || inner.length < 7) return false;
        int n = ((inner[2] & 0xff) << 8) | (inner[3] & 0xff);
        if (inner.length != 4 + n + 1 || inner[inner.length - 1] != 0x01) return false;
        if (n == 6 && (inner[4] & 0xff) == 0x82) {      // 82 10 f1 21 <group> <sum>
            return (inner[5] & 0xff) == 0x10 && (inner[6] & 0xff) == 0xf1 && (inner[7] & 0xff) == 0x21
                    && (inner[9] & 0xff) == ((0x82 + 0x10 + 0xf1 + 0x21 + (inner[8] & 0xff)) & 0xff);
        }
        if (n == 6) {                                   // 68 6a f1 01 <pid> <sum>
            return (inner[4] & 0xff) == 0x68 && (inner[5] & 0xff) == 0x6a && (inner[6] & 0xff) == 0xf1
                    && (inner[7] & 0xff) == 0x01
                    && (inner[9] & 0xff) == ((0x68 + 0x6a + 0xf1 + 0x01 + (inner[8] & 0xff)) & 0xff);
        }
        return (n == 2 || n == 3) && (inner[4] & 0xff) == 0xf4;      // f4 <id> [<id>]
    }

    /**
     * The one thing the dash may change in the engine computer: clearing its stored codes, as the frames
     * the GearWrench app sends for Clear DTC (two on the Jeep, one on the Porsche) and nothing else. Kept apart from {@link #allowed} so
     * that live data can never send them; only the clear itself asks this.
     */
    public static boolean allowedWrite(byte[] inner) {
        if (inner == null) return false;
        String hex = Hex.str(inner);
        return hex.equals(SciCodes.CLEAR) || hex.equals(SciCodes.CLEAR_ALL) || hex.equals(KwpCodes.CLEAR);
    }

    public static byte[] genericRequest(int pid) {
        int sum = (0x68 + 0x6a + 0xf1 + 0x01 + pid) & 0xff;
        return new byte[]{0x61, 0x04, 0x00, 0x06, 0x68, 0x6a, (byte) 0xf1, 0x01, (byte) pid, (byte) sum, 0x01};
    }

    public static byte[] sciRequest(int... ids) {
        byte[] b = new byte[4 + 1 + ids.length + 1];
        b[0] = 0x61; b[1] = 0x04; b[2] = 0x00; b[3] = (byte) (ids.length + 1);
        b[4] = (byte) 0xf4;
        for (int i = 0; i < ids.length; i++) b[5 + i] = (byte) ids[i];
        b[b.length - 1] = 0x01;
        return b;
    }

    /** Queries (61 04, 61 05, 60 cX status) get a reply; line configuration only gets an ack. */
    public static boolean wantsReply(byte[] inner) {
        if (inner.length < 2) return false;
        int a = inner[0] & 0xff, b = inner[1] & 0xff;
        return (a == 0x61 && (b == 0x04 || b == 0x05)) || (a == 0x60 && b >= 0xc0);
    }
}
