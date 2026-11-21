package com.strickland.jeepdash;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;

import com.strickland.jeepdash.core.Spectrum;

/** The visualizer screen: tap anywhere for the next look, COLORS for the next colour profile. */
public class VisualizerView extends View {
    public interface Host { void onDashButton(); }

    final VizPainter painter = new VizPainter();
    private final SharedPreferences prefs;
    private final Host host;
    private volatile Spectrum spectrum;
    private long lastNs, startNs;
    private boolean running;

    public VisualizerView(Context context, Host host) {
        super(context);
        this.host = host;
        prefs = context.getSharedPreferences("viz", Context.MODE_PRIVATE);
        painter.look = Math.floorMod(prefs.getInt("look", 0), VizPainter.LOOKS.length);
        painter.color = Math.floorMod(prefs.getInt("color", 1), VizPainter.COLORS.length);
    }

    void setSpectrum(Spectrum s) { spectrum = s; }

    void setRunning(boolean on) {
        running = on;
        lastNs = 0;
        if (on) invalidate();
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
        if (e.getActionMasked() != MotionEvent.ACTION_UP) return true;
        if (painter.dashButton.contains(e.getX(), e.getY())) {
            host.onDashButton();
        } else if (painter.colorButton.contains(e.getX(), e.getY())) {
            painter.color = (painter.color + 1) % VizPainter.COLORS.length;
        } else {
            painter.look = (painter.look + 1) % VizPainter.LOOKS.length;
        }
        prefs.edit().putInt("look", painter.look).putInt("color", painter.color).apply();
        invalidate();
        return true;
    }
}
