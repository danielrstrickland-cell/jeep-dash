package com.strickland.jeepdash;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.BlendMode;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Choreographer;
import android.view.TextureView;

import com.strickland.jeepdash.core.DiagLog;
import com.strickland.jeepdash.core.GfxStats;
import com.strickland.jeepdash.core.MilkPlaylist;
import com.strickland.jeepdash.core.VizTune;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The MilkDrop look: libprojectM (the open-source MilkDrop engine) drawing into a TextureView under the
 * visualizer's buttons, on its own GL thread so it never holds up the rest of the app. It is fed the same sound
 * the visualizer hears, held back by the Speaker delay. It switches preset on the drop (projectM's own beat
 * detection), held at least the "Hold at least" time, never while locked; Prev, Next, Lock and Star come from
 * the side menu. Glow, CRT, an rpm colour shift and G-meter sway are applied on top by Android's renderer.
 * 50 presets ship in the app; the full Cream of the Crop pack and the MilkDrop texture pack download once.
 */
final class MilkDrop implements TextureView.SurfaceTextureListener {
    private static final boolean LOADED;

    static {
        boolean ok;
        try {
            System.loadLibrary("milkdrop");
            ok = available();
        } catch (Throwable t) {
            ok = false;
        }
        LOADED = ok;
    }

    static native boolean available();

    private static native long create(int w, int h, String textures);

    private static native void resize(long h, int w, int ht);

    private static native void settings(long h, boolean drop, float hold, float sensitivity, boolean locked,
                                        int meshW, int meshH, int fps);

    private static native void pcm(long h, short[] samples, int count);

    private static native int render(long h);

    private static native void load(long h, String file, boolean smooth);

    private static native String failed(long h);

    private static native void destroy(long h);

    /** True when this device can run MilkDrop (the 64-bit tablet build). */
    static boolean ready() { return LOADED; }

    static final String PACK_URL = "https://codeload.github.com/projectM-visualizer/presets-cream-of-the-crop/zip/refs/heads/master";
    static final String TEXTURE_URL = "https://codeload.github.com/projectM-visualizer/presets-milkdrop-texture-pack/zip/refs/heads/master";

    final TextureView view;
    private final Context context;
    private final File root, starter, pack, textures;
    final MilkPlaylist list = new MilkPlaylist(System.nanoTime());
    private final GfxStats stats = new GfxStats();

    private HandlerThread thread;
    private Handler gl;
    private EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
    private EGLContext ctx = EGL14.EGL_NO_CONTEXT;
    private EGLSurface surf = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture texture;
    private long pm;
    private int viewW, viewH, bufW, bufH;
    private long lastFrameNs;

    // settings, written by the visualizer screen every frame, read by the GL thread
    volatile boolean active, drop = true, locked;
    volatile float hold = 15, sensitivity = 1, delay = 0.2f;
    volatile int quality = VizTune.BALANCED, mode = MilkPlaylist.FAVOUR, pace = 2;
    private int vsyncs;
    private String settingsShown = "";
    /** Next (1) or Prev (-1), asked from the side menu. */
    final AtomicInteger step = new AtomicInteger();
    /** The preset on screen and when it came on, for the name flash and the side menu. */
    volatile String current;
    volatile long changedMs;
    /** Download progress for the tune window, or null. */
    volatile String download;

    // sound in, held back by the speaker delay
    private static final int CHUNKS = 96;
    private final short[][] chunk = new short[CHUNKS][];
    private final int[] chunkN = new int[CHUNKS];
    private final long[] chunkNs = new long[CHUNKS];
    private int head, tail;

    MilkDrop(Context c, TextureView v) {
        context = c.getApplicationContext();
        view = v;
        root = new File(context.getFilesDir(), "milkdrop");
        starter = new File(root, "starter");
        pack = new File(root, "pack");
        textures = new File(root, "textures");
        view.setSurfaceTextureListener(this);
        view.setOpaque(true);
    }

    /** One chunk of the sound the visualizer heard (mono, 16-bit). */
    void pcmIn(short[] samples, int n) {
        if (!active || n <= 0) return;
        synchronized (chunk) {
            int i = head;
            if (chunk[i] == null || chunk[i].length < n) chunk[i] = new short[n];
            System.arraycopy(samples, 0, chunk[i], 0, n);
            chunkN[i] = n;
            chunkNs[i] = System.nanoTime();
            head = (head + 1) % CHUNKS;
            if (head == tail) tail = (tail + 1) % CHUNKS;               // full: drop the oldest
        }
    }

