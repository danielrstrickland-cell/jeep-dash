package com.strickland.jeepdash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.strickland.jeepdash.core.Catalog;
import com.strickland.jeepdash.core.GaugeSpec;
import com.strickland.jeepdash.core.GaugeType;
import com.strickland.jeepdash.core.HeatGuard;
import com.strickland.jeepdash.core.HoloFace;
import com.strickland.jeepdash.core.Layout;
import com.strickland.jeepdash.core.LiveValues;
import com.strickland.jeepdash.core.Materialize;
import com.strickland.jeepdash.core.MusicLayout;
import com.strickland.jeepdash.core.Param;
import com.strickland.jeepdash.core.Units;
import com.strickland.jeepdash.scene.HoloLoop;
import com.strickland.jeepdash.theme.HoloTheme;
import com.strickland.jeepdash.theme.MusicPainter;
import com.strickland.jeepdash.theme.Theme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws the gauges through the current look and reports taps on the look's buttons. In the Holo look it is the
 * layer in front of the 3D truck: gauges floating behind the truck are drawn by HoloBackView (through
 * drawHoloLayer), the glow by HoloBloomView, and redraws are paced by HoloLoop.
 */
public class DashView extends View implements HoloLoop.Layers {

    public interface Host {
        /** The view knows its size in dp (first layout, rotation). */
        void onDashSize(float wDp, float hDp);
        /** A button of the look was tapped (see Theme.hit). */
        void onDashAction(String action);
        /** Edit mode: a gauge was tapped (not dragged). */
        void onGaugeTap(String key);
        /** Edit mode: a gauge was moved or resized. */
        void onLayoutEdited();
        /** Holo look: where the 3D truck's box is now (dp), or null when there is none. */
        default void onSceneRect(GaugeSpec truck) { }
        /** Holo look: the truck was tapped, or held. */
        default void onTruckTap() { }
        default void onTruckHold() { }
    }

    private static final float HANDLE = 48;           // dp: the resize corner
    private static final float SLOP = 10;             // dp: movement below this is a tap
    private static final float GRID = 16;
    private static final long HOLD_MS = 550;
    private static final float HEAT_ICON = 34;        // dp

    private static final double NEEDLE_MS = 160;      // needle catch-up time constant
    private static final long IDLE_REDRAW_MS = 30000; // nothing moving: redraw anyway (clock, XP brightness on Auto)

    private final Host host;
    private final float density;
    public final Theme.Chrome chrome = new Theme.Chrome();
    private Theme theme;
    private Layout layout;
    private LiveValues values;
    private final Map<String, Double> shown = new HashMap<>();
    private long lastFront, lastBack;
    private final Map<String, Param> inUnits = new HashMap<>();     // gauges shown in another unit, by id and unit
    private final Runnable idleRedraw = this::invalidate;
    private long lastFrameMs;
    private float wDp, hDp;
    private String downAction;
    private MusicLayout music;                         // the music window where it was last drawn; null when closed
    private String musicDown;                          // the part of it a finger went down on
    private String dragKey;
    private boolean resizing, moved;
    private float downX, downY, lastX, lastY;
    private final Paint editPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final DashPathEffect dashes = new DashPathEffect(new float[]{10, 6}, 0);

    // the Holo look
    private HoloTheme holo;
    private View back;
    private View[] glows = new View[0];
    private HoloLoop loop;
    private final Map<String, Materialize> anim = new HashMap<>();
    private final Map<String, GaugeSpec> seen = new LinkedHashMap<>();
    private final Map<String, GaugeSpec> ghosts = new LinkedHashMap<>();
    private final Map<String, String> ghostParam = new HashMap<>();
    private GaugeSpec truckRect;
    private boolean holoMoving;
    private boolean truckDown, holdFired;
    private final Runnable hold = this::fireHold;

    // the tablet heat icon (any look), shown at moderate heat and above
    private int heat;
    private final RectF heatRect = new RectF();

    public DashView(Context c, Host host) {
        super(c);
        this.host = host;
        this.density = c.getResources().getDisplayMetrics().density;
    }

    public void setTheme(Theme t) {
        theme = t;
        shown.clear();
        holo = t instanceof HoloTheme ? (HoloTheme) t : null;
        if (holo == null) {
            anim.clear();
            ghosts.clear();
            seen.clear();
        }
        invalidate();
    }

