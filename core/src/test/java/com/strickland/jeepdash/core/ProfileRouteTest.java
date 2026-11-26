package com.strickland.jeepdash.core;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Picking the sound profile that belongs to where the sound is going. */
public class ProfileRouteTest {
    private static final List<String> IDS = Arrays.asList("p1", "p2", "p3");

    private static Map<String, String> routes() {
        Map<String, String> m = new HashMap<>();
        m.put("p1", ProfileRoute.SPEAKER);       // Room
        m.put("p2", ProfileRoute.BLUETOOTH);     // Jeep
        return m;                                // p3 has none: only ever chosen by hand
    }

    public static void connectingToTheStereoSwitchesToItsProfile() {
        T.eq("p2", ProfileRoute.pick(IDS, routes(), "p1", ProfileRoute.BLUETOOTH), "Room to Jeep when Bluetooth takes the sound");
        T.eq("p1", ProfileRoute.pick(IDS, routes(), "p2", ProfileRoute.SPEAKER), "and back on the tablet's own speakers");
    }

    public static void theRightProfileIsLeftAlone() {
        T.ok(ProfileRoute.pick(IDS, routes(), "p2", ProfileRoute.BLUETOOTH) == null, "already on the Jeep profile");
    }

    public static void aRouteNoProfileClaimsChangesNothing() {
        T.ok(ProfileRoute.pick(IDS, routes(), "p1", ProfileRoute.OTHER) == null, "wired headphones: stay put");
        T.ok(ProfileRoute.pick(IDS, new HashMap<String, String>(), "p1", ProfileRoute.BLUETOOTH) == null, "nothing set to switch");
        T.ok(ProfileRoute.pick(IDS, routes(), "p3", null) == null, "route not known");
    }

    public static void aHandPickedProfileStillGivesWayWhenTheRouteChanges() {
        T.eq("p2", ProfileRoute.pick(IDS, routes(), "p3", ProfileRoute.BLUETOOTH), "from a profile with no route of its own");
    }
}
