package com.strickland.jeepdash.core;

import java.util.List;

/** The Codes screen's wording for whichever car is chosen: the Jeep's Chrysler codes or the Porsche's P codes. */
public final class CodeText {
    private CodeText() { }

    public static String listing(List<Integer> codes) {
        return Catalog.porsche() ? KwpCodes.listing(codes) : SciCodes.listing(codes);
    }

    public static String searchQuery(int code) {
        return Catalog.porsche() ? KwpCodes.searchQuery(code) : SciCodes.searchQuery(code);
    }
}
