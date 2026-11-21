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

/** The visualizer screen: tap for the next look, COLORS for the next colour profile, drag the BARS slider. */
public class VisualizerView extends View {
    public interface Host { void onDashButton(); }

    final VizPainter painter = new VizPainter();
    private final SharedPreferences prefs;
    private final Host host;
    private volatile Spectrum spectrum;
    private long lastNs, startNs;
    private boolean running, sliding;

    public VisualizerView(Context context, Host host) {
        super(context);
        this.host = host;
        prefs = context.getSharedPreferences("viz", Context.MODE_PRIVATE);
        painter.swirl = new SwirlLook();
        painter.look = Math.floorMod(prefs.getInt("look2", 0), VizPainter.LOOKS.length);
        painter.color = Math.floorMod(prefs.getInt("color", 1), VizPainter.COLORS.length);
        int bars = prefs.getInt("bars", 32);
        for (int n : VizPainter.COUNTS) if (n == bars) painter.shown = n;
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
        if (action != MotionEvent.ACTION_UP) return true;
        if (painter.dashButton.contains(e.getX(), e.getY())) {
            host.onDashButton();
        } else if (painter.colorButton.contains(e.getX(), e.getY())) {
            painter.color = (painter.color + 1) % VizPainter.COLORS.length;
        } else {
            painter.look = (painter.look + 1) % VizPainter.LOOKS.length;
        }
        save();
        return true;
    }

    private void save() {
        prefs.edit().putInt("look2", painter.look).putInt("color", painter.color).putInt("bars", painter.shown).apply();
        invalidate();
    }
}
