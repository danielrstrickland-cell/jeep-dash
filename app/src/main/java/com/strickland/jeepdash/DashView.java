package com.strickland.jeepdash;

import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;

import com.strickland.jeepdash.core.Catalog;
import com.strickland.jeepdash.core.GaugeSpec;
import com.strickland.jeepdash.core.Layout;
import com.strickland.jeepdash.core.LiveValues;
import com.strickland.jeepdash.core.Param;
import com.strickland.jeepdash.theme.Theme;

import java.util.HashMap;
import java.util.Map;

/** Draws the gauges through the current look and reports taps on the look's buttons. */
public class DashView extends View {

    public interface Host {
        /** The view knows its size in dp (first layout, rotation). */
        void onDashSize(float wDp, float hDp);
        /** A button of the look was tapped (see Theme.hit). */
        void onDashAction(String action);
    }

    private static final double NEEDLE_MS = 160;      // needle catch-up time constant

    private final Host host;
    private final float density;
    public final Theme.Chrome chrome = new Theme.Chrome();
    private Theme theme;
    private Layout layout;
    private LiveValues values;
    private final Map<String, Double> shown = new HashMap<>();
    private long lastFrameMs;
    private float wDp, hDp;
    private String downAction;

    public DashView(Context c, Host host) {
        super(c);
        this.host = host;
        this.density = c.getResources().getDisplayMetrics().density;
    }

    public void setTheme(Theme t) { theme = t; shown.clear(); invalidate(); }

    public void setLayout(Layout l) { layout = l; shown.clear(); invalidate(); }

    public Layout layout() { return layout; }

    public void setValues(LiveValues v) { values = v; invalidate(); }

    public float widthDp() { return wDp; }

    public float heightDp() { return hDp; }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        wDp = w / density;
        hDp = h / density;
        if (w > 0 && h > 0) host.onDashSize(wDp, hDp);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (theme == null || wDp <= 0) return;
        long now = LinkService.now();
        double dt = lastFrameMs == 0 ? 1000 : Math.min(1000, now - lastFrameMs);
        lastFrameMs = now;
        boolean blink = (now / 250) % 2 == 0;      // 2 Hz
        boolean moving = false, flashing = false;

        c.save();
        c.scale(density, density);
        theme.drawBackground(c, wDp, hDp);
        if (!"gauges".equals(chrome.tab)) {
            theme.drawPanel(c, wDp, hDp, "Visualizer", "Coming in the next update");
        } else if (layout != null) {
            for (GaugeSpec g : layout.gauges) {
                Param p = Catalog.byId(g.paramId);
                if (p == null) continue;
                Double v = values == null ? null : values.get(p.id, now);
                Double s = null;
                if (v == null) {
                    shown.remove(g.key);
                } else {
                    Double prev = shown.get(g.key);
                    double next = prev == null ? v : prev + (v - prev) * (1 - Math.exp(-dt / NEEDLE_MS));
                    if (Math.abs(v - next) < (p.max - p.min) * 0.001) next = v; else moving = true;
                    shown.put(g.key, next);
                    s = next;
                }
                boolean warn = Layout.shouldFlash(g, v);
                flashing |= warn;
                theme.drawGauge(c, g, p, v, s, warn && blink, chrome.editing);
            }
        }
        theme.drawChrome(c, wDp, hDp, chrome);
        c.restore();
        if (moving) postInvalidateOnAnimation();
        else if (flashing) postInvalidateDelayed(100);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (theme == null) return false;
        float x = e.getX() / density, y = e.getY() / density;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downAction = theme.hit(x, y);
                return true;
            case MotionEvent.ACTION_UP:
                String a = theme.hit(x, y);
                if (a != null && a.equals(downAction)) {
                    performClick();
                    host.onDashAction(a);
                }
                downAction = null;
                return true;
            case MotionEvent.ACTION_CANCEL:
                downAction = null;
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() { return super.performClick(); }
}
