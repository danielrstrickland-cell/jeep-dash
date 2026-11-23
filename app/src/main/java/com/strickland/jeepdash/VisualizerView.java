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
 * TUNE for a drop-down panel that adjusts the look on screen (every look remembers its own settings). A tap or
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
        painter.draw(canvas, getWidth(), getHeight(), dt, (now - startNs) / 1e9f);
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
                else if (!"window".equals(part)) host.onMusic(part);
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
        if (action == MotionEvent.ACTION_DOWN) tuneRow = painter.tuneOpen ? painter.rowAt(x, y) : -1;
        if (tuneRow >= 0 && !painter.rowIsSwitch(tuneRow)) painter.dragRow(tuneRow, x);
        if (action != MotionEvent.ACTION_UP) return true;
        if (tuneRow >= 0) {
            if (painter.rowIsSwitch(tuneRow) && painter.rowAt(x, y) == tuneRow) painter.flipRow(tuneRow);
            tuneRow = -1;
        } else if (painter.dashButton.contains(x, y)) {
            host.onDashButton();
        } else if (painter.colorButton.contains(x, y)) {
            painter.color = (painter.color + 1) % VizPainter.COLORS.length;
        } else if (painter.tuneButton.contains(x, y)) {
            painter.tuneOpen = !painter.tuneOpen;
        } else if (painter.lookButton.contains(x, y)) {
            painter.look = VizTune.nextLook(painter.look);
        } else if (painter.music && painter.musicButton.contains(x, y)) {
            painter.musicOpen = !painter.musicOpen;
        } else if (painter.tuneOpen) {
            if (painter.resetButton.contains(x, y)) painter.tune.reset(painter.look);
            else if (!painter.panel.contains(x, y)) painter.tuneOpen = false;      // a tap outside closes the panel
        }
        save();
        return true;
    }

    private void save() {
        prefs.edit().putInt("look2", painter.look).putInt("color", painter.color).putInt("bars", painter.shown)
                .putString("tune", painter.tune.save()).apply();
        invalidate();
    }
}
