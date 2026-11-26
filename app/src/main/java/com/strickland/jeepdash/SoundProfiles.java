package com.strickland.jeepdash;

import android.content.SharedPreferences;
import com.strickland.jeepdash.core.ProfileRoute;
import com.strickland.jeepdash.core.SettingsPack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sound profiles: one set of settings for each place the owner listens (the Jeep, a room on the tablet's own
 * speakers). The profile in use lives in the ordinary Sound settings, so everything that reads them works
 * unchanged; switching packs those away under the old profile and unpacks the new one's in their place.
 * A profile holds what depends on the place: the Calibrate measurement, target, correction strength, the
 * adaptive settings, box tuning and learned cabin noise.
 */
final class SoundProfiles {
    private static final String IDS = "profile_ids", ACTIVE = "profile_active", NAME = "profile_name_",
            DATA = "profile_data_", ROUTE = "profile_route_";
    private static final String[] KEYS = {SoundPrefs.TARGET, SoundPrefs.BASS, SoundPrefs.TREBLE, SoundPrefs.CORR,
            SoundPrefs.SCENE_EQ, SoundPrefs.NOISE, SoundPrefs.STRENGTH, SoundPrefs.VOLT, SoundPrefs.PORT,
            SoundPrefs.TUNE, SoundPrefs.DROP, SoundPrefs.AUTO, SoundPrefs.MEASURED, SoundPrefs.VALID_LO,
            SoundPrefs.VALID_HI, SoundPrefs.NOISE_PROFILE};

    private SoundProfiles() { }

    /**
     * First run: the settings as they stand become "Room" (they were made on the tablet's own speakers) and a
     * fresh "Jeep" is added, each set to come on by itself for its own way out.
     */
    static void ensure(SharedPreferences p) {
        if (p.getString(IDS, null) != null) return;
        p.edit().putString(IDS, "p1,p2").putString(ACTIVE, "p1")
                .putString(NAME + "p1", "Room").putString(ROUTE + "p1", ProfileRoute.SPEAKER)
                .putString(NAME + "p2", "Jeep").putString(ROUTE + "p2", ProfileRoute.BLUETOOTH).apply();
    }

    static List<String> ids(SharedPreferences p) {
        List<String> out = new ArrayList<>();
        for (String id : p.getString(IDS, "").split(",")) if (!id.isEmpty()) out.add(id);
        return out;
    }

    private static String join(List<String> ids) {
        StringBuilder b = new StringBuilder();
        for (String id : ids) b.append(b.length() > 0 ? "," : "").append(id);
        return b.toString();
    }

    static String active(SharedPreferences p) { return p.getString(ACTIVE, ""); }

    static String name(SharedPreferences p, String id) { return p.getString(NAME + id, "Profile"); }

    /** The way out this profile comes on for by itself: one of {@link ProfileRoute}'s, or none. */
    static String route(SharedPreferences p, String id) { return p.getString(ROUTE + id, ProfileRoute.NONE); }

    /** Sets it, and takes that way out off any other profile: one profile each. */
    static void setRoute(SharedPreferences p, String id, String route) {
        SharedPreferences.Editor e = p.edit();
        if (!route.isEmpty()) for (String other : ids(p)) if (route.equals(route(p, other))) e.remove(ROUTE + other);
        e.putString(ROUTE + id, route).apply();
    }

    private static String snapshot(SharedPreferences p) {
        Map<String, Object> keep = new LinkedHashMap<>();
        Map<String, ?> all = p.getAll();
        for (String k : KEYS) if (all.get(k) != null) keep.put(k, all.get(k));
        return SettingsPack.pack(keep);
    }

    private static void restore(SharedPreferences.Editor e, String data) {
        for (String k : KEYS) e.remove(k);                    // an editor does its removals first
        for (Map.Entry<String, Object> x : SettingsPack.unpack(data).entrySet()) {
            Object v = x.getValue();
            if (v instanceof Boolean) e.putBoolean(x.getKey(), (Boolean) v);
            else if (v instanceof Integer) e.putInt(x.getKey(), (Integer) v);
            else if (v instanceof Float) e.putFloat(x.getKey(), (Float) v);
            else e.putString(x.getKey(), String.valueOf(v));
        }
    }

    /** Puts the profile in use away and brings this one's settings in. */
    static void switchTo(SharedPreferences p, String id) {
        String now = active(p);
        if (id.equals(now) || !ids(p).contains(id)) return;
        SharedPreferences.Editor e = p.edit();
        e.putString(DATA + now, snapshot(p));
        restore(e, p.getString(DATA + id, null));
        e.putString(ACTIVE, id).apply();
    }

    /** A new profile with fresh settings and no measurement; returns its id. It is not switched to. */
    static String create(SharedPreferences p, String name) {
        List<String> ids = ids(p);
        int n = 1;
        while (ids.contains("p" + n)) n++;
        String id = "p" + n;
        ids.add(id);
        p.edit().putString(IDS, join(ids)).putString(NAME + id, name).remove(DATA + id).remove(ROUTE + id).apply();
        return id;
    }

    static void rename(SharedPreferences p, String id, String name) { p.edit().putString(NAME + id, name).apply(); }

    /** Removes a profile (never the last one). Deleting the one in use brings the first other one in. */
    static void delete(SharedPreferences p, String id) {
        List<String> ids = ids(p);
        if (ids.size() < 2 || !ids.remove(id)) return;
        SharedPreferences.Editor e = p.edit();
        if (id.equals(active(p))) {
            restore(e, p.getString(DATA + ids.get(0), null));
            e.putString(ACTIVE, ids.get(0));
        }
        e.putString(IDS, join(ids)).remove(NAME + id).remove(DATA + id).remove(ROUTE + id).apply();
    }

    /** The sound now goes out this way: switch to the profile set for it, if there is one. True if it switched. */
    static boolean followRoute(SharedPreferences p, String route) {
        List<String> ids = ids(p);
        Map<String, String> routes = new HashMap<>();
        for (String id : ids) routes.put(id, route(p, id));
        String to = ProfileRoute.pick(ids, routes, active(p), route);
        if (to == null) return false;
        switchTo(p, to);
        return true;
    }
}
