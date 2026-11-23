package com.strickland.jeepdash;

import android.content.Context;
import android.content.SharedPreferences;

import com.strickland.jeepdash.core.Catalog;
import com.strickland.jeepdash.core.FuelUse;

/**
 * The one FuelUse for the app, fed by LinkService whichever screen is in front and read by the dash and the
 * fill-up dialog. The calibration factor and the estimate since the last fill-up (with its moving times) are
 * saved in prefs "dash", with their own keys for each car (like the learned gearing), at least once a minute
 * while counting, so an app killed mid-drive loses at most a little.
 */
final class FuelStore {
    private FuelStore() { }

    private static final double SAVE_EVERY_GAL = 0.05;     // write the estimate when it has grown this much
    private static final long SAVE_EVERY_MS = 60000;       // or this long has passed

    private static FuelUse fuel;
    private static boolean forPorsche;
    private static SharedPreferences prefs;
    private static double savedSinceFill;
    private static long savedAt;

    static synchronized FuelUse get(Context c) {
        boolean porsche = Catalog.porsche();
        if (fuel == null || porsche != forPorsche) {
            prefs = c.getApplicationContext().getSharedPreferences("dash", Context.MODE_PRIVATE);
            forPorsche = porsche;
            fuel = new FuelUse();
            fuel.restore(prefs.getFloat(key("fuel_factor"), 1f), prefs.getFloat(key("fuel_since_fill"), 0f),
                    prefs.getBoolean(key("fuel_fill_on"), false), prefs.getLong(key("fuel_fill_moving_ms"), 0),
                    prefs.getLong(key("fuel_fill_missing_ms"), 0));
            savedSinceFill = fuel.sinceFill();
        }
        return fuel;
    }

    /** Saves the fill-up figures when they have grown enough, or enough time has passed, to be worth a write. */
    static synchronized void keep(long nowMs) {
        if (fuel == null || !fuel.fillStarted()) return;
        if (Math.abs(fuel.sinceFill() - savedSinceFill) >= SAVE_EVERY_GAL || nowMs - savedAt >= SAVE_EVERY_MS) {
            savedAt = nowMs;
            save();
        }
    }

    static synchronized void save() {
        if (fuel == null) return;
        savedSinceFill = fuel.sinceFill();
        prefs.edit()
                .putLong(key("fuel_fill_moving_ms"), fuel.fillMovingMs())
                .putLong(key("fuel_fill_missing_ms"), fuel.fillMissingMs())
                .putFloat(key("fuel_factor"), (float) fuel.factor())
                .putFloat(key("fuel_since_fill"), (float) fuel.sinceFill())
                .putBoolean(key("fuel_fill_on"), fuel.fillStarted())
                .apply();
    }

    private static String key(String base) { return forPorsche ? base + "_p" : base; }
}
