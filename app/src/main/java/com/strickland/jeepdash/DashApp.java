package com.strickland.jeepdash;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.strickland.jeepdash.core.Catalog;

/**
 * Remembers which car the app is set up for (the Jeep unless SETUP says otherwise) and applies it before
 * any screen or the link service starts, so they all read the same list of values.
 */
public class DashApp extends Application {
    static final String PREFS = "dash", VEHICLE = "vehicle", JEEP = "jeep", PORSCHE = "porsche";

    private static volatile int visible;        // this app's screens on view now (see inFront)

    @Override
    public void onCreate() {
        super.onCreate();
        Catalog.useVehicle(vehicle(this));
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity a) { visible++; }
            @Override public void onActivityStopped(Activity a) { visible = Math.max(0, visible - 1); }
            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityResumed(Activity a) { }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
    }

    /** True while one of this app's screens is on view (main thread counts; read from a receiver). */
    static boolean inFront() { return visible > 0; }

    static String vehicle(Context c) {
        return PORSCHE.equals(c.getSharedPreferences(PREFS, MODE_PRIVATE).getString(VEHICLE, JEEP)) ? PORSCHE : JEEP;
    }

    /**
     * Saves the other car and starts the app again from its first screen. A clean restart is the simple way
     * to be sure the link service, the dash and the start-up screen all switch together.
     */
    static void switchVehicle(Activity a, String id) {
        a.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(VEHICLE, id).commit();
        a.stopService(new Intent(a, LinkService.class));
        a.startActivity(new Intent(a, SplashActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        a.finishAffinity();
        new Handler(Looper.getMainLooper()).postDelayed(() -> Runtime.getRuntime().exit(0), 300);
    }
}
