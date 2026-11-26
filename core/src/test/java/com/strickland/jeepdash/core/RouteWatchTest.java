package com.strickland.jeepdash.core;

/** A change in where the sound goes only counts once it has held: Bluetooth blips must not swap profiles. */
public class RouteWatchTest {

    public static void aRouteCountsOnlyAfterItHasHeld() {
        RouteWatch w = new RouteWatch();
        T.ok(w.settled(ProfileRoute.BLUETOOTH, 1000) == null, "just seen");
        T.ok(w.settled(ProfileRoute.BLUETOOTH, 1000 + RouteWatch.HOLD_MS - 1) == null, "not held long enough");
        T.eq(ProfileRoute.BLUETOOTH, w.settled(ProfileRoute.BLUETOOTH, 1000 + RouteWatch.HOLD_MS), "held: it counts");
        T.eq(ProfileRoute.BLUETOOTH, w.settled(ProfileRoute.BLUETOOTH, 999999), "and goes on counting");
    }

    public static void aBlipStartsTheWaitAgain() {
        RouteWatch w = new RouteWatch();
        w.settled(ProfileRoute.BLUETOOTH, 0);
        w.settled(ProfileRoute.BLUETOOTH, RouteWatch.HOLD_MS);
        T.ok(w.settled(ProfileRoute.SPEAKER, RouteWatch.HOLD_MS + 5000) == null, "Bluetooth dropped a moment ago");
        T.ok(w.settled(ProfileRoute.BLUETOOTH, RouteWatch.HOLD_MS + 9000) == null, "and came back: nothing has settled");
        T.eq(ProfileRoute.BLUETOOTH, w.settled(ProfileRoute.BLUETOOTH, 2 * RouteWatch.HOLD_MS + 9000),
                "the speakers never held long enough to count");
    }

    public static void anUnknownRouteNeverSettles() {
        RouteWatch w = new RouteWatch();
        w.settled("", 0);
        T.ok(w.settled("", RouteWatch.HOLD_MS * 3) == null, "nothing known, nothing to act on");
    }
}
