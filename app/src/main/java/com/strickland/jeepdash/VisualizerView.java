package com.strickland.jeepdash;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import com.strickland.jeepdash.core.MusicLayout;
import com.strickland.jeepdash.core.Spectrum;
import com.strickland.jeepdash.core.VizTune;
import com.strickland.jeepdash.theme.MusicPainter;
import com.strickland.jeepdash.theme.Theme;

/**
 * The visualizer screen: LOOK for the next look, COLORS for the next colour profile, drag the BARS slider,
 * TUNE for a drop-down panel that adjusts the look on screen (every look remembers its own settings); hold TUNE
 * for the hidden tune menu (look, colours, effects, presets). A tap or
 * swipe on the picture itself does nothing, so brightness swipes cannot change the look. MUSIC (when on) opens
 * the plain music window.
 */
public class VisualizerView extends View {
    public interface Host {
        void onDashButton();
        /** prev, play or next on the music window. */
        void onMusic(String part);
    }

    /** The music window's text and state, in the same form the dash's looks get it. */
    final Theme.Chrome musicState = new Theme.Chrome();
    private MusicLayout music;
    private String musicDown;
    private final float density;

    final VizPainter painter = new VizPainter();
    private final SharedPreferences prefs;
    private final Host host;
    private volatile Spectrum spectrum;
    private long lastNs, startNs;
    private boolean running, sliding;
    private int tuneRow = -1;
    private final VizEffects effects = VizEffects.supported() ? new VizEffects() : null;
    private boolean held;                                   // the long press on TUNE opened the menu
    private final Runnable holdTune = () -> {
        held = true;
        painter.tuneOpen = false;
        painter.menuOpen = true;
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        invalidate();
    };

    public VisualizerView(Context context, Host host) {
        super(context);
        this.host = host;
        density = context.getResources().getDisplayMetrics().density;
        prefs = context.getSharedPreferences("viz", Context.MODE_PRIVATE);
        painter.swirl = new SwirlLook();
        painter.look = VizTune.savedLook(prefs.getInt("look2", 0));      // the old boom meters become Smooth
        painter.color = Math.floorMod(prefs.getInt("color", 1), VizPainter.COLORS.length);
        int bars = prefs.getInt("bars", 32);
        for (int n : VizPainter.COUNTS) if (n == bars) painter.shown = n;
        painter.tune = VizTune.load(prefs.getString("tune", null));
        for (int i = 0; i < VizPainter.PRESETS; i++) painter.presetInfo[i] = describe(prefs.getString("preset" + i, null));
    }

