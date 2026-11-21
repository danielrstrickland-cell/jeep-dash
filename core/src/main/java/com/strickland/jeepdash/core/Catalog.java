package com.strickland.jeepdash.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Every value the dash knows how to read. Adding a newly mapped Jeep value is one row here. */
public final class Catalog {
    private Catalog() { }

    private static double cToF(double c) { return c * 9 / 5 + 32; }

    private static final Map<String, Param> BY_ID = new LinkedHashMap<>();

    private static void add(Param p) { BY_ID.put(p.id, p); }

    static {
        add(new Param("rpm", "Engine RPM", "rpm", new int[]{0x0a, 0x0b}, (a, b) -> (a * 256 + b) / 8.0,
                0x0c, (a, b) -> (a * 256 + b) / 4.0, 0, 6000, 3));
        add(new Param("coolant", "Coolant", "°F", new int[]{0x16}, (a, b) -> cToF(a - 64),
                0x05, (a, b) -> cToF(a - 40), 100, 260, 1));
        add(new Param("map_v", "MAP sensor", "V", new int[]{0x17}, (a, b) -> a / 51.0,
                null, null, 0, 5, 2));
        add(new Param("spark", "Timing", "°", new int[]{0x2f}, (a, b) -> a,
                0x0e, (a, b) -> a / 2.0 - 64, -10, 40, 2));
        add(new Param("speed", "Speed", "mph", null, null,
                0x0d, (a, b) -> a * 0.621371, 0, 110, 3));
        add(new Param("iat", "Intake air", "°F", null, null,
                0x0f, (a, b) -> cToF(a - 40), 0, 200, 1));
        add(new Param("throttle", "Throttle", "%", null, null,
                0x11, (a, b) -> a * 100 / 255.0, 0, 100, 3));
        add(new Param("map_kpa", "MAP (generic)", "kPa", null, null,
                0x0b, (a, b) -> a, 0, 105, 2));
    }

    public static List<Param> all() { return Collections.unmodifiableList(new ArrayList<>(BY_ID.values())); }

    public static Param byId(String id) { return BY_ID.get(id); }
}