    /** The Holo look's other layers and frame clock (null when another look is on). */
    public void setHoloLayers(View back, View[] glows, HoloLoop loop) {
        this.back = back;
        this.glows = glows == null ? new View[0] : glows;
        this.loop = loop;
    }

    public void setLayout(Layout l) {
        layout = l;
        shown.clear();
        // a new layout on screen: every panel materializes, one after another
        anim.clear();
        ghosts.clear();
        seen.clear();
        if (holo != null && l != null) {
            long now = LinkService.now(), ms = holo.st.fx.animateMs;
            int i = 0;
            for (GaugeSpec g : l.gauges) {
                anim.put(g.key, Materialize.opening(now + 25L * i++, ms));
                seen.put(g.key, copy(g));
            }
        }
        invalidate();
    }

    public Layout layout() { return layout; }

    public void setValues(LiveValues v) { values = v; invalidate(); }

    public float widthDp() { return wDp; }

    public float heightDp() { return hDp; }

    /** Tablet heat (HeatGuard level): the icon shows from moderate. */
    public void setHeat(int level) {
        if (level != heat) {
            heat = level;
            invalidate();
        }
    }

    /** Holo look: every panel dematerializes, then runs the given step (switching look, say). */
    public void dematerializeAll(Runnable then) {
        long ms = holo == null || layout == null ? 0 : holo.st.fx.animateMs;
        if (ms <= 0) {
            then.run();
            return;
        }
        long now = LinkService.now();
        for (GaugeSpec g : layout.gauges) anim.put(g.key, Materialize.closing(now, ms));
        invalidate();
        postDelayed(then, ms + 30);
    }

    @Override
    public void invalidate() {
        if (holo != null && loop != null && loop.running()) loop.poke();
        else super.invalidate();
    }