    /** "Swirl · Vice" for a saved preset, or "Empty". */
    private static String describe(String preset) {
        String[] part = preset == null ? null : preset.split("\\|", 3);
        if (part == null || part.length < 3) return "Empty";
        try {
            int look = VizTune.savedLook(Integer.parseInt(part[0]));
            int color = Math.floorMod(Integer.parseInt(part[1]), VizPainter.COLORS.length);
            return VizPainter.LOOKS[look] + " \u00B7 " + VizPainter.COLORS[color];
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

    void setSpectrum(Spectrum s) { spectrum = s; }

    void setRunning(boolean on) {
        running = on;
        lastNs = 0;
        if (on) invalidate();
    }

    /** Asks for the screen's fastest mode at its current size, so motion is as smooth as the tablet allows. */
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Display d = getDisplay();
        if (d == null || !(getContext() instanceof Activity)) return;
        Display.Mode now = d.getMode(), best = now;
        for (Display.Mode m : d.getSupportedModes()) {
            if (m.getPhysicalWidth() == now.getPhysicalWidth() && m.getPhysicalHeight() == now.getPhysicalHeight()
                    && m.getRefreshRate() > best.getRefreshRate()) best = m;
        }
        Window win = ((Activity) getContext()).getWindow();
        WindowManager.LayoutParams lp = win.getAttributes();
        lp.preferredDisplayModeId = best.getModeId();
        win.setAttributes(lp);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = System.nanoTime();
        if (startNs == 0) startNs = now;
        float dt = lastNs == 0 ? 0.016f : Math.min(0.1f, (now - lastNs) / 1e9f);
        lastNs = now;
        Spectrum s = spectrum;
        if (s != null) {
            s.fallSpeed = painter.tune.get(painter.look, "fall");
            s.copy(painter.levels, painter.peaks);
            painter.vu = s.vu();
            painter.bass = s.bass();
            painter.beat = s.beat();
        }
        int w = getWidth(), h = getHeight();
        float seconds = (now - startNs) / 1e9f;
        if (effects != null && canvas.isHardwareAccelerated()) effects.draw(canvas, painter, w, h, dt, seconds);
        else painter.drawScene(canvas, w, h, dt, seconds);
        painter.drawChrome(canvas, w, h);
        drawMusic(canvas);
        if (running) postInvalidateOnAnimation();
    }

    /** The plain music window beside the MUSIC button, in dp like the dash's. */
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

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (musicTouch(e)) return true;
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) sliding = painter.slider.contains(e.getX(), e.getY());
        if (sliding) {
            painter.slide(e.getX());
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                sliding = false;
                save();
            }
            return true;
        }
        float x = e.getX(), y = e.getY();
        if (action == MotionEvent.ACTION_DOWN) {
            held = false;
            if (painter.tuneButton.contains(x, y)) postDelayed(holdTune, 450);
            tuneRow = painter.tuneOpen || painter.menuOpen ? painter.rowAt(x, y) : -1;
        }
        if (action == MotionEvent.ACTION_MOVE && !painter.tuneButton.contains(x, y)) removeCallbacks(holdTune);
        if (tuneRow >= 0 && !painter.rowIsSwitch(tuneRow)) painter.dragRow(tuneRow, x);
        if (action == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(holdTune);
            tuneRow = -1;
        }
        if (action != MotionEvent.ACTION_UP) return true;
        removeCallbacks(holdTune);
        if (held) {
            held = false;
            save();
            return true;
        }
        if (tuneRow >= 0) {
            if (painter.rowIsSwitch(tuneRow) && painter.rowAt(x, y) == tuneRow) painter.flipRow(tuneRow);
            tuneRow = -1;
        } else if (painter.dashButton.contains(x, y)) {
            host.onDashButton();
        } else if (painter.colorButton.contains(x, y)) {
            painter.color = (painter.color + 1) % VizPainter.COLORS.length;
        } else if (painter.tuneButton.contains(x, y)) {
            if (painter.menuOpen) painter.menuOpen = false;
            else painter.tuneOpen = !painter.tuneOpen;
        } else if (painter.lookButton.contains(x, y)) {
            painter.changeLook(VizTune.nextLook(painter.look));
        } else if (painter.music && painter.musicButton.contains(x, y)) {
            painter.musicOpen = !painter.musicOpen;
        } else if (painter.menuOpen) {
            menuTap(x, y);
        } else if (painter.tuneOpen) {
            if (painter.resetButton.contains(x, y)) painter.tune.reset(painter.look);
            else if (!painter.panel.contains(x, y)) painter.tuneOpen = false;      // a tap outside closes the panel
        }
        save();
        return true;
    }

    /** A tap inside the hidden menu; a tap outside it closes it. */
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
            for (int i = 0; i < VizPainter.PRESETS; i++) {
                if (p.presetSave[i].contains(x, y)) savePreset(i);
                else if (p.presetLoad[i].contains(x, y)) loadPreset(i);
            }
        } else if (p.resetButton.contains(x, y)) {
            p.tune.reset(p.target());
        }
    }

    private void save() {
        prefs.edit().putInt("look2", painter.look).putInt("color", painter.color).putInt("bars", painter.shown)
                .putString("tune", painter.tune.save()).apply();
        invalidate();
    }
}
