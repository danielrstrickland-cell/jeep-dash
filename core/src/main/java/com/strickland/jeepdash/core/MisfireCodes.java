package com.strickland.jeepdash.core;

import java.util.List;

/** Picks the misfire codes (P0300 to P0308) out of a list of stored codes. */
public final class MisfireCodes {
    private MisfireCodes() { }

    /** True for a Jeep-line (Chrysler) code number that is a misfire: 6a-6e and ae-b1. */
    public static boolean isJeepMisfire(int raw) {
        int c = raw & 0xff;
        return (c >= 0x6a && c <= 0x6e) || (c >= 0xae && c <= 0xb1);
    }

    /** True for a Porsche code number that is P0300 to P0308. */
    public static boolean isPorscheMisfire(int code) {
        return (code & 0xffff) >= 0x0300 && (code & 0xffff) <= 0x0308;
    }

    /** The first misfire code in the list as a label (e.g. "P0303"), or null when there is none. */
    public static String first(List<Integer> codes, boolean porsche) {
        if (codes == null) return null;
        for (int c : codes) {
            if (porsche ? isPorscheMisfire(c) : isJeepMisfire(c))
                return porsche ? KwpCodes.label(c) : SciCodes.describe(c).pcode;
        }
        return null;
    }
}
