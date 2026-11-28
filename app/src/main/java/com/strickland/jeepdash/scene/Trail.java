package com.strickland.jeepdash.scene;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/**
 * The 3D view's last steps, written to a file as they happen. A native crash in Filament kills the process
 * without a Java stack, so the crash report reads this file to say which step was running (the Filament call
 * that failed its check). Only set-up steps and the first frames are marked, not every frame.
 */
public final class Trail {
    private static final String NOW = "3d_trail.txt", PREV = "3d_trail_prev.txt";   // older builds' names, still read
    /** One file per run, named by process id, so a crash's trail survives later runs; the last few are kept. */
    private static final String PER_RUN = "3d_trail_", TXT = ".txt";
    private static final int KEEP_RUNS = 8;
    private static final int KEEP = 40;
    private static final ArrayDeque<String> steps = new ArrayDeque<>();
    private static File file;
    private static long t0;

    private Trail() {}

    /** Once per run, before the first step: the last run's trail is kept as the previous one. */
    static synchronized void start(Context c) {
        if (file != null) return;
        File dir = c.getApplicationContext().getFilesDir();
        file = new File(dir, PER_RUN + android.os.Process.myPid() + TXT);
        File[] runs = dir.listFiles((d, n) -> n.startsWith(PER_RUN) && n.endsWith(TXT));
        if (runs != null && runs.length >= KEEP_RUNS) {
            java.util.Arrays.sort(runs, (x, y) -> Long.compare(x.lastModified(), y.lastModified()));
            for (int i = 0; i <= runs.length - KEEP_RUNS; i++) runs[i].delete();     // oldest first
        }
        t0 = android.os.SystemClock.elapsedRealtime();
        steps.clear();
        step("3D start (pid " + android.os.Process.myPid() + ")");
    }

    /** Marks a step; written straight to the file so it survives an abort a moment later. */
    static synchronized void step(String what) {
        if (file == null) return;
        long ms = android.os.SystemClock.elapsedRealtime() - t0;
        steps.addLast("+" + ms + "ms " + Thread.currentThread().getName() + ": " + what);
        while (steps.size() > KEEP) steps.removeFirst();
        StringBuilder sb = new StringBuilder("pid ").append(android.os.Process.myPid()).append('\n');
        for (String s : steps) sb.append(s).append('\n');
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // a missing trail only makes the report shorter
        }
    }

    /** The trail of the run with this process id, or null when none was kept. */
    public static String forPid(Context c, int pid) {
        File dir = c.getApplicationContext().getFilesDir();
        for (String name : new String[]{PER_RUN + pid + TXT, NOW, PREV}) {
            File f = new File(dir, name);
            if (!f.exists() || f.length() > 64 * 1024) continue;
            try {
                String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                if (s.startsWith("pid " + pid + "\n")) return s.substring(s.indexOf('\n') + 1);
            } catch (IOException | RuntimeException ignored) {
                // unreadable: skip it
            }
        }
        return null;
    }
}
