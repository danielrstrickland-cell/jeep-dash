package com.strickland.jeepdash;

import android.content.Context;
import android.content.SharedPreferences;

import com.strickland.jeepdash.core.Catalog;
import com.strickland.jeepdash.core.NormalReadings;

/**
 * The one NormalReadings (learning alerts) for the app, fed by LinkService whichever screen is in front and read
 * by the dash and the Normal readings list. Kept on this tablet only, in prefs "dash" under each car's own name
 * (NormalReadings.storeKey), written at most once a minute while it is learning, and when it closes.
 */
final class NormalStore {
    private NormalStore() { }

    private static final long SAVE_EVERY_MS = 60000;

    private static NormalReadings normal;
    private static SharedPreferences prefs;
    private static long savedAt;

    static synchronized NormalReadings get(Context c) {
        boolean porsche = Catalog.porsche();
        if (normal == null || normal.porsche() != porsche) {
            save();                                 // the other car's, if it was loaded
            prefs = c.getApplicationContext().getSharedPreferences(DashApp.PREFS, Context.MODE_PRIVATE);
            normal = NormalReadings.restore(prefs.getString(NormalReadings.storeKey(porsche), null), porsche);
        }
        return normal;
    }

    /** Saves when something changed and a minute has passed since the last write. */
    static synchronized void keep(long nowMs) {
        if (normal == null || !normal.unsaved() || nowMs - savedAt < SAVE_EVERY_MS) return;
        savedAt = nowMs;
        save();
    }

    /** Saves now if anything changed (an accepted reading, learning started again, the service closing). */
    static synchronized void save() {
        if (normal == null || !normal.unsaved()) return;
        prefs.edit().putString(NormalReadings.storeKey(normal.porsche()), normal.save()).apply();
    }
}
