package com.strickland.jeepdash;

import android.content.Context;
import android.content.SharedPreferences;

import com.strickland.jeepdash.core.Catalog;
import com.strickland.jeepdash.core.FuelUse;

/**
 * The one FuelUse for the app, kept for as long as the app runs so a rotated or reopened dash keeps the
 * drive's figures. The calibration factor and the estimate since the last fill-up are saved in prefs "dash",
 * with their own keys for each car (like the learned gearing). Main thread only.
 */
final class FuelStore {
    private FuelStore() { }

    private static final double SAVE_EVERY_GAL = 0.05;     // write the estimate when it has grown this much

    private static FuelUse fuel;
    private static boolean forPorsche;
    private static SharedPreferences prefs;
    private static double savedSinceFill;

    static FuelUse get(Context c) {
        boolean porsche = Catalog.porsche();
        if (fuel == null || porsche != forPorsche) {
            prefs = c.getApplicationContext().getSharedPreferences("dash", Context.MODE_PRIVATE);
            forPorsche = porsche;
            fuel = new FuelUse();
            fuel.restore(prefs.getFloat(key("fuel_factor"), 1f), prefs.getFloat(key("fuel_since_fill"), 0f),
                    prefs.getBoolean(key("fuel_fill_on"), false));
            savedSinceFill = fuel.sinceFill();
        }
        return fuel;
    }

    /** Saves the estimate since the fill-up when it has grown enough to be worth a write. */
    static void keep() {
        if (fuel != null && Math.abs(fuel.sinceFill() - savedSinceFill) >= SAVE_EVERY_GAL) save();
    }

    static void save() {
        if (fuel == null) return;
        savedSinceFill = fuel.sinceFill();
        prefs.edit()
                .putFloat(key("fuel_factor"), (float) fuel.factor())
                .putFloat(key("fuel_since_fill"), (float) fuel.sinceFill())
                .putBoolean(key("fuel_fill_on"), fuel.fillStarted())
                .apply();
    }

    private static String key(String base) { return forPorsche ? base + "_p" : base; }
}