    /** HoloLoop: redraw every Holo layer now; true while something still animates. */
    @Override
    public boolean redraw(long nowMs) {
        if (holo == null) return false;
        holo.frameTime(nowMs);
        super.invalidate();
        if (back != null) back.invalidate();
        for (View g : glows) g.invalidate();
        boolean busy = holoMoving || !ghosts.isEmpty();
        for (Materialize m : anim.values()) busy |= !m.done(nowMs);
        return busy;
    }

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
        if (holo != null && "gauges".equals(chrome.tab) && layout != null) {
            // the backdrop and the gauges behind the truck are HoloBackView's
            if (back == null) holo.drawBackground(c, wDp, hDp);
            noticeChanges(now);
            GaugeSpec truck = null;
            for (GaugeSpec g : layout.gauges) if (g.type == GaugeType.TRUCK) truck = g;
            reportTruck(truck);
            holoMoving = drawHoloLayer(c, false, now);
            if (back == null) holoMoving |= drawHoloLayer(c, true, now);
            holo.drawStage(c, truck, wDp, hDp, false);
        } else {
            reportTruck(null);
            theme.drawBackground(c, wDp, hDp);
            if (!"gauges".equals(chrome.tab)) {
                theme.drawPanel(c, wDp, hDp, "Visualizer", "Coming in the next update");
            } else if (layout != null) {
                for (GaugeSpec g : layout.gauges) {
                    Param p = Catalog.byId(g.paramId);
                    if (p == null) continue;
                    Double v = values == null ? null : values.get(p.id, now);
                    Double s = tween(g.key, p, v, dt);
                    if (s != null && !s.equals(v)) moving = true;
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
        }
        if (chrome.editing && layout != null && "gauges".equals(chrome.tab)) drawEditMarks(c);
        theme.drawChrome(c, wDp, hDp, chrome);
        drawHeat(c);
        drawMusic(c);
        c.restore();
        if (holo != null && loop != null && loop.running()) return;        // HoloLoop paces the Holo look
        if (moving) postInvalidateOnAnimation();
        else if (flashing) postInvalidateDelayed(100);
        // one pending at a time, so it never adds frames of its own while readings are coming in
        removeCallbacks(idleRedraw);
        postDelayed(idleRedraw, IDLE_REDRAW_MS);
    }

    /** The smoothed reading for needles, bars and gliding figures; null with no reading. */
    private Double tween(String key, Param p, Double v, double dt) {
        if (v == null) {
            shown.remove(key);
            return null;
        }
        Double prev = shown.get(key);
        double next = prev == null ? v : prev + (v - prev) * (1 - Math.exp(-dt / NEEDLE_MS));
        if (Math.abs(v - next) < (p.max - p.min) * 0.001) next = v;
        shown.put(key, next);
        return next;
    }

    private void reportTruck(GaugeSpec t) {
        boolean same = t == null ? truckRect == null
                : truckRect != null && t.x == truckRect.x && t.y == truckRect.y && t.w == truckRect.w && t.h == truckRect.h;
        if (same) return;
        truckRect = t == null ? null : copy(t);
        final GaugeSpec r = truckRect;
        post(() -> host.onSceneRect(r));           // not in the middle of drawing
    }

    private static GaugeSpec copy(GaugeSpec g) {
        GaugeSpec c = new GaugeSpec(g.key, g.paramId, g.type, g.x, g.y, g.w, g.h);
        c.depth = g.depth;
        c.unit = g.unit;
        return c;
    }

    /** New gauges materialize; removed ones dematerialize as ghosts. */
    private void noticeChanges(long now) {
        long ms = holo.st.fx.animateMs;
        List<String> gone = new ArrayList<>(seen.keySet());
        for (GaugeSpec g : layout.gauges) {
            gone.remove(g.key);
            if (!seen.containsKey(g.key)) anim.put(g.key, Materialize.opening(now, ms));
            seen.put(g.key, copy(g));
            ghosts.remove(g.key);
        }
        for (String k : gone) {
            GaugeSpec g = seen.remove(k);
            if (ms > 0 && g != null && g.type != GaugeType.TRUCK) {
                ghosts.put(k, g);
                ghostParam.put(k, g.paramId);
                anim.put(k, Materialize.closing(now, ms));
            } else {
                anim.remove(k);
            }
        }
        List<String> done = new ArrayList<>();
        for (String k : ghosts.keySet()) if (anim.get(k) == null || anim.get(k).gone(now)) done.add(k);
        for (String k : done) {
            ghosts.remove(k);
            anim.remove(k);
        }
    }

    /**
     * Holo look: draws one layer of gauges (behind the truck, or level with and in front of it), each floated,
     * tilted and animated by the look. True while any of them is still moving toward its reading.
     */
    public boolean drawHoloLayer(Canvas c, boolean behind, long now) {
        if (holo == null || layout == null || !"gauges".equals(chrome.tab)) return false;
        long last = behind ? lastBack : lastFront;
        double dt = last == 0 ? 16 : Math.max(1, Math.min(1000, now - last));
        if (behind) lastBack = now;
        else lastFront = now;
        boolean blink = (now / 250) % 2 == 0, moving = false;
        List<GaugeSpec> all = new ArrayList<>(layout.gauges);
        all.addAll(ghosts.values());
        for (GaugeSpec g : all) {
            if ((g.depth < 0) != behind) continue;
            Param p = Catalog.byId(g.paramId);
            if (p == null) continue;
            boolean ghost = ghosts.containsKey(g.key);
            Double v = ghost || values == null ? null : values.get(p.id, now);
            Double s = ghost ? null : tween(g.key, p, v, dt);
            if (s != null && !s.equals(v)) moving = true;
            boolean warn = Layout.shouldFlash(g, v);
            String partnerId = HoloFace.partner(p.id);
            Double partner = partnerId == null || values == null ? null : values.get(partnerId, now);
            Param d = display(p, g.unit);
            if (d != p) {
                if (v != null) v = Units.convert(v, p.unit, d.unit);
                if (s != null) s = Units.convert(s, p.unit, d.unit);
            }
            holo.drawLayered(c, g, d, v, s, partner, warn && blink, chrome.editing, wDp, hDp, anim.get(g.key));
        }
        return moving;
    }

    /** Holo look: the bright lines of every gauge and of the stage, for the bloom layer. */
    public void drawHoloGlow(Canvas c) {
        if (holo == null || layout == null || !"gauges".equals(chrome.tab) || chrome.editing) return;
        GaugeSpec truck = null;
        for (GaugeSpec g : layout.gauges) {
            if (g.type == GaugeType.TRUCK) truck = g;
            Param p = Catalog.byId(g.paramId);
            if (p == null) continue;
            Materialize m = anim.get(g.key);
            if (m != null && !m.done(LinkService.now())) continue;
            Param d = display(p, g.unit);
            Double s = shown.get(g.key);
            if (s != null && d != p) s = Units.convert(s, p.unit, d.unit);
            holo.drawGlow(c, g, d, s, wDp, hDp);
        }
        holo.drawStageGlow(c, truck, wDp, hDp);
    }

    /** The music window, beside the look's music button, over everything else. */
    private void drawMusic(Canvas c) {
        RectF b = chrome.music && chrome.musicOpen ? theme.buttonRect("music") : null;
        if (b == null) {
            music = null;
            return;
        }
        music = MusicLayout.place(wDp, hDp, b.left, b.top, b.right, b.bottom);
        MusicPainter.draw(c, music, chrome, theme.musicDress());
    }

    /** A small thermometer in the top-left corner while the tablet runs hot; tapping it says how hot. */
    private void drawHeat(Canvas c) {
        if (heat < HeatGuard.MODERATE) {
            heatRect.setEmpty();
            return;
        }
        float x = 10, y = Math.max(52, hDp * 0.065f), s = HEAT_ICON;
        heatRect.set(x - 6, y - 6, x + s + 6, y + s + 6);
        int col = heat >= HeatGuard.SEVERE ? 0xffff463c : 0xffffb020;
        editPaint.setPathEffect(null);
        editPaint.setStyle(Paint.Style.FILL);
        editPaint.setColor(0xcc05090f);
        c.drawRoundRect(x, y, x + s, y + s, 6, 6, editPaint);
        editPaint.setStyle(Paint.Style.STROKE);
        editPaint.setStrokeWidth(1.5f);
        editPaint.setColor(col);
        c.drawRoundRect(x, y, x + s, y + s, 6, 6, editPaint);
        float cx = x + s / 2;
        c.drawRoundRect(cx - s * 0.09f, y + s * 0.16f, cx + s * 0.09f, y + s * 0.66f, s * 0.09f, s * 0.09f, editPaint);
        editPaint.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, y + s * 0.72f, s * 0.15f, editPaint);
        c.drawRect(cx - s * 0.04f, y + s * 0.34f, cx + s * 0.04f, y + s * 0.7f, editPaint);
    }

