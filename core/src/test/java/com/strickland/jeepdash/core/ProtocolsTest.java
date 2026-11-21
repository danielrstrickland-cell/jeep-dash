package com.strickland.jeepdash.core;

public class ProtocolsTest {
    public static void genericRequestMatchesCapture() {
        T.eq("61 04 00 06 68 6a f1 01 0c d0 01", Hex.str(Protocols.genericRequest(0x0c)), "generic RPM request");
    }

    public static void sciRequestMatchesCapture() {
        T.eq("61 04 00 03 f4 0a 0b 01", Hex.str(Protocols.sciRequest(0x0a, 0x0b)), "SCI RPM request");
        T.eq("61 04 00 02 f4 16 01", Hex.str(Protocols.sciRequest(0x16)), "SCI coolant request");
    }

    public static void onlyQueriesWaitForReplies() {
        T.ok(Protocols.wantsReply(Hex.bytes("61 04 00 02 f4 16 01")), "61 04 query");
        T.ok(Protocols.wantsReply(Hex.bytes(Protocols.GENERIC_INIT)), "61 05 wake-up");
        T.ok(Protocols.wantsReply(Hex.bytes("60 c3")), "60 c3 status");
        T.ok(!Protocols.wantsReply(Hex.bytes(Protocols.SCI_FAST)), "60 02 config");
        T.ok(!Protocols.wantsReply(Hex.bytes("61 06 01")), "61 06 config");
    }
}
