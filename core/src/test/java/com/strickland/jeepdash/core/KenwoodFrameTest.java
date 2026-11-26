package com.strickland.jeepdash.core;

/** The Kenwood OpenLink wire format: the CRC-16/ARC table, byte stuffing, and frame round-trips. */
public class KenwoodFrameTest {

    public static void crcMatchesKnownArcValues() {
        // CRC-16/ARC of the ASCII "123456789" is 0xBB3D, the standard check value.
        byte[] s = "123456789".getBytes();
        T.eq(0xBB3D, KenwoodFrame.crc16(s, s.length), "ARC check value");
        T.eq(0, KenwoodFrame.crc16(new byte[0], 0), "empty is 0");
    }

    public static void stuffEscapesTheThreeSpecialBytes() {
        byte[] raw = {0x7E, 0x01, 0x7C, 0x7D, 0x02};
        byte[] st = KenwoodFrame.stuff(raw);
        // 7E->7D 5E, 7C->7D 5C, 7D->7D 5D; two plain bytes pass through
        byte[] want = {0x7D, 0x5E, 0x01, 0x7D, 0x5C, 0x7D, 0x5D, 0x02};
        T.eq(want, st, "each flag byte becomes ESC + (byte ^ 0x20)");
    }

    public static void stuffUnstuffRoundTrips() {
        byte[] raw = {0x00, 0x7E, 0x7D, 0x7C, 0x55, (byte) 0xAA, 0x7E};
        byte[] back = KenwoodFrame.unstuff(KenwoodFrame.stuff(raw), 0, KenwoodFrame.stuff(raw).length);
        T.eq(raw, back, "unstuff reverses stuff");
    }

    public static void frameParsesBackToPayload() {
        byte[] cmd = {0x10, 0x20, 0x30, 0x40, 0x50};
        byte[] payload = {0x01, 0x7E, 0x33, 0x7C};     // payload containing bytes that must be stuffed
        byte[] wire = KenwoodFrame.frame(cmd, KenwoodFrame.CMD, payload);
        T.eq(0x7E, wire[0] & 0xFF, "starts with the 0x7E flag");
        T.eq(0x7C, wire[wire.length - 1] & 0xFF, "ends with 0x7C");

        KenwoodFrame.Parsed p = KenwoodFrame.parse(wire);
        T.ok(p != null, "a well-formed frame parses");
        T.eq(KenwoodFrame.CMD, p.ptype, "ptype preserved");
        T.eq(cmd, p.cmdId, "command id preserved");
        T.eq(payload, p.payload, "payload preserved through stuffing and CRC");
    }

    public static void parseRejectsCorruptedFrame() {
        byte[] wire = KenwoodFrame.frame(new byte[]{1, 2, 3, 4, 5}, KenwoodFrame.CMD, new byte[]{9, 9, 9});
        wire[13] ^= 0x01;                               // flip a payload bit, CRC should fail
        T.ok(KenwoodFrame.parse(wire) == null, "bad CRC is rejected");
        T.ok(KenwoodFrame.parse(new byte[]{0x7E, 0x7C}) == null, "too short is rejected");
    }

    public static void emptyPayloadFrames() {
        byte[] wire = KenwoodFrame.frame(new byte[]{0, 0, 0, 0, 0}, KenwoodFrame.ACK, new byte[0]);
        KenwoodFrame.Parsed p = KenwoodFrame.parse(wire);
        T.ok(p != null, "empty-payload frame parses");
        T.eq(0, p.payload.length, "no payload");
        T.eq(KenwoodFrame.ACK, p.ptype, "ack type preserved");
    }
}
