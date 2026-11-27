package com.strickland.jeepdash;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.FrameMetrics;
import android.view.Window;

import com.strickland.jeepdash.core.DiagLog;
import com.strickland.jeepdash.core.GfxStats;
import com.strickland.jeepdash.core.VizTune;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The visualizer's graphics log, under the GFX tag of the app's diagnostics log: what the tablet and its GPU
 * are (model, chip, GPU name and driver, Vulkan level, screen modes), then every 20 seconds and on each look
 * change one line per look with the frame rate, the 1% low, the GPU's and main thread's time per frame, the
 * 3D detail the look settled at, the effects in use and the tablet's temperature status. The graphics test
 * runs every look for 4 seconds with the effects as set and again with them off, then writes a summary.
 * Share sends the GFX lines as a text file.
 */
final class VizGfx {
    private static final float WINDOW_S = 20, STEP_S = 4, WARMUP_S = 1;
    private final GfxStats stats = new GfxStats();
    private final String startedWhen = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    private final StringBuilder summary = new StringBuilder();
    private final java.util.List<String> overBudget = new java.util.ArrayList<>();
    private HandlerThread metricsThread;
    private Window.OnFrameMetricsAvailableListener listener;
    private Window window;
    private float clock;
    private int lookShown = -1;
    // the graphics test
    boolean testing;
    private int step;
    private float stepTime;
    private String savedTune;
    private int savedLook;

