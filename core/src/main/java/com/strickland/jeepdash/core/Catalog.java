package com.strickland.jeepdash.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Every value the dash can show. Adding a newly mapped Jeep value is one row here. */
public final class Catalog {
    private Catalog() { }

    private static double cToF(double c) { return c * 9 / 5 + 32; }

    private static final Map<String, Param> BY_ID = new LinkedHashMap<>();

    private static void add(Param p) { BY_ID.put(p.id, p); }

    /** Jeep-line bytes that can go below zero (timing, fuel trims). */
    private static double signed(int a) { return a >= 128 ? a - 256 : a; }

    private static final double VOLT = 5 / 255.0;                     // 0-5 V sensor inputs
    private static final double KPA = 0.059756 * 6.894757;            // manifold and barometer counts

    /**
     * Jeep-line (SCI) ids and scalings come from two captures of the GearWrench app on this Jeep, checked
     * against the generic line and against the open-source ChryslerScanner tables (docs/protocol-notes.md).
     */
    static {
        add(new Param("rpm", "Engine RPM", "rpm", new int[]{0x0a, 0x0b}, (a, b) -> (a * 256 + b) / 8.0,
                0x0c, (a, b) -> (a * 256 + b) / 4.0, 0, 6000, 3));
        add(new Param("coolant", "Coolant", "°F", new int[]{0x16}, (a, b) -> cToF(a - 64),
                0x05, (a, b) -> cToF(a - 40), 100, 260, 1));
        add(new Param("map_v", "MAP sensor", "V", new int[]{0x17}, (a, b) -> a * VOLT,
                null, null, 0, 5, 2));
        add(new Param("spark", "Timing", "°", new int[]{0x2f}, (a, b) -> signed(a) * 0.5,
                0x0e, (a, b) -> a / 2.0 - 64, -10, 40, 2));
        add(new Param("speed", "Speed", "mph", new int[]{0x0c, 0x0d}, (a, b) -> (a * 256 + b) / 64.0,
                0x0d, (a, b) -> a * 0.621371, 0, 110, 3));
        add(new Param("iat", "Intake air", "°F", new int[]{0x1e}, (a, b) -> cToF(a - 64),
                0x0f, (a, b) -> cToF(a - 40), 0, 200, 1));
        add(new Param("throttle", "Throttle", "%", new int[]{0x12}, (a, b) -> a * 100 / 255.0,
                0x11, (a, b) -> a * 100 / 255.0, 0, 100, 3));
        add(new Param("map_kpa", "Manifold", "kPa", new int[]{0x18}, (a, b) -> a * KPA,
                0x0b, (a, b) -> a, 0, 105, 2));
        add(new Param("batt_v", "Battery", "V", new int[]{0x0f}, (a, b) -> a * 0.0625,
                null, null, 10, 16, 1));
        add(new Param("charge_v", "Charge target", "V", new int[]{0x3a}, (a, b) -> a * 0.0625,
                null, null, 10, 16, 1));
        add(new Param("load", "Engine load", "%", new int[]{0xe3}, (a, b) -> a * 100 / 255.0,
                0x04, (a, b) -> a * 100 / 255.0, 0, 100, 2));
        add(new Param("target_idle", "Target idle", "rpm", new int[]{0x35, 0x36}, (a, b) -> (a * 256 + b) / 8.0,
                null, null, 0, 2000, 1));
        add(new Param("iac", "Idle steps", "steps", new int[]{0x3e}, (a, b) -> a,
                null, null, 0, 200, 1));
        add(new Param("batt_temp", "Battery temp", "°F", new int[]{0x11}, (a, b) -> cToF(a - 64),
                null, null, 0, 200, 1));
        add(new Param("o2_front", "O2 front", "V", new int[]{0x1b}, (a, b) -> a * VOLT,
                null, null, 0, 5, 2));
        add(new Param("o2_rear", "O2 rear", "V", new int[]{0x48}, (a, b) -> a * VOLT,
                null, null, 0, 5, 1));
        add(new Param("stft", "Fuel trim short", "%", new int[]{0x44}, (a, b) -> signed(a) * 0.196,
                0x06, (a, b) -> (a - 128) * 100 / 128.0, -25, 25, 2));
        add(new Param("ltft", "Fuel trim long", "%", new int[]{0x2b}, (a, b) -> signed(a) * 0.196,
                0x07, (a, b) -> (a - 128) * 100 / 128.0, -25, 25, 1));
        add(new Param("inj_ms", "Injector", "ms", new int[]{0x27, 0x28}, (a, b) -> (a * 256 + b) / 256.0,
                null, null, 0, 20, 2));
        add(new Param("baro", "Barometer", "kPa", new int[]{0x19}, (a, b) -> a * KPA,
                null, null, 80, 105, 1));
        add(new Param("fuel_v", "Fuel sender", "V", new int[]{0xde}, (a, b) -> a * VOLT,
                null, null, 0, 5, 1));
        add(new Param("knock_v", "Knock sensor", "V", new int[]{0x1f}, (a, b) -> a * VOLT,
                null, null, 0, 5, 2));

        // From the tablet's GPS and accelerometer (see Motion); the dongle is never asked for these.
        add(Param.tablet("gps_mph", "GPS speed", "mph", 0, 110));
        add(Param.tablet("g_force", "G-force", "g", 0, 1.2));
        add(Param.tablet("g_accel", "Pull / braking", "g", -1, 1));
        add(Param.tablet("zero_sixty", "0-60 time", "s", 0, 20));
        add(Param.tablet("speedo_err", "Speedo vs GPS", "%", -15, 15));
    }

    // Which car the app is set up for. Chosen once in SETUP and applied when the app starts, so every screen
    // and the link service agree; the Jeep is the default.
    private static volatile boolean porsche;

    public static void useVehicle(String id) { porsche = "porsche".equals(id); }

    public static boolean porsche() { return porsche; }

    public static List<Param> all() {
        if (porsche) return PorscheCatalog.all();
        return Collections.unmodifiableList(new ArrayList<>(BY_ID.values()));
    }

    public static Param byId(String id) { return porsche ? PorscheCatalog.byId(id) : BY_ID.get(id); }
}
