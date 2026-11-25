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

    static final String CRASH_FILE = "last-crash.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        catchCrashes();
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

    /**
     * A crash anywhere in the app is written to a file before Android closes it; the start-up screen shows it
     * next time, so it can be shared without a computer.
     */
    private void catchCrashes() {
        final Thread.UncaughtExceptionHandler system = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            try {
                String when = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
                String text = com.strickland.jeepdash.core.CrashReport.format(build(this), when, thread.getName(), e)
                        + "\nAndroid " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + "), "
                        + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
                try (java.io.FileOutputStream out = openFileOutput(CRASH_FILE, MODE_PRIVATE)) {
                    out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) {
                // nothing more can be done while crashing
            }
            if (system != null) system.uncaughtException(thread, e);
        });
    }

    /** The last crash's report, or null when there is none. */
    static String lastCrash(Context c) {
        java.io.File f = new java.io.File(c.getFilesDir(), CRASH_FILE);
        if (!f.isFile()) return null;
        try {
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    static void clearCrash(Context c) { new java.io.File(c.getFilesDir(), CRASH_FILE).delete(); }

    static int build(Context c) {
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return (int) (android.os.Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode);
        } catch (Exception e) {
            return 0;
        }
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
