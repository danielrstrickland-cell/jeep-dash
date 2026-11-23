package com.strickland.jeepdash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import com.strickland.jeepdash.core.Catalog;
import com.strickland.jeepdash.core.GaugeSpec;
import com.strickland.jeepdash.core.Layout;
import com.strickland.jeepdash.core.LiveValues;
import com.strickland.jeepdash.core.Param;
import com.strickland.jeepdash.core.Units;
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
        /** Edit mode: a gauge was tapped (not dragged). */
        void onGaugeTap(String key);
        /** Edit mode: a gauge was moved or resized. */
        void onLayoutEdited();
    }

    private static final float HANDLE = 48;           // dp: the resize corner
    private static final float SLOP = 10;             // dp: movement below this is a tap
    private static final float GRID = 16;

    private static final double NEEDLE_MS = 160;      // needle catch-up time constant
    private static final long IDLE_REDRAW_MS = 30000; // nothing moving: redraw anyway (clock, XP brightness on Auto)

    private final Host host;
    private final float density;
    public final Theme.Chrome chrome = new Theme.Chrome();
    private Theme theme;
    private Layout layout;
    private LiveValues values;
    private final Map<String, Double> shown = new HashMap<>();
    private final Map<String, Param> inUnits = new HashMap<>();     // gauges shown in another unit, by id and unit
    private final Runnable idleRedraw = this::invalidate;
    private long lastFrameMs;
    private float wDp, hDp;
    private String downAction;
    private String dragKey;
    private boolean resizing, moved;
    private float downX, downY, lastX, lastY;
    private final Paint editPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final DashPathEffect dashes = new DashPathEffect(new float[]{10, 6}, 0);

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
                boolean warn = Layout.shouldFlash(g, v);         // the limit is kept in the value's own unit
                flashing |= warn;
                Param d = display(p, g.unit);
                if (d != p) {
                    if (v != null) v = Units.convert(v, p.unit, d.unit);
                    if (s != null) s = Units.convert(s, p.unit, d.unit);
                }
                theme.drawGauge(c, g, d, v, s, warn && blink, chrome.editing);
            }
        }
        if (chrome.editing && layout != null && "gauges".equals(chrome.tab)) drawEditMarks(c);
        theme.drawChrome(c, wDp, hDp, chrome);
        c.restore();
        if (moving) postInvalidateOnAnimation();
        else if (flashing) postInvalidateDelayed(100);
        // one pending at a time, so it never adds frames of its own while readings are coming in
        removeCallbacks(idleRedraw);
        postDelayed(idleRedraw, IDLE_REDRAW_MS);
    }

    /** The value as its gauge shows it: converted to the gauge's chosen unit, made once and kept. */
    private Param display(Param p, String unit) {
        if (unit == null) return p;
        String key = System.identityHashCode(p) + "|" + p.id + "|" + unit;
        Param d = inUnits.get(key);
        if (d == null) {
            d = Units.display(p, unit);
            inUnits.put(key, d);
        }
        return d;
    }

    /** Dashed outline and a corner handle on every gauge while editing. */
    private void drawEditMarks(Canvas c) {
        for (GaugeSpec g : layout.gauges) {
            boolean active = g.key.equals(dragKey);
            editPaint.setStyle(Paint.Style.STROKE);
            editPaint.setPathEffect(null);
            editPaint.setStrokeWidth(4);
            editPaint.setColor(0x99000000);
            c.drawRect(g.x, g.y, g.x + g.w, g.y + g.h, editPaint);
            editPaint.setStrokeWidth(2);
            editPaint.setPathEffect(dashes);
            editPaint.setColor(active ? 0xffffffff : 0xffffd400);
            c.drawRect(g.x, g.y, g.x + g.w, g.y + g.h, editPaint);
            editPaint.setPathEffect(null);
            editPaint.setStyle(Paint.Style.FILL);
            float hx = g.x + g.w, hy = g.y + g.h;
            editPaint.setColor(0xcc000000);
            c.drawRect(hx - 26, hy - 26, hx, hy, editPaint);
            editPaint.setColor(active ? 0xffffffff : 0xffffd400);
            c.drawRect(hx - 22, hy - 22, hx - 4, hy - 4, editPaint);
        }
    }

    private GaugeSpec gaugeAt(float x, float y) {
        for (int i = layout.gauges.size() - 1; i >= 0; i--) {
            GaugeSpec g = layout.gauges.get(i);
            if (x >= g.x && x <= g.x + g.w && y >= g.y && y <= g.y + g.h) return g;
        }
        return null;
    }

    private boolean editTouch(MotionEvent e, float x, float y) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                GaugeSpec g = gaugeAt(x, y);
                if (g == null) return false;
                dragKey = g.key;
                resizing = x > g.x + g.w - HANDLE && y > g.y + g.h - HANDLE;
                moved = false;
                downX = lastX = x;
                downY = lastY = y;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (dragKey == null) return false;
                if (!moved && Math.hypot(x - downX, y - downY) < SLOP) return true;
                moved = true;
                if (resizing) layout.resize(dragKey, x - lastX, y - lastY);
                else layout.move(dragKey, x - lastX, y - lastY);
                lastX = x;
                lastY = y;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP: {
                if (dragKey == null) return false;
                String key = dragKey;
                dragKey = null;
                if (moved) {
                    if (chrome.snap) layout.snapOne(key, GRID);
                    host.onLayoutEdited();
                } else {
                    performClick();
                    host.onGaugeTap(key);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                if (dragKey == null) return false;
                dragKey = null;
                host.onLayoutEdited();
                invalidate();
                return true;
            default:
                return dragKey != null;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (theme == null) return false;
        float x = e.getX() / density, y = e.getY() / density;
        boolean editingGauges = chrome.editing && layout != null && "gauges".equals(chrome.tab);
        if (editingGauges && (dragKey != null || theme.hit(x, y) == null) && editTouch(e, x, y)) return true;
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
