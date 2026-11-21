package com.strickland.jeepdash.core;

/**
 * Dongle setup sequences and read requests, exactly as captured from the GearWrench app
 * (docs/protocol-notes.md). Read-only: nothing here changes how the engine runs.
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

    /** Chrysler SCI (7812.5 baud) setup. */
    public static final String[] SCI_SETUP = {
            "60 05 19 01", "60 01 49 c4 21", "60 02 00 00 1e 84 00 08", "60 03 00 78 07 d0 00 14 00 05",
            "61 04 00 01 10 01"};
    /** Sent before every SCI read: switches the dongle to 62500 baud. */
    public static final String SCI_FAST = "60 02 00 00 f4 24 00 08 ff";

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