    // ---- the surface and the GL thread ----

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        texture = st;
        viewW = w;
        viewH = h;
        thread = new HandlerThread("milkdrop-gl");
        thread.start();
        gl = new Handler(thread.getLooper());
        gl.post(this::init);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
        viewW = w;
        viewH = h;
        if (gl != null) gl.post(this::fitBuffer);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        if (gl != null) {
            CountDownLatch done = new CountDownLatch(1);
            gl.post(() -> {
                teardown();
                done.countDown();
            });
            try {
                done.await(800, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            thread.quitSafely();
            gl = null;
        }
        texture = null;
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) { }

    private void init() {
        try {
            copyStarter();
            rescan();
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] v = new int[2];
            EGL14.eglInitialize(dpy, v, 0, v, 1);
            int[] attr = {EGL14.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT */, EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE};
            EGLConfig[] cfg = new EGLConfig[1];
            int[] n = new int[1];
            EGL14.eglChooseConfig(dpy, attr, 0, cfg, 0, 1, n, 0);
            if (n[0] == 0) throw new IllegalStateException("no OpenGL ES 3 config");
            ctx = EGL14.eglCreateContext(dpy, cfg[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE}, 0);
            fitBufferSize();
            surf = EGL14.eglCreateWindowSurface(dpy, cfg[0], texture, new int[]{EGL14.EGL_NONE}, 0);
            EGL14.eglMakeCurrent(dpy, surf, surf, ctx);
            EGL14.eglSwapInterval(dpy, 1);
            pm = create(bufW, bufH, textures.getAbsolutePath());
            if (pm == 0) throw new IllegalStateException("projectM did not start");
            Diag.info(DiagLog.GFX, "milkdrop started " + bufW + "x" + bufH + ", " + list.size() + " presets, GL "
                    + GLES20.glGetString(GLES20.GL_VERSION));
            show(list.next(mode), false);
            Choreographer.getInstance().postFrameCallback(this::frame);
        } catch (RuntimeException e) {
            Diag.error(DiagLog.GFX, "milkdrop could not start: " + e.getMessage());
            teardown();
        }
    }

