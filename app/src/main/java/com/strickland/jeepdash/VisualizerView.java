package com.strickland.jeepdash;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.Build;
import android.view.Display;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;

import com.strickland.jeepdash.core.MusicLayout;
import com.strickland.jeepdash.core.Spectrum;
import com.strickland.jeepdash.core.VizTune;
import com.strickland.jeepdash.theme.MusicPainter;
import com.strickland.jeepdash.theme.Theme;

/**
 * The visualizer screen. One menu button (top left, kept below the status bar) opens a side menu with Dash,
 * Look, Colors, Bars, the tune window, Hide controls and Music. The button fades after 10 seconds untouched; a
 * touch on its corner brings it back. A three-finger tap hides everything, or brings it back. A tap or swipe on
 * the picture itself does nothing, so brightness swipes cannot change anything. MUSIC (when on) opens the plain
 * music window.
 */
public class VisualizerView extends View {
    public interface Host {
        void onDashButton();
        /** prev, play or next on the music window. */
        void onMusic(String part);
    }

    private static final int HISTORY = 128;                 // sound frames kept for the speaker delay
    private static final long FADE_AFTER_MS = 10_000;

    /** The music window's text and state, in the same form the dash's looks get it. */
    final Theme.Chrome musicState = new Theme.Chrome();
    private MusicLayout music;
    private String musicDown;
    private final float density;

