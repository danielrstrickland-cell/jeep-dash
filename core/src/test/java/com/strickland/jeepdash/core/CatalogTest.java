package com.strickland.jeepdash.core;

public class CatalogTest {
    static Double sci(String id, String hex) { return Catalog.byId(id).decode(Source.SCI, Hex.bytes(hex)); }
    static Double gen(String id, String hex) { return Catalog.byId(id).decode(Source.GENERIC, Hex.bytes(hex)); }

    public static void decodesSci() {
        T.near(729.75, sci("rpm", "16 0b ce"), 0.001, "SCI rpm");
        T.near(177.8, sci("coolant", "16 91"), 0.1, "SCI coolant F");
        T.near(2.06, sci("map_v", "17 69"), 0.01, "SCI MAP volts");
        T.near(7, sci("spark", "2f 07"), 0.001, "SCI spark");
    }

    public static void decodesGeneric() {
        T.near(720, gen("rpm", "48 6b d1 41 0c 0b 40 00"), 0.001, "generic rpm");
        T.near(13.7, gen("throttle", "48 6b d1 41 11 23 00"), 0.1, "generic throttle");
        T.near(127.4, gen("iat", "48 6b d1 41 0f 5d 00"), 0.1, "generic intake air F");
        T.near(0, gen("speed", "48 6b d1 41 0d 00 00"), 0.001, "generic speed");
    }

    public static void malformedReplyIsNull() {
        T.eq(null, sci("rpm", "16"), "SCI rpm too short");
        T.eq(null, gen("rpm", "48 6b d1 41 0d 0b 40 00"), "generic reply for another PID");
    }

    public static void slowMeansGenericOnly() {
        T.ok(Catalog.byId("speed").slow(), "speed has no SCI id yet");
        T.ok(!Catalog.byId("rpm").slow(), "rpm has SCI ids");
    }
}
