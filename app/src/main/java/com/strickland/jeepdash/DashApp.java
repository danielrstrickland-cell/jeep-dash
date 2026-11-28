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
        // the last run's crash report is written now, while the system log still has why it stopped
        Thread keep = new Thread(() -> exitReport(this, false), "keep-crash-report");
        keep.setDaemon(true);
        keep.start();
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

    /**
     * After any crash: 3D off for this build, and a Jeep dash left on Holo goes back to Jeep style, so the next
     * start opens whatever broke. Returns a line for the crash box saying what was changed.
     */
    static String safeAfterCrash(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences(PREFS, MODE_PRIVATE);
        android.content.SharedPreferences.Editor e = p.edit()
                .putLong(DashActivity.SCENE_OFF, build(c))
                .remove(DashActivity.SCENE_LIVE);
        String note = "\n\nSafe start: the 3D view is off until the next update.";
        if ("holo".equals(p.getString("look", null))) {
            e.putString("look", "jeep");
            note += " The dash was switched from Holo to Jeep style; the look button switches back.";
        }
        e.commit();
        return note;
    }

    private static final String EXIT_SEEN = "exit_seen_v2";   // v2: show the last crash again, read properly

    /**
     * Android's own record of how the app last ended, when it was a crash not shown yet: this catches native
     * crashes (the 3D engine) that the Java handler above never sees. Its trace is the crash "tombstone" (or the
     * not-responding trace); its readable text is included. Null when there is nothing new.
     */
    static String lastExit(Context c) { return exitReport(c, true); }

    private static boolean better(android.app.ApplicationExitInfo a, android.app.ApplicationExitInfo b, boolean newestOnly) {
        if (!newestOnly) {
            boolean ca = a.getReason() >= 4 && a.getReason() <= 7, cb = b.getReason() >= 4 && b.getReason() <= 7;
            if (ca != cb) return ca;
        }
        return a.getTimestamp() > b.getTimestamp();
    }

    /** The most recent crash Android remembers, shown or not (the menu's Last crash report); null when none. */
    static String latestCrash(Context c) { return exitReport(c, false); }

    private static String exitReport(Context c, boolean onlyNew) {
        if (android.os.Build.VERSION.SDK_INT < 30) return null;
        try {
            android.app.ActivityManager am = c.getSystemService(android.app.ActivityManager.class);
            if (am == null) return null;
            java.util.List<android.app.ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(null, 0, 8);
            android.content.SharedPreferences p = c.getSharedPreferences(PREFS, MODE_PRIVATE);
            long seen = onlyNew ? p.getLong(EXIT_SEEN, 0) : 0, newest = p.getLong(EXIT_SEEN, 0);
            android.app.ApplicationExitInfo pick = null;
            for (android.app.ApplicationExitInfo e : list) {
                if (e.getTimestamp() <= seen) continue;
                newest = Math.max(newest, e.getTimestamp());
                boolean worth = com.strickland.jeepdash.core.CrashReport.worthShowing(e.getReason());
                // the menu's report prefers a real crash over a memory or signal kill that came after it
                if (worth && (pick == null || better(e, pick, onlyNew))) pick = e;
            }
            if (onlyNew) p.edit().putLong(EXIT_SEEN, newest).apply();
            if (pick == null) return null;
            // written down at the first start after the crash, with its log; later starts read that copy
            java.io.File kept = new java.io.File(c.getFilesDir(), "exit-" + pick.getTimestamp() + ".txt");
            if (kept.exists() && kept.length() < 256 * 1024) {
                try {
                    return new String(java.nio.file.Files.readAllBytes(kept.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                } catch (java.io.IOException ignored) {
                    // unreadable: build it again below
                }
            }
            StringBuilder sb = new StringBuilder();
            String when = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date(pick.getTimestamp()));
            sb.append("Jeep Dash dash-").append(build(c)).append(": last exit was a ")
                    .append(com.strickland.jeepdash.core.CrashReport.reasonName(pick.getReason()))
                    .append(" at ").append(when).append('\n');
            if (pick.getDescription() != null) sb.append("Description: ").append(pick.getDescription()).append('\n');
            sb.append("Process ").append(pick.getProcessName()).append(", status ").append(pick.getStatus())
                    .append(", importance ").append(pick.getImportance())
                    .append(", memory ").append(pick.getPss() / 1024).append(" MB pss / ").append(pick.getRss() / 1024).append(" MB rss\n");
            sb.append("Android ").append(android.os.Build.VERSION.RELEASE).append(" (API ").append(android.os.Build.VERSION.SDK_INT)
                    .append("), ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL).append('\n');
            try (java.io.InputStream in = pick.getTraceInputStream()) {
                if (in != null) {
                    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                    byte[] chunk = new byte[16384];
                    int n;
                    while (buf.size() < 512 * 1024 && (n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
                    byte[] trace = buf.toByteArray();
                    String read = pick.getReason() == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE
                            ? com.strickland.jeepdash.core.Tombstone.summary(trace, 16000) : null;
                    sb.append("\nTrace:\n").append(read != null ? read
                            : com.strickland.jeepdash.core.CrashReport.printable(trace, 4, 16000));
                }
            } catch (java.io.IOException | RuntimeException e) {
                sb.append("\n(trace unreadable: ").append(e).append(")");
            }
            String trail = com.strickland.jeepdash.scene.Trail.forPid(c, pick.getPid());
            if (trail != null) sb.append("\n3D steps before the crash (last one was running):\n").append(trail);
            String log = logOf(pick.getPid());
            if (log != null) sb.append("\nLog of that run (warnings and errors):\n").append(log);
            else sb.append("\n(the system log no longer had that run's lines)\n");
            String text = sb.toString();
            keepReport(c, kept, text);
            return text;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Keeps a built report (the newest few) so later starts show the same text, log and all. */
    private static void keepReport(Context c, java.io.File f, String text) {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException | RuntimeException ignored) {
            return;
        }
        java.io.File[] all = c.getFilesDir().listFiles((d, n) -> n.startsWith("exit-") && n.endsWith(".txt"));
        if (all != null && all.length > 5) {
            java.util.Arrays.sort(all, (x, y) -> Long.compare(x.lastModified(), y.lastModified()));
            for (int i = 0; i < all.length - 5; i++) all[i].delete();
        }
    }

    /**
     * The crashed run's own warnings and errors, while the system log still holds them (an app may read its own
     * lines). Filament writes why it stopped here; the tombstone leaves the log out on most phones. Null when none.
     */
    private static String logOf(int pid) {
        if (pid <= 0) return null;
        Process p = null;
        try {
            p = new ProcessBuilder("logcat", "-d", "-v", "time", "--pid=" + pid, "-t", "300", "*:W")
                    .redirectErrorStream(true).start();
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            java.io.InputStream in = p.getInputStream();
            long end = android.os.SystemClock.elapsedRealtime() + 2000;
            while (buf.size() < 64 * 1024 && android.os.SystemClock.elapsedRealtime() < end && (n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            String s = buf.toString("UTF-8").trim();
            if (s.isEmpty()) return null;
            String[] lines = s.split("\n");
            StringBuilder sb = new StringBuilder();
            for (int i = Math.max(0, lines.length - 60); i < lines.length; i++) {
                if (lines[i].startsWith("--------- beginning of")) continue;
                sb.append(lines[i]).append('\n');
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }

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
