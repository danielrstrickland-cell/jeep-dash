package com.strickland.jeepdash.core;

public class LiveValuesTest {
    public static void staleAfterThreeSeconds() {
        LiveValues v = new LiveValues();
        v.put("rpm", 720, 0);
        T.near(720, v.get("rpm", 2999), 0.001, "fresh at 2999 ms");
        T.eq(null, v.get("rpm", 3001), "stale at 3001 ms");
        T.eq(null, v.get("coolant", 0), "never seen");
    }

    public static void perValueStaleTime() {
        LiveValues v = new LiveValues();
        v.setStaleMs("speed", 18000);
        v.put("speed", 40, 0);
        T.near(40, v.get("speed", 17000), 0.001, "slow value kept for its own stale time");
        T.eq(null, v.get("speed", 18001), "then stale");
    }

    public static void holdKeepsFreshValuesUntilItEnds() {
        LiveValues v = new LiveValues();
        v.put("rpm", 720, 9000);
        v.put("coolant", 180, 5000);
        v.hold(10000, 16000);
        T.near(720, v.get("rpm", 15000), 0.001, "fresh when the hold began: kept during it");
        T.eq(null, v.get("coolant", 15000), "already stale when the hold began: stays stale");
        T.eq(null, v.get("rpm", 16001), "hold over: stale again");
        v.put("rpm", 700, 17000);
        v.endHold(17000);
        T.eq(null, v.get("rpm", 20001), "after endHold the normal 3 s applies");
    }

    public static void clearDropsReadingsAndAnyHold() {
        LiveValues v = new LiveValues();
        v.put("rpm", 700, 0);
        v.hold(1000, 20000);
        v.clear();
        T.eq(null, v.get("rpm", 1500), "cleared");
        v.put("rpm", 710, 30000);
        T.eq(null, v.get("rpm", 40000), "the old hold is gone too");
    }

    public static void aReadingKnowsWhenItWasTaken() {
        LiveValues v = new LiveValues();
        T.ok(v.timeOf("speed") == null, "never read");
        v.put("speed", 30, 1234);
        T.eq(1234L, v.timeOf("speed"), "the time it was put");
    }
}