    private void teardown() {
        logPreset();
        if (pm != 0) destroy(pm);
        pm = 0;
        if (dpy != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf);
            if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx);
        }
        surf = EGL14.EGL_NO_SURFACE;
        ctx = EGL14.EGL_NO_CONTEXT;
        dpy = EGL14.EGL_NO_DISPLAY;
    }

    /** The picture is drawn at the Quality setting's share of the screen and stretched to fit. */
    private void fitBufferSize() {
        float s = VizTune.detail(quality);
        bufW = Math.max(64, Math.round(viewW * s));
        bufH = Math.max(64, Math.round(viewH * s));
        if (texture != null) texture.setDefaultBufferSize(bufW, bufH);
    }

    private void fitBuffer() {
        fitBufferSize();
        if (pm != 0) resize(pm, bufW, bufH);
    }

    private int qualityShown = -1;

    private void frame(long frameNs) {
        if (pm == 0 || gl == null) return;
        Choreographer.getInstance().postFrameCallback(this::frame);
        if (!active) {
            lastFrameNs = 0;
            return;
        }
        if (quality != qualityShown) {
            qualityShown = quality;
            fitBuffer();
        }
        int[] mesh = quality == VizTune.MAX_QUALITY ? new int[]{64, 48} : quality == VizTune.SMOOTH_90 ? new int[]{32, 24} : new int[]{48, 36};
        String now = drop + "/" + hold + "/" + sensitivity + "/" + locked + "/" + quality;
        if (!now.equals(settingsShown)) {
            settingsShown = now;
            settings(pm, drop && !locked, hold, sensitivity, locked, mesh[0], mesh[1],
                    quality == VizTune.MAX_QUALITY ? 30 : quality == VizTune.SMOOTH_90 ? 90 : 45);
        }
        int asked = step.getAndSet(0);
        if (asked > 0) show(list.next(mode), true);
        else if (asked < 0) show(list.prev(), true);
        feed();
        if (++vsyncs < pace) return;                                     // evenly paced, like the rest of the visualizer
        vsyncs = 0;
        long t0 = System.nanoTime();
        int request = render(pm);
        EGL14.eglSwapBuffers(dpy, surf);
        stats.metrics((System.nanoTime() - t0) / 1e6, 0);                // render + swap time, the GPU's share
        if (lastFrameNs != 0) stats.frame((frameNs - lastFrameNs) / 1e6, VizTune.detail(quality));
        lastFrameNs = frameNs;
        String bad = failed(pm);
        if (bad != null) Diag.warn(DiagLog.GFX, "milkdrop preset failed: " + bad);
        if (request != 0 && !locked) show(list.next(mode), request == 2);
    }

    /** Hands projectM every chunk of sound older than the speaker delay. */
    private void feed() {
        long due = System.nanoTime() - (long) (delay * 1e9);
        synchronized (chunk) {
            while (tail != head && chunkNs[tail] <= due) {
                pcm(pm, chunk[tail], chunkN[tail]);
                tail = (tail + 1) % CHUNKS;
            }
        }
    }

    private void show(String preset, boolean smooth) {
        if (preset == null || pm == 0) return;
        logPreset();
        load(pm, preset, smooth);
        current = preset;
        changedMs = System.currentTimeMillis();
    }

    /** Logs how the preset that is leaving ran: the per-preset record the graphics profile is built from. */
    private void logPreset() {
        if (current == null || stats.frames() < 60) {
            stats.clear();
            return;
        }
        String line = "milkdrop " + MilkPlaylist.name(current) + "  " + stats.line();
        if (stats.fps() < 40) Diag.warn(DiagLog.GFX, line + "  SLOW");
        else Diag.info(DiagLog.GFX, line);
        stats.clear();
    }

    // ---- presets on disk ----

    private void copyStarter() {
        if (new File(starter, ".done").exists()) return;
        starter.mkdirs();
        AssetManager am = context.getAssets();
        try {
            String[] names = am.list("milkdrop");
            if (names != null) for (String n : names) {
                if (!n.endsWith(".milk")) continue;
                try (InputStream in = am.open("milkdrop/" + n); OutputStream out = new FileOutputStream(new File(starter, n))) {
                    copy(in, out);
                }
            }
            new File(starter, ".done").createNewFile();
        } catch (java.io.IOException e) {
            Diag.error(DiagLog.GFX, "milkdrop starter presets: " + e.getMessage());
        }
    }

    /** Lists every preset: the starter set, plus the full pack once downloaded. */
    private void rescan() {
        List<String> found = new ArrayList<>();
        collect(starter, found);
        collect(pack, found);
        list.setPresets(found);
        list.loadStars(context.getSharedPreferences("viz", Context.MODE_PRIVATE).getString("milkStars", ""));
    }

    private static void collect(File dir, List<String> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) collect(f, out);
            else if (f.getName().endsWith(".milk")) out.add(f.getAbsolutePath());
        }
    }

    void saveStars() {
        context.getSharedPreferences("viz", Context.MODE_PRIVATE).edit().putString("milkStars", list.saveStars()).apply();
    }

    boolean packInstalled() { return new File(pack, ".done").exists(); }

    /** Downloads the full preset pack and the texture pack, once, in the background. */
    void downloadPack() {
        if (download != null) return;
        download = "Starting download…";
        new Thread(() -> {
            try {
                unzip(TEXTURE_URL, textures, "Textures", false);
                unzip(PACK_URL, pack, "Presets", true);
                new File(pack, ".done").createNewFile();
                Handler h = gl;
                if (h != null) h.post(this::rescan);
                else rescan();
                Diag.info(DiagLog.GFX, "milkdrop pack downloaded");
                download = null;
            } catch (Exception e) {
                Diag.error(DiagLog.GFX, "milkdrop download failed: " + e.getMessage());
                download = "Download failed. Check the connection and try again";
                try {
                    Thread.sleep(6000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                download = null;
            }
        }, "milkdrop-download").start();
    }

    private void unzip(String url, File into, String what, boolean presets) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        into.mkdirs();
        long got = 0;
        int files = 0;
        try (InputStream raw = c.getInputStream(); ZipInputStream zip = new ZipInputStream(new java.io.BufferedInputStream(raw, 1 << 16))) {
            byte[] buf = new byte[1 << 16];
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                String name = e.getName();
                int cut = name.indexOf('/');                          // drop the repo's top folder
                name = cut >= 0 ? name.substring(cut + 1) : name;
                if (e.isDirectory() || name.isEmpty() || name.contains("..")) continue;
                String lower = name.toLowerCase(Locale.US);
                boolean keep = presets ? lower.endsWith(".milk")
                        : lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".tga");
                if (!keep) continue;
                File out = new File(into, presets ? name : new File(name).getName());
                out.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(out)) {
                    for (int n; (n = zip.read(buf)) > 0; ) {
                        o.write(buf, 0, n);
                        got += n;
                    }
                }
                files++;
                if (files % 50 == 0) download = String.format(Locale.US, "%s: %d files, %.0f MB", what, files, got / 1e6);
            }
        } finally {
            c.disconnect();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] b = new byte[8192];
        for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
    }

    // ---- effects on top, by Android's renderer ----

    private String effectShown = "";

    /**
     * Glow, CRT and the rpm colour shift, as a RenderEffect on the TextureView, rebuilt only when they change;
     * plus G-meter sway as a small shift of the view.
     */
    void effects(float bloom, float crt, float hueDegrees, float swayX, float swayY, boolean sway) {
        if (sway) {
            view.setScaleX(1.06f);
            view.setScaleY(1.06f);
            view.setTranslationX(swayX * view.getWidth() * 0.025f);
            view.setTranslationY(swayY * view.getHeight() * 0.025f);
        } else {
            view.setScaleX(1);
            view.setScaleY(1);
            view.setTranslationX(0);
            view.setTranslationY(0);
        }
        if (Build.VERSION.SDK_INT < 31) return;
        int hue = Math.round(hueDegrees / 5) * 5;
        String key = Math.round(bloom * 20) + "/" + Math.round(crt * 20) + "/" + hue + "/" + view.getWidth();
        if (key.equals(effectShown)) return;
        effectShown = key;
        RenderEffect e = null;
        if (hue != 0) e = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(hueMatrix(hue)));
        if (bloom > 0.01f) {
            float k = 1.6f * bloom, off = -k * 0.4f * 255;
            ColorMatrix bright = new ColorMatrix(new float[]{k, 0, 0, 0, off, 0, k, 0, 0, off, 0, 0, k, 0, off, 0, 0, 0, 1, 0});
            float r = Math.max(8, Math.min(view.getWidth(), view.getHeight()) * 0.035f);
            RenderEffect base = e == null ? RenderEffect.createOffsetEffect(0, 0) : e;
            RenderEffect glow = RenderEffect.createBlurEffect(r, r,
                    RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(bright), base), Shader.TileMode.DECAL);
            e = RenderEffect.createBlendModeEffect(base, glow, BlendMode.PLUS);
        }
        if (crt > 0.01f && Build.VERSION.SDK_INT >= 33) {
            RuntimeShader s = new RuntimeShader(VizEffects.CRT);
            s.setFloatUniform("size", view.getWidth(), view.getHeight());
            s.setFloatUniform("amount", crt);
            RenderEffect tv = RenderEffect.createRuntimeShaderEffect(s, "content");
            e = e == null ? tv : RenderEffect.createChainEffect(tv, e);
        }
        view.setRenderEffect(e);
    }

    /** A colour matrix that turns every colour round the colour wheel by the given degrees. */
    private static ColorMatrix hueMatrix(float degrees) {
        double a = Math.toRadians(degrees);
        float c = (float) Math.cos(a), s = (float) Math.sin(a);
        float lr = 0.213f, lg = 0.715f, lb = 0.072f;
        return new ColorMatrix(new float[]{
                lr + c * (1 - lr) + s * (-lr), lg + c * (-lg) + s * (-lg), lb + c * (-lb) + s * (1 - lb), 0, 0,
                lr + c * (-lr) + s * 0.143f, lg + c * (1 - lg) + s * 0.140f, lb + c * (-lb) + s * (-0.283f), 0, 0,
                lr + c * (-lr) + s * (-(1 - lr)), lg + c * (-lg) + s * lg, lb + c * (1 - lb) + s * lb, 0, 0,
                0, 0, 0, 1, 0});
    }
}
