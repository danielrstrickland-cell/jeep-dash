package com.strickland.jeepdash.core;

import java.util.List;
import java.util.Map;

/**
 * Which sound profile belongs to where the sound is going. A profile can be set to come on by itself when
 * the tablet plays through its own speakers, or through Bluetooth (the stereo); when the way out changes,
 * the first profile set for the new one takes over. Nothing changes for a way out no profile claims.
 */
public final class ProfileRoute {
    public static final String NONE = "", SPEAKER = "speaker", BLUETOOTH = "bluetooth", OTHER = "other";

    private ProfileRoute() { }

    /** The profile to switch to now that sound goes out this way, or null to stay on the active one. */
    public static String pick(List<String> ids, Map<String, String> routeOf, String active, String route) {
        if (route == null || route.isEmpty() || route.equals(routeOf.get(active))) return null;
        for (String id : ids) if (route.equals(routeOf.get(id))) return id;
        return null;
    }
}
