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

    public static void readOnlyListTakesEverythingTheDashSends() {
        String[][] groups = {Protocols.GENERIC_SETUP_PRE, {Protocols.GENERIC_INIT, Protocols.GENERIC_FIRST_QUERY},
                Protocols.GENERIC_SETUP_POST, {Protocols.STOP_KEEPALIVE, Protocols.SCI_FAST, Protocols.SCI_SLOW},
                Protocols.GENERIC_LEAVE, Protocols.SCI_SETUP};
        for (String[] g : groups) for (String c : g) T.ok(Protocols.allowed(Hex.bytes(c)), c);
        T.ok(Protocols.allowed(Protocols.genericRequest(0x0c)), "generic read");
        T.ok(Protocols.allowed(Protocols.sciRequest(0x0a, 0x0b)), "Jeep-line read, two ids");
        T.ok(Protocols.allowed(Protocols.sciRequest(0x16)), "Jeep-line read, one id");
    }

    public static void readOnlyListRefusesAnythingThatCouldChangeTheJeep() {
        String[] bad = {
                "61 04 00 06 68 6a f1 04 00 c7 01",        // generic mode 04: clear codes
                "61 04 00 06 68 6a f1 08 00 cb 01",        // generic mode 08: control a system
                "61 04 00 06 68 6a f1 01 0c d0 02",        // not the captured request shape
                "61 04 00 02 13 01 01",                    // Jeep line: actuator test
                "61 04 00 02 17 00 01",                    // Jeep line: erase codes
                "61 04 00 02 f5 0a 01",                    // Jeep line: another table
                "61 04 00 01 f4 01",                       // a read of nothing
                "61 05 1e 10 02 02 01 e0 00 00 00 05",     // wake-up to another address
                "60 07 01",                                // dongle setting the app never used here
        };
        for (String c : bad) T.ok(!Protocols.allowed(Hex.bytes(c)), "must refuse " + c);
    }

    // ---------------- the value scan (Jeep line) ----------------

    public static void scanRequestIsTheLiveDataReadWithOneId() {
        T.eq("61 04 00 02 f4 00 01", Hex.str(Protocols.scanRequest(0x00)), "first id");
        T.eq("61 04 00 02 f4 18 01", Hex.str(Protocols.scanRequest(0x18)), "manifold pressure's id");
        T.eq(Hex.str(Protocols.sciRequest(0x18)), Hex.str(Protocols.scanRequest(0x18)), "the same bytes live data sends");
        T.eq("61 04 00 02 f4 ef 01", Hex.str(Protocols.scanRequest(0xef)), "last id");
    }

    public static void scanRequestRefusesIdsOutsideTheTable() {
        for (int id : new int[]{-1, 0xf0, 0xf4, 0xfe, 0xff, 0x100}) {
            try {
                Protocols.scanRequest(id);
                T.ok(false, "must refuse id " + id);
            } catch (IllegalArgumentException expected) {
                // f0 to ff pick another table (fe also drops the engine computer out of its fast mode)
            }
        }
    }

    public static void scanListTakesOnlyOneIdReadsOfTheTable() {
        for (int id = 0; id <= 0xef; id++) T.ok(Protocols.allowedScan(Protocols.scanRequest(id)), "id " + id);
        String[] bad = {
                "61 04 00 03 f4 0a 0b 01",                 // two ids: live data, not the scan
                "61 04 00 02 f4 f0 01", "61 04 00 02 f4 fe 01", "61 04 00 02 f4 ff 01",
                "61 04 00 02 f5 0a 01",                    // another table
                "61 04 00 01 10 01",                       // stored-code list
                "61 04 00 01 17 01", "61 04 00 02 23 01 01",       // the two erase frames
                "61 04 00 02 13 01 01",                    // actuator test
                "61 04 00 02 f4 18 02",                    // not the captured flag
                "61 04 00 02 f4 18", "61 04 00 02 f4 18 01 00",    // cut short, or something tacked on
                "61 04 00 03 f4 18 01",                    // length says two
                Protocols.SCI_FAST, Protocols.SCI_SLOW, Protocols.SCI_TIMING,
                "61 04 00 06 68 6a f1 01 0c d0 01",        // generic read
        };
        for (String c : bad) T.ok(!Protocols.allowedScan(Hex.bytes(c)), "must refuse " + c);
        T.ok(!Protocols.allowedScan(null), "nothing");
    }

    public static void liveDataReadsStayInsideTheTable() {
        T.ok(!Protocols.allowed(Hex.bytes("61 04 00 02 f4 fe 01")), "fe would drop the engine computer out of its fast mode");
        T.ok(!Protocols.allowed(Hex.bytes("61 04 00 02 f4 f2 01")), "f0 to ff pick another table");
        T.ok(!Protocols.allowed(Hex.bytes("61 04 00 03 f4 0a f4 01")), "nor as the second id");
        try {
            Catalog.useVehicle("jeep");
            for (Param p : Catalog.all())
                if (p.sciIds != null) T.ok(Protocols.allowed(Protocols.sciRequest(p.sciIds)), "the dash's own " + p.id);
        } finally {
            Catalog.useVehicle("jeep");
        }
    }
}
