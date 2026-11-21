package com.strickland.jeepdash.core;

public class LiveValuesTest {
    public static void staleAfterThreeSeconds() {
        LiveValues v = new LiveValues();
        v.put("rpm", 720, 0);
        T.near(720, v.get("rpm", 2999), 0.001, "fresh at 2999 ms");
        T.eq(null, v.get("rpm", 3001), "stale at 3001 ms");
        T.eq(null, v.get("coolant", 0), "never seen");
    }
}