    /** Writes what the tablet and GPU are, once per visit, and starts reading frame metrics. */
    void start(Activity a) {
        Diag.info(DiagLog.GFX, "device " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + "), Android "
                + Build.VERSION.RELEASE + " API " + Build.VERSION.SDK_INT
                + (Build.VERSION.SDK_INT >= 31 ? ", chip " + Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL : "")
                + ", board " + Build.HARDWARE);
        Display d = a.getWindowManager().getDefaultDisplay();
        DisplayMetrics m = new DisplayMetrics();
        d.getRealMetrics(m);
        StringBuilder modes = new StringBuilder();
        for (Display.Mode mode : d.getSupportedModes())
            modes.append(' ').append(mode.getPhysicalWidth()).append('x').append(mode.getPhysicalHeight())
                    .append('@').append(Math.round(mode.getRefreshRate()));
        Diag.info(DiagLog.GFX, "screen " + m.widthPixels + "x" + m.heightPixels + " " + m.densityDpi + "dpi, now "
                + Math.round(d.getRefreshRate()) + " Hz, modes" + modes);
        Diag.info(DiagLog.GFX, "vulkan " + vulkan(a) + ", memory class " + ((android.app.ActivityManager)
                a.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryClass() + " MB, cores "
                + Runtime.getRuntime().availableProcessors());
        new Thread(() -> Diag.info(DiagLog.GFX, "gpu " + glInfo()), "gfx-probe").start();
        window = a.getWindow();
        if (Build.VERSION.SDK_INT >= 24) {
            metricsThread = new HandlerThread("gfx-metrics");
            metricsThread.start();
            listener = (w, fm, dropped) -> {
                double gpu = Build.VERSION.SDK_INT >= 31 ? fm.getMetric(FrameMetrics.GPU_DURATION) / 1e6 : 0;
                double cpu = (fm.getMetric(FrameMetrics.DRAW_DURATION) + fm.getMetric(FrameMetrics.SYNC_DURATION)) / 1e6;
                if (Build.VERSION.SDK_INT >= 31) stats.metrics(gpu, cpu);
            };
            window.addOnFrameMetricsAvailableListener(listener, new Handler(metricsThread.getLooper()));
        }
    }

    void stop(VizPainter p) {
        flush(p);
        if (testing) finish(p, false);
        if (window != null && listener != null && Build.VERSION.SDK_INT >= 24) {
            try {
                window.removeOnFrameMetricsAvailableListener(listener);
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
        if (metricsThread != null) metricsThread.quitSafely();
        metricsThread = null;
        listener = null;
    }

    /** Logs how long a 3D look's shader took to build, or that it would not build. */
    static void shader(String look, long startNs, RuntimeException failed) {
        long ms = (System.nanoTime() - startNs) / 1_000_000;
        if (failed == null) Diag.info(DiagLog.GFX, "shader " + look + " built in " + ms + " ms");
        else Diag.error(DiagLog.GFX, "shader " + look + " failed after " + ms + " ms: " + failed.getMessage());
    }

    /** One frame: counted, and the test moved on when it is running. */
    void frame(VizPainter p, float dt, Context c) {
        if (dt <= 0) return;
        float detail = 1;
        VizPainter.Extra x = p.look < p.gpu.length ? p.gpu[p.look] : null;
        if (x instanceof GpuLook) detail = ((GpuLook) x).detailNow();
        if (testing) {
            stepTime += dt;
            if (stepTime < WARMUP_S) {
                stats.clear();
                return;
            }
            stats.frame(dt * 1000, detail);
            if (stepTime >= STEP_S) {
                float target = 1 / p.frameBudget;
                boolean over = stats.fps() < target * 0.9;               // checked against this tablet's profile
                String line = String.format(Locale.US, "%-13s %-4s %s%s", VizPainter.LOOKS[p.look],
                        effectsOn() ? "fx" : "bare", stats.line(),
                        over ? String.format(Locale.US, "  OVER BUDGET (target %.0f fps)", target) : "");
                if (over) {
                    Diag.warn(DiagLog.GFX, "test " + line);
                    String name = VizPainter.LOOKS[p.look];
                    if (!overBudget.contains(name)) overBudget.add(name);
                } else {
                    Diag.info(DiagLog.GFX, "test " + line);
                }
                summary.append(line).append('\n');
                next(p);
            }
            return;
        }
        if (p.look != lookShown) {
            flush(p);
            lookShown = p.look;
        }
        stats.frame(dt * 1000, detail);
        clock += dt;
        if (clock >= WINDOW_S) flush(p);
        if (Math.round(clock * 10) % 50 == 0) thermal(c);
    }

    private int thermalStatus = -1;
    private float headroom = Float.NaN;

    private void thermal(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        if (pm == null || Build.VERSION.SDK_INT < 29) return;
        thermalStatus = pm.getCurrentThermalStatus();
        if (Build.VERSION.SDK_INT >= 30) headroom = pm.getThermalHeadroom(0);
    }

    private String effects(VizPainter p) {
        return String.format(Locale.US, "quality %s glow %.2f crt %.2f storm %.2f bars %d",
                VizTune.QUALITY[Math.round(p.tune.get(VizTune.GLOBAL, "quality"))],
                p.tune.get(VizTune.GLOBAL, "bloom"), p.tune.get(VizTune.GLOBAL, "crt"),
                p.tune.get(VizTune.GLOBAL, "storm"), p.shown);
    }

    private String heat() {
        String[] names = {"none", "light", "moderate", "severe", "critical", "emergency", "shutdown"};
        String s = thermalStatus >= 0 && thermalStatus < names.length ? names[thermalStatus] : "unknown";
        return "heat " + s + (Float.isNaN(headroom) ? "" : String.format(Locale.US, " headroom %.2f", headroom));
    }

    /** Writes the frames so far as one line and starts counting again. */
    private void flush(VizPainter p) {
        if (stats.frames() > 30 && lookShown >= 0 && lookShown < VizPainter.LOOKS.length)
            Diag.info(DiagLog.GFX, "look " + VizPainter.LOOKS[lookShown] + "  " + stats.line() + "  "
                    + effects(p) + "  " + heat());
        stats.clear();
        clock = 0;
    }

    // ---- the graphics test: every look, effects on then off, 4 seconds each ----

    void startTest(VizPainter p) {
        if (testing) {
            finish(p, false);
            return;
        }
        flush(p);
        savedTune = p.tune.save();
        savedLook = p.look;
        summary.setLength(0);
        overBudget.clear();
        Diag.info(DiagLog.GFX, "test start  " + effects(p) + "  " + heat());
        testing = true;
        step = -1;
        next(p);
    }

    private boolean effectsOn() { return step % 2 == 0; }

    private void next(VizPainter p) {
        step++;
        if (step >= VizTune.CHOICES.length * 2) {
            finish(p, true);
            return;
        }
        p.tune = VizTune.load(savedTune);
        p.tune.set(VizTune.GLOBAL, "blend", 0);                         // straight cuts, so only one look runs
        if (!effectsOn()) {
            p.tune.set(VizTune.GLOBAL, "bloom", 0);
            p.tune.set(VizTune.GLOBAL, "crt", 0);
            p.tune.set(VizTune.GLOBAL, "storm", 0);
        }
        p.changeLook(VizTune.CHOICES[step / 2]);
        p.message = "Graphics test " + (step + 1) + " of " + VizTune.CHOICES.length * 2 + ": "
                + VizPainter.LOOKS[p.look] + (effectsOn() ? ", effects on" : ", effects off");
        stepTime = 0;
        stats.clear();
    }

    private void finish(VizPainter p, boolean done) {
        testing = false;
        p.tune = VizTune.load(savedTune);
        p.changeLook(savedLook);
        String over = overBudget.isEmpty() ? "every look within budget" : "over budget: " + String.join(", ", overBudget);
        Diag.info(DiagLog.GFX, (done ? "test done, " + over : "test stopped") + "  " + heat() + "\n" + summary);
        p.message = done ? "Graphics test done, " + over + ". Share the log from Tune › Presets" : null;
        lookShown = -1;
    }

    // ---- sharing ----

    void share(Activity a) {
        int build = 0;
        try {
            android.content.pm.PackageInfo pi = a.getPackageManager().getPackageInfo(a.getPackageName(), 0);
            build = (int) (Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode);
        } catch (Exception ignored) {
            // build stays 0
        }
        String body = Diag.LOG.export(build, startedWhen, DiagLog.INFO, DiagLog.GFX, 400000);
        String name = "jeepdash-graphics-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
        Uri uri = DriveLogStore.saveText(a, name, body);
        if (uri != null) {
            DriveLogStore.shareText(a, uri, "Graphics log");
        } else {
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, body);
            a.startActivity(Intent.createChooser(i, "Graphics log"));
        }
    }

    private static String vulkan(Context c) {
        PackageManager pm = c.getPackageManager();
        FeatureInfo[] all = pm.getSystemAvailableFeatures();
        String level = "none";
        if (all != null) for (FeatureInfo f : all) {
            if (PackageManager.FEATURE_VULKAN_HARDWARE_VERSION.equals(f.name))
                level = (f.version >> 22) + "." + ((f.version >> 12) & 0x3FF) + "." + (f.version & 0xFFF);
        }
        return level;
    }

    /** The GPU's own name and driver, from a tiny throwaway OpenGL context. */
    private static String glInfo() {
        try {
            EGLDisplay d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] v = new int[2];
            if (!EGL14.eglInitialize(d, v, 0, v, 1)) return "unknown (no EGL)";
            int[] attr = {EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE,
                    EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE};
            EGLConfig[] cfg = new EGLConfig[1];
            int[] n = new int[1];
            if (!EGL14.eglChooseConfig(d, attr, 0, cfg, 0, 1, n, 0) || n[0] == 0) return "unknown (no config)";
            EGLContext ctx = EGL14.eglCreateContext(d, cfg[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            EGLSurface s = EGL14.eglCreatePbufferSurface(d, cfg[0], new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
            EGL14.eglMakeCurrent(d, s, s, ctx);
            String out = GLES20.glGetString(GLES20.GL_VENDOR) + " " + GLES20.glGetString(GLES20.GL_RENDERER)
                    + ", " + GLES20.glGetString(GLES20.GL_VERSION) + ", EGL " + v[0] + "." + v[1];
            EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(d, s);
            EGL14.eglDestroyContext(d, ctx);
            return out;
        } catch (Throwable t) {
            return "unknown (" + t.getClass().getSimpleName() + ")";
        }
    }
}
