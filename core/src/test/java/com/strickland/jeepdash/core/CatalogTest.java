package com.strickland.jeepdash.core;

public class CatalogTest {
    static Double sci(String id, String hex) { return Catalog.byId(id).decode(Source.SCI, Hex.bytes(hex)); }
    static Double gen(String id, String hex) { return Catalog.byId(id).decode(Source.GENERIC, Hex.bytes(hex)); }

    public static void decodesSci() {
        T.near(729.75, sci("rpm", "16 0b ce"), 0.001, "SCI rpm");
        T.near(177.8, sci("coolant", "16 91"), 0.1, "SCI coolant F");
        T.near(2.06, sci("map_v", "17 69"), 0.01, "SCI MAP volts");
        T.near(3.5, sci("spark", "2f 07"), 0.001, "SCI spark is in half degrees");
        T.near(-1, sci("spark", "2f fe"), 0.001, "SCI spark after top dead centre");
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

    public static void everyValueIsOnTheJeepLine() {
        for (Param p : Catalog.all()) if (!p.tablet) T.ok(!p.slow(), p.id + " must have a Jeep-line id");
    }

    /** Raw answers from the 22:31 capture of the GearWrench app; formulas cross-checked with ChryslerScanner. */
    public static void decodesTheNewlyMappedJeepLineValues() {
        T.near(60, sci("speed", "0f 0d 00"), 0.001, "speed is 1/64 mph");
        T.near(0, sci("speed", "00 0d 00"), 0.001, "parked");
        T.near(134.6, sci("iat", "1e 79"), 0.1, "intake air");
        T.near(13.7, sci("throttle", "12 23"), 0.1, "throttle matches the generic 13.7 % at rest");
        T.near(52.7, sci("map_kpa", "18 80"), 0.1, "manifold kPa at idle");
        T.near(13.5, sci("batt_v", "0f d8"), 0.001, "battery");
        T.near(13.6875, sci("charge_v", "3a db"), 0.001, "charge target");
        T.near(1.568, sci("load", "e3 04"), 0.001, "engine load");
        T.near(704, sci("target_idle", "16 36 00"), 0.001, "target idle");
        T.near(101, sci("iac", "3e 65"), 0.001, "idle motor steps");
        T.near(95, sci("batt_temp", "11 63"), 0.1, "battery temperature");
        T.near(0.137, sci("o2_front", "1b 07"), 0.001, "front O2");
        T.near(4.627, sci("o2_rear", "48 ec"), 0.001, "rear O2, cold");
        T.near(1.568, sci("stft", "44 08"), 0.001, "short-term trim adding fuel");
        T.near(-0.98, sci("stft", "44 fb"), 0.001, "short-term trim pulling fuel");
        T.near(-11.76, sci("ltft", "2b c4"), 0.001, "long-term trim");
        T.near(6.426, sci("inj_ms", "06 28 6d"), 0.001, "injector pulse");
        T.near(98.5, sci("baro", "19 ef"), 0.1, "barometer");
        T.near(2.176, sci("fuel_v", "de 6f"), 0.001, "fuel sender");
        T.near(0, sci("knock_v", "1f 00"), 0.001, "knock sensor");
        T.near(-3.125, gen("stft", "48 6b d1 41 06 7c 00"), 0.001, "generic short-term trim");
        T.near(50.2, gen("load", "48 6b d1 41 04 80 00"), 0.1, "generic load");
    }

    public static void tabletValuesAreNeverAskedOfTheEngine() {
        for (String id : new String[]{"gps_mph", "g_force", "g_accel", "zero_sixty", "speedo_err"}) {
            Param p = Catalog.byId(id);
            T.ok(p != null && p.tablet, id + " comes from the tablet");
            T.ok(p.sciIds == null && p.pid == null, id + " has nothing to request");
        }
        T.ok(!Catalog.byId("rpm").tablet, "engine values are not tablet values");
    }

    public static void aTabletValueIsNotAGenericLineValue() {
        Param p = Catalog.byId("g_force");
        T.ok(!p.slow(), "nothing to wait for on the generic line");
        T.ok(p.decode(Source.SCI, new byte[]{0x0a, 0x10}) == null && p.decode(Source.GENERIC, new byte[8]) == null,
                "and no reply from the Jeep is ever taken for it");
    }
}
