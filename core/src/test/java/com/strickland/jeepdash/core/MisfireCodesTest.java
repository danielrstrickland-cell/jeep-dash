package com.strickland.jeepdash.core;

import java.util.Arrays;
import java.util.Collections;

public class MisfireCodesTest {
    public static void jeepMisfireCodes() {
        T.eq("P0303", MisfireCodes.first(Arrays.asList(0x05, 0x96, 0x7e, 0x6d), false), "cyl 3");
        T.eq("P0300", MisfireCodes.first(Arrays.asList(0x6a), false), "multiple");
        T.eq("P0308", MisfireCodes.first(Arrays.asList(0xb1), false), "cyl 8");
        T.ok(MisfireCodes.first(Arrays.asList(0x05, 0x96, 0x7e), false) == null, "no misfire");
        T.ok(MisfireCodes.first(Collections.emptyList(), false) == null, "empty");
        T.ok(MisfireCodes.first(null, false) == null, "null list");
        T.ok(MisfireCodes.first(Arrays.asList(0x6f), false) == null, "6f is not a misfire");
    }

    public static void porscheMisfireCodes() {
        T.eq("P0302", MisfireCodes.first(Arrays.asList(0x0302), true), "cyl 2");
        T.ok(MisfireCodes.first(Arrays.asList(0x0171), true) == null, "P0171 is not");
    }
}