    final VizPainter painter = new VizPainter();
    private final SharedPreferences prefs;
    private final Host host;
    private final VizMotion motion = new VizMotion();
    private final VizGfx gfx = new VizGfx();
    // even frame pacing: a new picture every Nth screen refresh (the screen only runs at 90 Hz)
    private int vsyncs;
    private final android.view.Choreographer.FrameCallback tick = new android.view.Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameNs) {
            if (!running) return;
            if (++vsyncs >= painter.pace) invalidate();
            else android.view.Choreographer.getInstance().postFrameCallback(this);
        }
    };
    private final VizEffects effects = VizEffects.supported() ? new VizEffects() : null;
    private volatile Spectrum spectrum;
    private long lastNs, startNs, touchedMs = System.currentTimeMillis();
    private boolean running, sliding;
    private int tuneRow = -1;
    private int fastMode, sixtyMode, modeShown = -1;

    // what the sound looked like over the last moment, so the picture can wait for the Bluetooth speakers
    private final float[][] histLevels = new float[HISTORY][VizPainter.BANDS], histPeaks = new float[HISTORY][VizPainter.BANDS];
    private final float[] histVu = new float[HISTORY], histBass = new float[HISTORY], histBeat = new float[HISTORY];
    private final long[] histNs = new long[HISTORY];
    private int histAt;

    private boolean wakeHeld;                                // a hold on the corner while everything is hidden
    private final Runnable wake = () -> {
        wakeHeld = true;
        painter.hidden = false;
        touched();
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    };

    public VisualizerView(Context context, Host host) {
        super(context);
        this.host = host;
        density = context.getResources().getDisplayMetrics().density;
        prefs = context.getSharedPreferences("viz", Context.MODE_PRIVATE);
        SwirlLook plain = new SwirlLook();                          // the old main-processor swirl, as a fallback
        GpuSwirl xp = GpuSwirl.supported() ? new GpuSwirl() : null;
        painter.swirl = (c, w, h, dt, sec, p) -> {
            if (xp != null && xp.working()) xp.draw(c, w, h, dt, sec, p);
            else plain.draw(c, w, h, dt, sec, p);
        };
        if (GpuLook.supported()) {                           // a shader that does not build leaves smooth bars
            build(VizTune.ROAD, () -> new GpuRoad(motion));
            build(VizTune.MANDEL, GpuMandel::new);
            build(VizTune.SUBS, () -> new GpuSubs(motion));
        }
        painter.gpu[VizTune.DOLPHINS] = new DolphinLook();                // plain drawing: every tablet
        painter.look = VizTune.savedLook(prefs.getInt("look2", 0));      // the old boom meters become Smooth
        painter.color = Math.floorMod(prefs.getInt("color", 1), VizPainter.COLORS.length);
        int bars = prefs.getInt("bars", 32);
        for (int n : VizPainter.COUNTS) if (n == bars) painter.shown = n;
        painter.tune = VizTune.load(prefs.getString("tune", null));
        for (int i = 0; i < VizPainter.PRESETS; i++) painter.presetInfo[i] = describe(prefs.getString("preset" + i, null));
    }

    /** Builds one graphics-chip look, timing it for the graphics log. */
    private void build(int look, java.util.function.Supplier<VizPainter.Extra> make) {
        long t = System.nanoTime();
        try {
            painter.gpu[look] = make.get();
            VizGfx.shader(VizPainter.LOOKS[look], t, null);
        } catch (RuntimeException e) {
            painter.gpu[look] = null;
            VizGfx.shader(VizPainter.LOOKS[look], t, e);
        }
    }

    void setSpectrum(Spectrum s) { spectrum = s; }

    private MilkDrop milk;

    /** MilkDrop's view and engine, set by the screen when this tablet can run it. */
    void attachMilk(MilkDrop m) {
        milk = m;
        painter.milkReady = m != null;
    }

    /** Sound for MilkDrop, from the screen's sound thread. */
    void milkPcm(short[] samples, int n) {
        MilkDrop m = milk;
        if (m != null) m.pcmIn(samples, n);
    }

    /** Hands MilkDrop this frame's settings and shows its state in the side menu. */
    private void milkFrame() {
        MilkDrop m = milk;
        if (m == null) return;
        VizPainter p = painter;
        float alpha = p.milkAlpha();
        if (m.view.getAlpha() != alpha) m.view.setAlpha(alpha);
        m.active = alpha > 0 && running;
        int look = VizTune.MILK;
        m.drop = p.tune.on(look, "drop");
        m.hold = p.tune.get(look, "hold");
        m.sensitivity = p.tune.get(look, "sens");
        m.locked = p.milkLocked;
        m.quality = Math.round(p.tune.get(VizTune.GLOBAL, "quality"));
        m.pace = p.pace;
        m.mode = Math.round(p.tune.get(look, "play"));
        m.delay = p.tune.get(VizTune.GLOBAL, "delay");
        if (m.active) {
            float hue = p.rpm == null ? 0 : (float) Math.min(1, p.rpm / 4500.0) * 180 * p.tune.get(look, "hue");
            m.effects(p.tune.get(VizTune.GLOBAL, "bloom"), p.tune.get(VizTune.GLOBAL, "crt"), hue,
                    p.sloshX, p.sloshY, p.tune.on(look, "sway"));
        }
        String now = m.current;
        p.milkName = com.strickland.jeepdash.core.MilkPlaylist.name(now);
        p.milkChangedMs = m.changedMs;
        p.milkNumber = m.list.number();
        p.milkCount = m.list.size();
        p.milkStarred = m.list.starred(now);
        p.milkMode = m.mode;
        p.milkStatus = m.download;
        p.milkPack = m.packInstalled();
    }

    void setRunning(boolean on) {
        running = on;
        lastNs = 0;
        if (on) {
            motion.start(getContext());
            if (getContext() instanceof Activity) gfx.start((Activity) getContext());
            invalidate();
        } else {
            motion.stop();
            gfx.stop(painter);
        }
    }

    /** "Swirl · Vice" for a saved preset, or "Empty". */
    private static String describe(String preset) {
        String[] part = preset == null ? null : preset.split("\\|", 3);
        if (part == null || part.length < 3) return "Empty";
        try {
            int look = VizTune.savedLook(Integer.parseInt(part[0]));
            int color = Math.floorMod(Integer.parseInt(part[1]), VizPainter.COLORS.length);
            return VizPainter.LOOKS[look] + " · " + VizPainter.COLORS[color];
        } catch (NumberFormatException e) {
            return "Empty";
        }
    }

    private void savePreset(int i) {
        String preset = painter.look + "|" + painter.color + "|" + painter.tune.save();
        prefs.edit().putString("preset" + i, preset).apply();
        painter.presetInfo[i] = describe(preset);
    }

    private void loadPreset(int i) {
        String[] part = prefs.getString("preset" + i, "").split("\\|", 3);
        if (part.length < 3) return;
        try {
            painter.changeLook(VizTune.savedLook(Integer.parseInt(part[0])));
            painter.color = Math.floorMod(Integer.parseInt(part[1]), VizPainter.COLORS.length);
            painter.tune = VizTune.load(part[2]);
        } catch (NumberFormatException ignored) {
            // a damaged preset is left alone
        }
    }

    /** Finds the screen's fastest mode and a 60 Hz one at the current size, for the Quality choice. */
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Display d = getDisplay();
        if (d == null || !(getContext() instanceof Activity)) return;
        Display.Mode now = d.getMode(), best = now, sixty = null;
        for (Display.Mode m : d.getSupportedModes()) {
            if (m.getPhysicalWidth() != now.getPhysicalWidth() || m.getPhysicalHeight() != now.getPhysicalHeight()) continue;
            if (m.getRefreshRate() > best.getRefreshRate()) best = m;
            if (Math.abs(m.getRefreshRate() - 60) < 1) sixty = m;
        }
        fastMode = best.getModeId();
        sixtyMode = sixty == null ? fastMode : sixty.getModeId();
        modeShown = -1;
    }

    private void applyMode() {
        boolean fast = Math.round(painter.tune.get(VizTune.GLOBAL, "quality")) == VizTune.SMOOTH_90;
        int want = fast ? fastMode : sixtyMode;
        if (want == modeShown || want == 0 || !(getContext() instanceof Activity)) return;
        Window win = ((Activity) getContext()).getWindow();
        WindowManager.LayoutParams lp = win.getAttributes();
        lp.preferredDisplayModeId = want;
        win.setAttributes(lp);
        modeShown = want;
    }

    /** How far down the status bar reaches, even while it is hidden, so it never covers a button. */
    private float safeTop() {
        float min = 28 * density;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsets in = getRootWindowInsets();
            if (in != null) {
                int top = in.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()
                        | WindowInsets.Type.displayCutout()).top;
                return Math.max(min, top + 6 * density);
            }
        }
        return min;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = System.nanoTime();
        if (startNs == 0) startNs = now;
        float dt = lastNs == 0 ? 0.016f : Math.min(0.1f, (now - lastNs) / 1e9f);
        lastNs = now;
        if (dt > 0) painter.fps += (1 / dt - painter.fps) * Math.min(1, dt * 2);
        gfx.frame(painter, dt, getContext());
        painter.testing = gfx.testing;
        applyMode();
        float refresh = getDisplay() != null ? getDisplay().getRefreshRate() : 60;
        painter.pace = VizTune.pace(Math.round(painter.tune.get(VizTune.GLOBAL, "quality")), refresh);
        painter.frameBudget = painter.pace / Math.max(30f, refresh);
        Spectrum s = spectrum;
        if (s != null) {
            s.fallSpeed = painter.tune.get(painter.look, "fall");
            sound(s, now);
        }
        motion.step(dt, painter.tune.get(VizTune.GLOBAL, "tilt"));
        painter.sloshX = motion.sloshX();
        painter.sloshY = motion.sloshY();
        painter.safeTop = safeTop();
        boolean fade = painter.tune.on(VizTune.GLOBAL, "autohide") && !painter.drawerOpen && !painter.menuOpen
                && System.currentTimeMillis() - touchedMs > FADE_AFTER_MS;
        painter.chromeAlpha = Math.max(0, Math.min(1, painter.chromeAlpha + (fade ? -dt * 2 : dt * 4)));
        milkFrame();
        int w = getWidth(), h = getHeight();
        float seconds = (now - startNs) / 1e9f;
        if (effects != null && canvas.isHardwareAccelerated()) effects.draw(canvas, painter, w, h, dt, seconds);
        else painter.drawScene(canvas, w, h, dt, seconds);
        painter.drawChrome(canvas, w, h);
        drawMusic(canvas);
        vsyncs = 0;
        if (running) android.view.Choreographer.getInstance().postFrameCallback(tick);
    }

    /** Stores this moment's sound, then hands the painter the sound from "Speaker delay" ago. */
    private void sound(Spectrum s, long now) {
        int i = histAt = (histAt + 1) % HISTORY;
        s.copy(histLevels[i], histPeaks[i]);
        histVu[i] = s.vu();
        histBass[i] = s.bass();
        histBeat[i] = s.beat();
        histNs[i] = now;
        long want = now - (long) (painter.tune.get(VizTune.GLOBAL, "delay") * 1e9);
        int use = i;
        for (int k = 0; k < HISTORY; k++) {
            int j = Math.floorMod(i - k, HISTORY);
            if (histNs[j] == 0) break;
            use = j;
            if (histNs[j] <= want) break;
        }
        System.arraycopy(histLevels[use], 0, painter.levels, 0, VizPainter.BANDS);
        System.arraycopy(histPeaks[use], 0, painter.peaks, 0, VizPainter.BANDS);
        painter.vu = histVu[use];
        painter.bass = histBass[use];
        painter.beat = histBeat[use];
    }

    /** The plain music window beside the side menu's Music row, in dp like the dash's. */
    private void drawMusic(Canvas canvas) {
        RectF b = painter.musicButton;
        if (!painter.music || !painter.musicOpen || b.isEmpty()) {
            music = null;
            return;
        }
        float d = density;
        music = MusicLayout.place(getWidth() / d, getHeight() / d, b.left / d, b.top / d, b.right / d, b.bottom / d);
        canvas.save();
        canvas.scale(d, d);
        musicState.music = true;
        musicState.musicOpen = true;
        MusicPainter.draw(canvas, music, musicState, MusicPainter.plain());
        canvas.restore();
    }

    /** A finger on the music window is the window's; the rest of the screen works as usual. */
    private boolean musicTouch(MotionEvent e) {
        float x = e.getX() / density, y = e.getY() / density;
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            musicDown = music == null ? null : music.hit(x, y);
            return musicDown != null;
        }
        if (musicDown == null) return false;
        if (a == MotionEvent.ACTION_UP) {
            String part = music == null ? null : music.hit(x, y);
            if (musicDown.equals(part)) {
                if ("close".equals(part)) painter.musicOpen = false;
                else host.onMusic(part);
                invalidate();
            }
            musicDown = null;
        } else if (a == MotionEvent.ACTION_CANCEL) {
            musicDown = null;
        }
        return true;
    }

    private void touched() {
        touchedMs = System.currentTimeMillis();
        invalidate();
    }

    /** The top-left corner, where the menu button lives (a bit bigger, for fingers on a bumpy road). */
    private boolean inCorner(float x, float y) {
        return x < 180 * density && y < painter.safeTop + 130 * density;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_POINTER_DOWN && e.getPointerCount() == 3) {   // three fingers: hide or show all
            removeCallbacks(wake);
            painter.hidden = !painter.hidden;
            painter.drawerOpen = false;
            painter.menuOpen = false;
            sliding = false;
            tuneRow = -1;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            touched();
            return true;
        }
        if (e.getPointerCount() > 1) return true;
        float x = e.getX(), y = e.getY();
        if (painter.hidden) {                                   // only a hold on the corner (or three fingers) wakes it
            if (action == MotionEvent.ACTION_DOWN) {
                wakeHeld = false;
                if (inCorner(x, y)) postDelayed(wake, 700);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                removeCallbacks(wake);
            }
            return true;
        }
        if (musicTouch(e)) return true;
        if (action == MotionEvent.ACTION_DOWN && painter.chromeAlpha < 0.5f) {   // the faded button: wake it only
            if (inCorner(x, y)) touched();
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            sliding = painter.drawerOpen && painter.slider.contains(x, y);
            tuneRow = painter.menuOpen ? painter.rowAt(x, y) : -1;
            touchedMs = System.currentTimeMillis();
        }
        if (sliding) {
            painter.slide(x);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                sliding = false;
                save();
            }
            invalidate();
            return true;
        }
        if (tuneRow >= 0 && !painter.rowIsSwitch(tuneRow)) painter.dragRow(tuneRow, x);
        if (action == MotionEvent.ACTION_CANCEL) tuneRow = -1;
        if (action != MotionEvent.ACTION_UP) {
            invalidate();
            return true;
        }
        touched();
        if (tuneRow >= 0) {
            if (painter.rowIsSwitch(tuneRow) && painter.rowAt(x, y) == tuneRow) painter.flipRow(tuneRow);
            tuneRow = -1;
        } else if (painter.menuOpen) {
            menuTap(x, y);
        } else if (painter.drawerOpen) {
            drawerTap(x, y);
        } else if (painter.menuButton.contains(x, y)) {
            painter.drawerOpen = true;
        }
        save();
        return true;
    }

    /** A tap with the side menu open; a tap outside it closes it. */
    private void drawerTap(float x, float y) {
        VizPainter p = painter;
        if (!p.drawerRect.contains(x, y) || p.drawerClose.contains(x, y)) {
            p.drawerOpen = false;
        } else if (p.rowDash.contains(x, y)) {
            host.onDashButton();
        } else if (p.lookPrev.contains(x, y) || p.lookNext.contains(x, y)) {
            p.changeLook(p.lookNext.contains(x, y) ? VizTune.nextLook(p.look) : prevLook(p.look));
        } else if (p.colorPrev.contains(x, y)) {
            p.color = Math.floorMod(p.color - 1, VizPainter.COLORS.length);
        } else if (p.colorNext.contains(x, y)) {
            p.color = (p.color + 1) % VizPainter.COLORS.length;
        } else if (p.rowTune.contains(x, y) || p.rowEffects.contains(x, y)) {
            p.menuTab = p.rowTune.contains(x, y) ? VizPainter.TAB_LOOK : VizPainter.TAB_EFFECTS;
            p.menuOpen = true;
            p.drawerOpen = false;
        } else if (milk != null && p.milkPrev.contains(x, y)) {
            milk.step.set(-1);
        } else if (milk != null && p.milkNext.contains(x, y)) {
            milk.step.set(1);
        } else if (milk != null && p.milkLock.contains(x, y)) {
            p.milkLocked = !p.milkLocked;
        } else if (milk != null && p.milkStar.contains(x, y)) {
            milk.list.star(milk.current, !milk.list.starred(milk.current));
            milk.saveStars();
        } else if (milk != null && p.milkPlay.contains(x, y)) {
            p.tune.set(VizTune.MILK, "play", (Math.round(p.tune.get(VizTune.MILK, "play")) + 1) % 3);
        } else if (p.rowHide.contains(x, y)) {
            p.drawerOpen = false;
            p.hidden = true;
        } else if (p.music && p.musicButton.contains(x, y)) {
            p.musicOpen = !p.musicOpen;
            p.drawerOpen = false;
        }
    }

    private static int prevLook(int look) {
        int n = VizTune.CHOICES.length;
        for (int i = 0; i < n; i++) if (VizTune.CHOICES[i] == look) return VizTune.CHOICES[(i + n - 1) % n];
        return VizTune.CHOICES[0];
    }

    /** A tap inside the tune window; a tap outside it closes it. */
    private void menuTap(float x, float y) {
        VizPainter p = painter;
        if (!p.menuWindow.contains(x, y) || p.menuClose.contains(x, y)) {
            p.menuOpen = false;
            return;
        }
        for (int i = 0; i < p.tabRects.length; i++) if (p.tabRects[i].contains(x, y)) p.menuTab = i;
        if (p.menuTab == VizPainter.TAB_COLORS) {
            for (int i = 0; i < p.colorCells.length; i++) if (p.colorCells[i].contains(x, y)) p.color = i;
        } else if (p.menuTab == VizPainter.TAB_PRESETS) {
            if (p.gfxTest.contains(x, y)) {
                gfx.startTest(p);
                p.menuOpen = !gfx.testing;                          // close the window so the test sees the looks
            }
            if (p.gfxShare.contains(x, y) && getContext() instanceof Activity) gfx.share((Activity) getContext());
            p.testing = gfx.testing;
            for (int i = 0; i < VizPainter.PRESETS; i++) {
                if (p.presetSave[i].contains(x, y)) savePreset(i);
                else if (p.presetLoad[i].contains(x, y)) loadPreset(i);
            }
        } else if (p.resetButton.contains(x, y)) {
            p.tune.reset(p.target());
        } else if (milk != null && p.milkDownload.contains(x, y) && !milk.packInstalled()) {
            milk.downloadPack();
        }
    }

    private void save() {
        if (gfx.testing) {                                          // the test's temporary settings are never saved
            invalidate();
            return;
        }
        prefs.edit().putInt("look2", painter.look).putInt("color", painter.color).putInt("bars", painter.shown)
                .putString("tune", painter.tune.save()).apply();
        invalidate();
    }
}