    /**
     * A finger on the music window: the close box closes it, the three buttons go to the music app, and the rest
     * of the window swallows the touch. Anywhere outside is the dash's as usual, so it can stay open.
     */
    private boolean musicTouch(MotionEvent e, float x, float y) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            musicDown = music == null ? null : music.hit(x, y);
            return musicDown != null;
        }
        if (musicDown == null) return false;
        if (a == MotionEvent.ACTION_UP) {
            String part = music == null ? null : music.hit(x, y);
            if (musicDown.equals(part)) {
                performClick();
                if ("close".equals(part)) host.onDashAction("music");
                else host.onDashAction("music:" + part);
            }
            musicDown = null;
        } else if (a == MotionEvent.ACTION_CANCEL) {
            musicDown = null;
        }
        return true;
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

    private void fireHold() {
        if (!truckDown) return;
        holdFired = true;
        host.onTruckHold();
    }

    /** Holo look, not editing: a tap on the truck spins it, a hold opens its hood. */
    private boolean truckTouch(MotionEvent e, float x, float y) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (truckRect == null || theme.hit(x, y) != null || x < truckRect.x || x > truckRect.x + truckRect.w
                        || y < truckRect.y || y > truckRect.y + truckRect.h) return false;
                truckDown = true;
                holdFired = false;
                downX = x;
                downY = y;
                postDelayed(hold, HOLD_MS);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!truckDown) return false;
                if (Math.hypot(x - downX, y - downY) > SLOP * 2) {
                    truckDown = false;
                    removeCallbacks(hold);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!truckDown) return false;
                truckDown = false;
                removeCallbacks(hold);
                if (!holdFired) {
                    performClick();
                    host.onTruckTap();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                truckDown = false;
                removeCallbacks(hold);
                return true;
            default:
                return truckDown;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (theme == null) return false;
        float x = e.getX() / density, y = e.getY() / density;
        if (musicTouch(e, x, y)) return true;
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && heatRect.contains(x, y)) {
            downAction = "heat";
            return true;
        }
        if ("heat".equals(downAction)) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                downAction = null;
                if (heatRect.contains(x, y)) {
                    performClick();
                    host.onDashAction("heat");
                }
            } else if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                downAction = null;
            }
            return true;
        }
        boolean editingGauges = chrome.editing && layout != null && "gauges".equals(chrome.tab);
        if (editingGauges && (dragKey != null || theme.hit(x, y) == null) && editTouch(e, x, y)) return true;
        if (!editingGauges && holo != null && truckTouch(e, x, y)) return true;
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
