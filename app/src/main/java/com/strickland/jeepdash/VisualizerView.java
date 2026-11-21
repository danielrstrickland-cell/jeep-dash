package com.strickland.jeepdash;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import com.strickland.jeepdash.core.Spectrum;
import com.strickland.jeepdash.core.VizTune;

/**
 * The visualizer screen: tap for the next look, COLORS for the next colour profile, drag the BARS slider,
 * TUNE for a drop-down panel that adjusts the look on screen (every look remembers its own settings).
 */
public class VisualizerView extends View {
    public interface Host { void onDashButton(); }

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
        prefs = context.getSharedPreferences("viz", Context.MODE_PRIVATE);
        painter.swirl = new SwirlLook();
        painter.look = Math.floorMod(prefs.getInt("look2", 0), VizPainter.LOOKS.length);
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
        if (running) postInvalidateOnAnimation();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
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
        } else if (painter.tuneOpen) {
            if (painter.resetButton.contains(x, y)) painter.tune.reset(painter.look);
            else if (!painter.panel.contains(x, y)) painter.tuneOpen = false;      // a tap outside closes the panel
        } else {
            painter.look = (painter.look + 1) % VizPainter.LOOKS.length;
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
