package com.strickland.jeepdash.theme;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import com.strickland.jeepdash.LinkService;
import com.strickland.jeepdash.core.GaugeSpec;
import com.strickland.jeepdash.core.GaugeType;
import com.strickland.jeepdash.core.HoloFace;
import com.strickland.jeepdash.core.HoloFraming;
import com.strickland.jeepdash.core.Materialize;
import com.strickland.jeepdash.core.Param;
import com.strickland.jeepdash.scene.HoloState;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The Holo look (the owner's approved mockup): a dark hex-grid room, the 3D truck on a projector platform in the
 * middle, rpm and speed arcs wrapping round it and slim panels down both sides, all drawn as hologram overlays:
 * cyan glow, moving scanlines, a faint flicker and colour fringe on the panel edges, panels floating in front of
 * or behind the truck, turned in toward the viewer and sliding with the parallax. Every gauge is still a gauge:
 * moved, resized, given any reading or hidden like in the other looks. The drawing is split into the layer behind
 * the truck (HoloBackView), the layer in front (DashView) and the glow (HoloBloomView).
 */
public final class HoloTheme implements Theme {
    static final int CYAN = 0xff46e8ff, CYAN_D = 0xff1e7896, WHITE = 0xffe8faff, AMBER = 0xffffb020, RED = 0xffff463c;
    private static final int BG_TOP = 0xff040a12, BG_MID = 0xff0a1826, BG_BOTTOM = 0xff081018;

    public final HoloState st;
    private final Typeface orb, raj, rajB;
    private final Map<String, RectF> buttons = new LinkedHashMap<>();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();
    private final Matrix mx = new Matrix(), scanM = new Matrix();
    private final float[] src = new float[8];
    private Bitmap bg, scanBmp;
    private BitmapShader scan;
    private float bgW, bgH, bgDensity, scanGap;
    private long now;
    private float fade = 1;
    private String lastNotice = "";
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.US);

    public HoloTheme(Context c, HoloState st) {
        this.st = st;
        orb = font(c, "fonts/Orbitron.ttf", Typeface.DEFAULT);
        raj = font(c, "fonts/Rajdhani-SemiBold.ttf", Typeface.DEFAULT);
        rajB = font(c, "fonts/Rajdhani-Bold.ttf", Typeface.DEFAULT_BOLD);
    }

    private static Typeface font(Context c, String path, Typeface fallback) {
        try {
            return Typeface.createFromAsset(c.getAssets(), path);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    @Override
    public String id() { return "holo"; }

    /** The time this frame is drawn at (one clock for every layer). */
    public void frameTime(long nowMs) { now = nowMs; }

    // ---------------- colour with the flicker and fade applied ----------------

    private int ink(int color, int alpha) {
        int a = Math.round(Math.max(0, Math.min(255, alpha * fade)));
        return (color & 0x00ffffff) | (a << 24);
    }

    private Paint fill(int color, int alpha) {
        p.reset();
        p.setAntiAlias(true);
        p.setStyle(Paint.Style.FILL);
        p.setColor(ink(color, alpha));
        return p;
    }

    private Paint stroke(int color, int alpha, float width) {
        p.reset();
        p.setAntiAlias(true);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        p.setColor(ink(color, alpha));
        return p;
    }

    private void text(Canvas c, String s, float x, float baseline, float size, Typeface tf, int color, int alpha, Paint.Align a) {
        p.reset();
        p.setAntiAlias(true);
        p.setTypeface(tf);
        p.setTextSize(size);
        p.setTextAlign(a);
        p.setColor(ink(color, alpha));
        c.drawText(s, x, baseline, p);
    }

    private float fit(String s, float size, float maxW, Typeface tf) {
        p.reset();
        p.setTypeface(tf);
        p.setTextSize(size);
        float w = p.measureText(s);
        return w <= maxW || w <= 0 ? size : Math.max(5, size * maxW / w);
    }

    /** Baseline for text whose top is at y. */
    private float top(float y, float size, Typeface tf) {
        p.reset();
        p.setTypeface(tf);
        p.setTextSize(size);
        return y - p.getFontMetrics().ascent * 0.92f;
    }

    // ---------------- background and the projector stage ----------------

    @Override
    public void drawBackground(Canvas c, float w, float h) {
        if (st.trail) return;           // the night trail: its own 3D sky, ground and truck, no room or platform
        float d = c.getWidth() > 0 && w > 0 ? c.getWidth() / w : 1;
        if (bg == null || bgW != w || bgH != h || bgDensity != d) makeBackground(w, h, d);
        r.set(0, 0, w, h);
        p.reset();
        p.setFilterBitmap(true);
        c.drawBitmap(bg, null, r, p);
    }

    /** Gradient, hex grid and vignette: drawn once per screen size, at half the screen's pixels. */
    private void makeBackground(float w, float h, float density) {
        bgW = w;
        bgH = h;
        bgDensity = density;
        int bw = Math.max(1, Math.round(w * density / 2)), bh = Math.max(1, Math.round(h * density / 2));
        if (bg != null) bg.recycle();
        bg = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bg);
        c.scale(bw / w, bh / h);
        Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
        q.setShader(new LinearGradient(0, 0, 0, h, new int[]{BG_TOP, BG_MID, BG_BOTTOM}, new float[]{0, 0.45f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, q);
        q.setShader(null);
        q.setStyle(Paint.Style.STROKE);
        q.setStrokeWidth(0.8f);
        float rr = 34 * w / 1600f * 0.8f / 0.8f, cx = w / 2, cy = h * 0.455f;
        Path hex = new Path();
        for (int j = -1; j < h / (rr * 1.5f) + 2; j++) {
            for (int i = -1; i < w / (rr * 1.732f) + 2; i++) {
                float x = i * rr * 1.732f + (j % 2 == 0 ? 0 : rr * 0.866f), y = j * rr * 1.5f;
                float dist = (float) Math.hypot(x - cx, y - cy) / (w * 0.5625f);
                int a = (int) Math.max(0, 26 * (1 - dist));
                if (a <= 2) continue;
                hex.reset();
                for (int k = 0; k < 6; k++) {
                    double t = Math.toRadians(60 * k + 30);
                    float px = x + rr * 0.95f * (float) Math.cos(t), py = y + rr * 0.95f * (float) Math.sin(t);
                    if (k == 0) hex.moveTo(px, py);
                    else hex.lineTo(px, py);
                }
                hex.close();
                q.setColor((a << 24) | 0x3cc8f0);
                c.drawPath(hex, q);
            }
        }
        q.setStyle(Paint.Style.FILL);
        q.setShader(new RadialGradient(w / 2, h / 2, (float) Math.hypot(w, h) / 2, new int[]{0, 0, 0x8c000000},
                new float[]{0, 0.55f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, q);
    }

    /** Where a ground point of the 3D scene lands on screen (dp), through the truck view's framing. */
    private float[] ground(GaugeSpec t, double x, double z) {
        double[] f = framing(t).project(x, 0, z);
        return new float[]{t.x + (float) f[0] * t.w, t.y + (float) f[1] * t.h};
    }

    private HoloFraming framing(GaugeSpec t) {
        HoloFraming f = st.framing;
        double aspect = t.w / Math.max(1f, t.h);
        if (f == null || Math.abs(f.aspect - aspect) > 0.01)
            f = HoloFraming.fit(aspect, 4.0, st.truckWidth, st.truckHeight, st.parallaxYaw, st.parallaxPitch);
        return f;
    }

    private float[] shift(float depth, float x, float y, float w, float h) {
        GaugeSpec g = new GaugeSpec("", "", GaugeType.HEX, x, y, 0, 0);
        g.depth = Math.round(depth);
        float[] q = HoloFace.project(g, w, h, 0, st.parallaxYaw, st.parallaxPitch);
        return new float[]{q[0] - x, q[1] - y};
    }

    /** Ring turn, degrees, for the slow rings round the truck. */
    private float ringTurn(float speed) { return (float) ((now / 60000.0 * 360 * speed) % 360); }

    /**
     * The projector stage round the truck view's box t. Behind (back): the arc-reactor rings, the emitter disc and
     * its light cone, the platform rings and the far halves of the turning rings. In front: their near halves and
     * the scanlines over the truck.
     */
    public void drawStage(Canvas c, GaugeSpec t, float w, float h, boolean back) {
        if (st.trail) return;           // the night trail: its own 3D sky, ground and truck, no room or platform
        if (t == null) return;
        fade = 1;
        float cx = t.x + t.w / 2, cy = t.y + t.h / 2, k = t.w / 660f;
        if (back) {
            float[] s = shift(-1, cx, cy, w, h);
            float x = cx + s[0], y = cy + s[1];
            float turn = ringTurn(st.fx.ringSpeed * 0.5f);
            circle(c, x, y, 300 * k, 2 * k, 120);
            circle(c, x, y, 286 * k, 1 * k, 70);
            r.set(x - 318 * k, y - 318 * k, x + 318 * k, y + 318 * k);
            for (int i = 0; i < 24; i++) {
                stroke(CYAN, i % 3 == 0 ? 160 : 90, 6 * k);
                c.drawArc(r, i * 15 + 2 + turn, 9, false, p);
            }
            stroke(CYAN, 110, Math.max(0.6f, k));
            for (int i = 0; i < 120; i++) {
                double a = Math.toRadians(i * 3 - turn * 0.5);
                float r0 = 262 * k, r1 = (i % 5 == 0 ? 276 : 270) * k;
                p.setColor(ink(CYAN, i % 5 == 0 ? 170 : 110));
                c.drawLine(x + r0 * (float) Math.cos(a), y + r0 * (float) Math.sin(a), x + r1 * (float) Math.cos(a), y + r1 * (float) Math.sin(a), p);
            }
            circle(c, x, y, 240 * k, k, 50);
            projector(c, t);
            platform(c, t);
        }
        turningRings(c, t, back);
        if (!back && st.fx.scanlineGap > 0) scanlines(c, t.x, t.y, t.x + t.w, t.y + t.h, 10);
    }

    private void circle(Canvas c, float x, float y, float radius, float width, int alpha) {
        stroke(CYAN, alpha, width);
        c.drawCircle(x, y, radius, p);
    }

    /** An emitter disc on the ground under the truck and a faint cone of light up to it. */
    private void projector(Canvas c, GaugeSpec t) {
        HoloFraming f = framing(t);
        double[] e = f.ring(1.2);
        float ex = t.x + (float) e[0] * t.w, ey = t.y + (float) e[1] * t.h, erx = (float) e[2] * t.w, ery = (float) e[3] * t.h;
        double[] top = f.project(0, st.truckHeight * 0.6, 0);
        float ty = t.y + (float) top[1] * t.h;
        path.reset();
        path.moveTo(ex - erx, ey);
        path.lineTo(ex - erx * 0.55f, ty);
        path.lineTo(ex + erx * 0.55f, ty);
        path.lineTo(ex + erx, ey);
        path.close();
        p.reset();
        p.setAntiAlias(true);
        p.setShader(new LinearGradient(0, ey, 0, ty, ink(CYAN, 46), ink(CYAN, 0), Shader.TileMode.CLAMP));
        c.drawPath(path, p);
        p.reset();
        p.setAntiAlias(true);
        p.setShader(new RadialGradient(ex, ey, Math.max(1, erx), new int[]{ink(WHITE, 120), ink(CYAN, 70), ink(CYAN, 0)},
                new float[]{0, 0.45f, 1}, Shader.TileMode.CLAMP));
        c.save();
        c.scale(1, Math.max(0.05f, ery / Math.max(1, erx)), ex, ey);
        c.drawCircle(ex, ey, erx, p);
        c.restore();
        r.set(ex - erx, ey - ery, ex + erx, ey + ery);
        stroke(CYAN, 170, 1.5f);
        c.drawOval(r, p);
    }

    private void platform(Canvas c, GaugeSpec t) {
        HoloFraming f = framing(t);
        double[] radii = {2.9, 2.4, 1.8, 1.05};
        int[] alpha = {60, 90, 120, 90};
        for (int i = 0; i < radii.length; i++) {
            double[] g = f.ring(radii[i]);
            r.set(t.x + (float) (g[0] - g[2]) * t.w, t.y + (float) (g[1] - g[3]) * t.h,
                    t.x + (float) (g[0] + g[2]) * t.w, t.y + (float) (g[1] + g[3]) * t.h);
            stroke(CYAN, alpha[i], 1.6f);
            c.drawOval(r, p);
        }
        stroke(CYAN, 40, 0.8f);
        for (int i = 0; i < 36; i++) {
            double a = Math.toRadians(i * 10);
            float[] a0 = ground(t, 1.8 * Math.cos(a), 1.8 * Math.sin(a)), a1 = ground(t, 2.9 * Math.cos(a), 2.9 * Math.sin(a));
            c.drawLine(a0[0], a0[1], a1[0], a1[1], p);
        }
    }

    /** Two segmented rings turning slowly round the truck at ground level; near halves pass in front of it. */
    private void turningRings(Canvas c, GaugeSpec t, boolean back) {
        if (st.fx.ringSpeed <= 0 && !back) return;
        float turn = ringTurn(st.fx.ringSpeed);
        ring(c, t, 2.65, 12, 18, turn, back, 150, 2.2f);
        ring(c, t, 3.25, 6, 34, -turn * 0.6f, back, 90, 1.4f);
    }

    private void ring(Canvas c, GaugeSpec t, double radius, int segments, float segDeg, float turn, boolean back,
                      int alpha, float width) {
        stroke(CYAN, alpha, width);
        p.setStrokeCap(Paint.Cap.ROUND);
        float step = 360f / segments;
        for (int s = 0; s < segments; s++) {
            path.reset();
            boolean drawing = false;
            for (int k = 0; k <= 8; k++) {
                double a = Math.toRadians(turn + s * step + segDeg * k / 8);
                double x = radius * Math.cos(a), z = radius * Math.sin(a);
                boolean near = z > 0;
                if (near == back) {
                    drawing = false;
                    continue;
                }
                float[] g = ground(t, x, z);
                if (!drawing) path.moveTo(g[0], g[1]);
                else path.lineTo(g[0], g[1]);
                drawing = true;
            }
            c.drawPath(path, p);
        }
    }

    // ---------------- scanlines ----------------

    private void scanlines(Canvas c, float l, float tp, float rt, float b, int alpha) {
        float gap = st.fx.scanlineGap;
        if (gap <= 0) return;
        if (scan == null || scanGap != gap) {
            scanGap = gap;
            int n = Math.max(2, Math.round(gap * 2));
            scanBmp = Bitmap.createBitmap(1, n, Bitmap.Config.ARGB_8888);
            scanBmp.setPixel(0, 0, 0xff000000);
            scan = new BitmapShader(scanBmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        }
        scanM.reset();
        scanM.setScale(1, 0.5f);
        scanM.postTranslate(0, (now / 40f) % gap);
        scan.setLocalMatrix(scanM);
        p.reset();
        p.setShader(scan);
        p.setAlpha(Math.round(alpha * 2.2f * fade));
        c.drawRect(l, tp, rt, b, p);
    }

    // ---------------- gauges as hologram layers ----------------

    /**
     * One gauge on its layer: floated to its depth, turned toward the viewer and slid with the parallax (flat
     * while editing), materializing or dematerializing, with flicker, scanlines and colour fringe as the effects
     * allow. partner is the panel's second reading, if it has one.
     */
    public void drawLayered(Canvas c, GaugeSpec g, Param pr, Double value, Double shown, Double partner, boolean flash,
                            boolean editing, float w, float h, Materialize m) {
        if (g.type == GaugeType.TRUCK) {
            drawGauge(c, g, pr, value, shown, flash, editing);
            return;
        }
        c.save();
        if (!editing) {
            src[0] = g.x; src[1] = g.y; src[2] = g.x + g.w; src[3] = g.y;
            src[4] = g.x + g.w; src[5] = g.y + g.h; src[6] = g.x; src[7] = g.y + g.h;
            float[] q = HoloFace.project(g, w, h, 1, st.parallaxYaw, st.parallaxPitch);
            mx.setPolyToPoly(src, 0, q, 0, 4);
            c.concat(mx);
        }
        double height = m == null ? 1 : m.height(now), reveal = m == null ? 1 : m.reveal(now);
        float cy = g.y + g.h / 2;
        c.scale(1, (float) height, g.x + g.w / 2, cy);
        fade = flicker(g.key);
        boolean panel = g.type == GaugeType.DIGITAL;
        if (reveal < 1) {
            if (panel) frame(c, g, flash ? RED : colour(pr), editing);
            c.save();
            c.clipRect(g.x - 6, g.y - 6, g.x + g.w + 6, g.y + g.h * (float) reveal);
            content(c, g, pr, value, shown, partner, flash, editing);
            c.restore();
            float wy = g.y + g.h * (float) reveal;
            stroke(CYAN, 90, 5);
            c.drawLine(g.x, wy, g.x + g.w, wy, p);
            stroke(WHITE, 230, 1.4f);
            c.drawLine(g.x, wy, g.x + g.w, wy, p);
        } else {
            content(c, g, pr, value, shown, partner, flash, editing);
        }
        if (g.type != GaugeType.ARC_LEFT && g.type != GaugeType.ARC_RIGHT) scanlines(c, g.x, g.y, g.x + g.w, g.y + g.h, 16);
        fade = 1;
        c.restore();
    }

    /** A faint unsteadiness: a slow shimmer and, now and then, a brief dip. */
    private float flicker(String key) {
        if (!st.fx.flicker) return 1;
        int hsh = key.hashCode();
        double t = now / 1000.0;
        float f = 1 - 0.05f * (float) (0.5 + 0.5 * Math.sin(t * 7.3 + (hsh & 63)));
        if (((now / 90) + (hsh & 0xff)) % 233 == 0) f -= 0.3f;
        return f;
    }

    private int colour(Param pr) { return "normal".equals(pr.id) ? AMBER : CYAN; }

    /** The glow pass: just the bright lines, for the bloom layer to blur. */
    public void drawGlow(Canvas c, GaugeSpec g, Param pr, Double shown, float w, float h) {
        if (g.type == GaugeType.TRUCK) return;
        c.save();
        float[] q = HoloFace.project(g, w, h, 1, st.parallaxYaw, st.parallaxPitch);
        src[0] = g.x; src[1] = g.y; src[2] = g.x + g.w; src[3] = g.y;
        src[4] = g.x + g.w; src[5] = g.y + g.h; src[6] = g.x; src[7] = g.y + g.h;
        mx.setPolyToPoly(src, 0, q, 0, 4);
        c.concat(mx);
        fade = 1;
        if (g.type == GaugeType.ARC_LEFT || g.type == GaugeType.ARC_RIGHT) {
            arcSegments(c, g, pr, shown, true);
        } else if (g.type == GaugeType.HEX) {
            hexPath(g);
            c.drawPath(path, stroke(CYAN, 200, 3));
        } else {
            r.set(g.x, g.y, g.x + g.w, g.y + g.h);
            c.drawRect(r, stroke(colour(pr), 150, 2));
        }
        c.restore();
    }

    /** The stage's bright rings, for the bloom layer. */
    public void drawStageGlow(Canvas c, GaugeSpec t, float w, float h) {
        if (st.trail) return;           // the night trail: its own 3D sky, ground and truck, no room or platform
        if (t == null) return;
        fade = 1;
        float cx = t.x + t.w / 2, cy = t.y + t.h / 2, k = t.w / 660f;
        float[] s = shift(-1, cx, cy, w, h);
        circle(c, cx + s[0], cy + s[1], 300 * k, 3 * k, 150);
        projector(c, t);
    }

    // ---------------- the gauges themselves (flat) ----------------

    @Override
    public void drawGauge(Canvas c, GaugeSpec g, Param pr, Double value, Double shown, boolean flashOn, boolean editing) {
        content(c, g, pr, value, shown, null, flashOn, editing);
    }

    private void content(Canvas c, GaugeSpec g, Param pr, Double value, Double shown, Double partner, boolean flash, boolean editing) {
        switch (g.type) {
            case ARC_LEFT: case ARC_RIGHT: arc(c, g, pr, value, shown, flash); break;
            case HEX: hex(c, g, pr, value, shown, flash); break;
            case DIAL: ring(c, g, pr, value, shown, flash); break;
            case BAR: readout(c, g, pr, value, shown, flash); break;
            case TRUCK: truck(c, g, editing); break;
            default: panel(c, g, pr, value, shown, partner, flash, editing); break;
        }
    }

    /** The number shown: the smoothed reading, so figures glide instead of jumping. */
    private static String number(Param pr, Double value, Double shown) {
        Double v = shown != null ? shown : value;
        if (v == null) return Art.DASH;
        if (pr.id.equals("rpm") && pr.unit.equals("rpm")) return String.format(Locale.US, "%,d", Math.round(v / 10) * 10);
        if (pr.unit.startsWith("°F") || pr.unit.equals("°C") || pr.unit.equals("mph") || pr.unit.equals("km/h")
                || pr.unit.equals("rpm") || Art.whole(pr)) return String.valueOf(Math.round(v));
        return Art.format(pr, v);
    }

    private static String signed(double v) { return (v > 0.5 ? "+" : "") + Math.round(v); }

    private void frame(Canvas c, GaugeSpec g, int col, boolean editing) {
        float u = g.h / 62f;
        r.set(g.x, g.y, g.x + g.w, g.y + g.h);
        c.drawRect(r, fill(0x04121c, 70));
        if (st.fx.idleMotion) c.drawRect(r, stroke(col, 26, 5 * u));             // faint edge glow
        if (st.fx.fringe && !editing) {
            r.offset(-0.9f, 0);
            c.drawRect(r, stroke(0xff4060, 46, u));
            r.offset(1.8f, 0);
            c.drawRect(r, stroke(0x4080ff, 46, u));
            r.offset(-0.9f, 0);
        }
        c.drawRect(r, stroke(col, 120, Math.max(0.8f, u)));
        c.drawLine(g.x, g.y, g.x + 18 * u, g.y, stroke(col, 255, 3 * u));
    }

    /** A slim panel: title, reading, unit, and a thin bar of where it sits on its scale. */
    private void panel(Canvas c, GaugeSpec g, Param pr, Double value, Double shown, Double partner, boolean flash, boolean editing) {
        int col = flash ? RED : colour(pr);
        frame(c, g, col, editing);
        float u = g.h / 62f, pad = 10 * u;
        text(c, HoloFace.title(pr.id, pr.name), g.x + pad, top(g.y + 7 * u, 15 * u, raj), fit(HoloFace.title(pr.id, pr.name), 15 * u, g.w - 2 * pad, raj),
                raj, col, 200, Paint.Align.LEFT);
        String v, unit = pr.unit;
        boolean bar = true;
        Double s = shown != null ? shown : value;
        switch (pr.id) {
            case "ltft": case "stft":
                v = s == null ? Art.DASH : signed(s) + " / " + (partner == null ? Art.DASH : signed(partner));
                bar = false;
                break;
            case "spark":
                v = s == null ? Art.DASH : Math.round(s) + "° / " + (partner == null ? Art.DASH : Math.round(partner) + "°");
                unit = "";
                break;
            case "roll":
                v = s == null ? Art.DASH : HoloFace.lean(s);
                unit = partner == null ? "" : String.format(Locale.US, "pitch %.1f°", partner);
                break;
            case "normal":
                int n = s == null ? 0 : (int) Math.round(s);
                boolean alert = !lastNotice.isEmpty();
                v = alert ? "ALERT" : n >= 5 ? "learned" : "drive " + n + "/5";
                unit = alert ? "tap above" : n >= 5 ? "watching" : "learning";
                break;
            default:
                v = number(pr, value, shown);
        }
        float unitSize = 15 * u;
        float unitW = unit.isEmpty() ? 0 : Art.width(unit, unitSize, raj) + 6 * u;
        float size = fit(v, 24 * u, g.w - 2 * pad - unitW, orb);
        text(c, v, g.x + pad, top(g.y + 25 * u, size, orb), size, orb, flash ? RED : WHITE, 255, Paint.Align.LEFT);
        if (!unit.isEmpty()) text(c, unit, g.x + g.w - pad, top(g.y + 36 * u, unitSize, raj), unitSize, raj, col, 190, Paint.Align.RIGHT);
        if (bar && s != null) {
            float l = g.x + pad, rt = g.x + g.w - pad, y = g.y + 55 * u;
            c.drawRect(l, y, rt, y + 2 * u, fill(col, 50));
            float zero = pr.min < 0 && pr.max > 0 ? Art.frac(pr, 0) : 0, f = Art.frac(pr, s);
            c.drawRect(l + (rt - l) * Math.min(zero, f), y, l + (rt - l) * Math.max(zero, f), y + 2 * u, fill(col, 230));
        }
    }

    /** A big readout: caption over a large figure, unit under it. */
    private void readout(Canvas c, GaugeSpec g, Param pr, Double value, Double shown, boolean flash) {
        float cx = g.x + g.w / 2, u = Math.min(g.h / 120f, g.w / 220f);
        String title = HoloFace.title(pr.id, pr.name), v = number(pr, value, shown);
        text(c, title, cx, top(g.y + g.h * 0.08f, 18 * u, raj), fit(title, 18 * u, g.w, raj), raj, CYAN, 210, Paint.Align.CENTER);
        float size = fit(v, 54 * u, g.w * 0.95f, orb);
        text(c, v, cx, top(g.y + g.h * 0.3f, size, orb), size, orb, flash ? RED : WHITE, 255, Paint.Align.CENTER);
        if (!pr.unit.equals("rpm")) text(c, pr.unit.toUpperCase(Locale.US), cx, top(g.y + g.h * 0.78f, 16 * u, raj), 16 * u, raj, CYAN, 200, Paint.Align.CENTER);
    }

    /** A small arc-reactor ring with the reading in the middle. */
    private void ring(Canvas c, GaugeSpec g, Param pr, Double value, Double shown, boolean flash) {
        float rad = Math.min(g.w, g.h) / 2 * 0.86f, cx = g.x + g.w / 2, cy = g.y + g.h / 2;
        r.set(cx - rad, cy - rad, cx + rad, cy + rad);
        float turn = st.fx.idleMotion ? ringTurn(st.fx.ringSpeed * 1.5f) : 0;
        for (int i = 0; i < 24; i++) c.drawArc(r, 135 + i * 11.25f + 0.8f, 9.6f, false, stroke(CYAN, 38, rad * 0.09f));
        Double s = shown != null ? shown : value;
        if (s != null) {
            float f = Art.frac(pr, s);
            int lit = Math.round(f * 24);
            for (int i = 0; i < lit; i++) c.drawArc(r, 135 + i * 11.25f + 0.8f, 9.6f, false, stroke(flash ? RED : CYAN, 230, rad * 0.09f));
        }
        float r2 = rad * 1.1f;
        r.set(cx - r2, cy - r2, cx + r2, cy + r2);
        for (int i = 0; i < 6; i++) c.drawArc(r, turn + i * 60, 34, false, stroke(CYAN, 90, 1.2f));
        String v = number(pr, value, shown);
        float size = fit(v, rad * 0.42f, rad * 1.4f, orb);
        text(c, v, cx, cy + size * 0.36f, size, orb, flash ? RED : WHITE, 255, Paint.Align.CENTER);
        String t = HoloFace.title(pr.id, pr.name);
        text(c, t, cx, cy + rad * 0.55f, fit(t, rad * 0.16f, rad * 1.5f, raj), raj, CYAN, 200, Paint.Align.CENTER);
    }

    private void hexPath(GaugeSpec g) {
        float rad = Math.min(g.w, g.h * 0.76f) / 2, cx = g.x + g.w / 2, cy = g.y + rad + g.h * 0.02f;
        path.reset();
        for (int k = 0; k < 6; k++) {
            double a = Math.toRadians(60 * k + 30);
            float x = cx + rad * (float) Math.cos(a), y = cy + rad * (float) Math.sin(a);
            if (k == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        path.close();
    }

    /** The gear hexagon: a figure inside, the caption under it. */
    private void hex(Canvas c, GaugeSpec g, Param pr, Double value, Double shown, boolean flash) {
        float rad = Math.min(g.w, g.h * 0.76f) / 2, cx = g.x + g.w / 2, cy = g.y + rad + g.h * 0.02f;
        hexPath(g);
        c.drawPath(path, fill(0x04121c, 120));
        if (st.fx.idleMotion) c.drawPath(path, stroke(CYAN, 40, rad * 0.16f));
        c.drawPath(path, stroke(flash ? RED : CYAN, 230, Math.max(1.5f, rad * 0.06f)));
        String v;
        if (pr.id.equals("gear")) {
            Double s = value;
            v = s == null ? Art.DASH : Math.round(s) == 0 ? "N" : String.valueOf(Math.round(s));
        } else {
            v = number(pr, value, shown);
        }
        float size = fit(v, rad * 1.08f, rad * 1.5f, orb);
        text(c, v, cx, cy + size * 0.37f, size, orb, flash ? RED : WHITE, 255, Paint.Align.CENTER);
        String t = pr.id.equals("gear") ? "GEAR" : HoloFace.title(pr.id, pr.name);
        float ts = Math.min(rad * 0.33f, g.h * 0.13f);
        text(c, t, cx, top(cy + rad + g.h * 0.04f, ts, raj), fit(t, ts, g.w, raj), raj, CYAN, 210, Paint.Align.CENTER);
    }

    // ---------------- the wrap-around arcs ----------------

    private void arcSegments(Canvas c, GaugeSpec g, Param pr, Double shown, boolean glowOnly) {
        float R = HoloFace.arcRadius(g), u = R / 372f;
        float[] ctr = HoloFace.arcCentre(g);
        boolean right = g.type == GaugeType.ARC_RIGHT;
        float start = right ? -55 : 125, span = 110;
        int n = 44;
        float frac = shown == null ? 0 : Art.frac(pr, shown);
        Double red = redFrom(g, pr);
        float redAt = red == null ? 2 : Art.frac(pr, red);
        // the sweep: a brighter band runs up the lit part now and then
        float sweep = -1;
        if (st.fx.sweep && !glowOnly) {
            float ph = (now % 3600) / 900f;
            if (ph < 1) sweep = ph * frac;
        }
        r.set(ctr[0] - R, ctr[1] - R, ctr[0] + R, ctr[1] + R);
        for (int k = 0; k < n; k++) {
            float a0 = start + span * k / n + 0.6f, a1 = start + span * (k + 1) / n - 0.6f;
            float pos = k / (float) (n - 1), fromBottom = right ? 1 - pos : pos;
            boolean lit = fromBottom <= frac && shown != null;
            if (glowOnly && !lit) continue;
            int col = fromBottom >= redAt ? RED : CYAN;
            int alpha = lit ? 235 : 38;
            if (sweep >= 0 && lit && Math.abs(fromBottom - sweep) < 0.05f) col = WHITE;
            c.drawArc(r, a0, a1 - a0, false, stroke(col, glowOnly ? 170 : alpha, 22 * u));
        }
        if (glowOnly) return;
        float ro = R + 20 * u, ri = R - 18 * u;
        r.set(ctr[0] - ro, ctr[1] - ro, ctr[0] + ro, ctr[1] + ro);
        c.drawArc(r, start, span, false, stroke(CYAN, 90, 2 * u));
        r.set(ctr[0] - ri, ctr[1] - ri, ctr[0] + ri, ctr[1] + ri);
        c.drawArc(r, start, span, false, stroke(CYAN, 60, Math.max(0.6f, u)));
    }

    private static Double redFrom(GaugeSpec g, Param pr) {
        if (pr.redLine != null) return pr.redLine;              // the gauge's own red zone
        if (g.warnOn && g.warnAbove) return g.warnLimit;
        if (pr.id.equals("rpm")) return pr.max * 0.867;
        return null;
    }

    private void arc(Canvas c, GaugeSpec g, Param pr, Double value, Double shown, boolean flash) {
        arcSegments(c, g, pr, shown, false);
        float R = HoloFace.arcRadius(g), u = R / 372f;
        float[] ctr = HoloFace.arcCentre(g);
        boolean right = g.type == GaugeType.ARC_RIGHT, rpm = pr.id.equals("rpm") && pr.unit.equals("rpm");
        // numbers round the outside
        double step = rpm ? 1000 : pr.id.equals("speed") || pr.unit.equals("mph") ? 20 : Art.scale(pr).labelEvery;
        if (step <= 0) step = (pr.max - pr.min) / 5;
        float ls = (rpm ? 20 : 18) * u;
        for (double v = Math.ceil(pr.min / step) * step; v <= pr.max + 1e-9; v += step) {
            float f = Art.frac(pr, v);
            double a = Math.toRadians(right ? 55 - 110 * f : 125 + 110 * f);
            float lx = ctr[0] + (R + 44 * u) * (float) Math.cos(a), ly = ctr[1] + (R + 44 * u) * (float) Math.sin(a);
            String t = rpm ? String.valueOf(Math.round(v / 1000)) : Art.compact(v);
            text(c, t, lx, ly + ls * 0.36f, ls, orb, WHITE, 220, Paint.Align.CENTER);
        }
        // the readout beside the arc
        float rx = right ? ctr[0] + 1.33f * R : ctr[0] - 1.33f * R;
        String cap = rpm ? "RPM  x1000" : right && pr.id.equals("speed") ? "SPEED" : HoloFace.title(pr.id, pr.name);
        text(c, cap, rx, top(ctr[1] - 0.277f * R, 18 * u, raj), fit(cap, 18 * u, 0.6f * R, raj), raj, CYAN, 210, Paint.Align.CENTER);
        String v = number(pr, value, shown);
        float size = fit(v, (rpm ? 50 : 64) * u, 0.62f * R, orb);
        text(c, v, rx, top(ctr[1] - 0.207f * R, size, orb), size, orb, flash ? RED : WHITE, 255, Paint.Align.CENTER);
        if (!rpm) text(c, pr.unit.toUpperCase(Locale.US), rx, top(ctr[1] - 0.207f * R + size * 1.18f, 18 * u, raj), 18 * u, raj, CYAN, 210, Paint.Align.CENTER);
    }

    // ---------------- the truck view's box ----------------

    private void truck(Canvas c, GaugeSpec g, boolean editing) {
        float cx = g.x + g.w / 2;
        if (!st.sceneOk) {
            String m = st.sceneNote.isEmpty() ? "3D view unavailable on this device" : st.sceneNote;
            text(c, m, cx, g.y + g.h / 2, fit(m, 16, g.w * 0.9f, raj), raj, CYAN, 200, Paint.Align.CENTER);
        }
        if (editing) text(c, "3D TRUCK", cx, top(g.y + 8, 16, rajB), 16, rajB, CYAN, 230, Paint.Align.CENTER);
    }

    @Override
    public void drawPanel(Canvas c, float w, float h, String title, String message) {
        text(c, title.toUpperCase(Locale.US), w / 2, h / 2 - 12, 24, rajB, CYAN, 230, Paint.Align.CENTER);
        text(c, message, w / 2, h / 2 + 20, fit(message, 18, w - 60, raj), raj, WHITE, 200, Paint.Align.CENTER);
    }

    // ---------------- header and footer ----------------

    private float button(Canvas c, String action, float right, float cy, float size, String label, boolean strong) {
        p.reset();
        p.setTypeface(rajB);
        p.setTextSize(size);
        p.setLetterSpacing(0.12f);
        float w = p.measureText(label) + size * 1.6f, l = right - w, hgt = size * 1.9f;
        r.set(l, cy - hgt / 2, right, cy + hgt / 2);
        c.drawRect(r, fill(0x04121c, 150));
        c.drawRect(r, stroke(CYAN, strong ? 230 : 110, 1.2f));
        c.drawLine(l, r.top, l + size, r.top, stroke(CYAN, 255, 2.2f));
        p.reset();
        p.setAntiAlias(true);
        p.setTypeface(rajB);
        p.setTextSize(size);
        p.setLetterSpacing(0.12f);
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(ink(strong ? WHITE : CYAN, 230));
        c.drawText(label, l + w / 2, cy + size * 0.35f, p);
        buttons.put(action, new RectF(l - 4, cy - hgt / 2 - 8, right + 4, cy + hgt / 2 + 8));
        return l;
    }

    @Override
    public void drawChrome(Canvas c, float w, float h, Chrome s) {
        buttons.clear();
        fade = 1;
        lastNotice = s.notice;
        float m = h / 1000f, mx = w / 1600f;
        // header: clock, title, link
        text(c, clock.format(new Date()), 28 * mx, top(30 * m, 22 * m, rajB), 22 * m, rajB, WHITE, 230, Paint.Align.LEFT);
        boolean good = s.status == LinkService.Status.LIVE && s.detail.isEmpty();
        String head;
        int headCol = CYAN;
        if (!s.detail.isEmpty()) {
            head = s.detail.toUpperCase(Locale.US);
            headCol = AMBER;
            buttons.put("status2", new RectF(w * 0.3f, 0, w * 0.7f, 70 * m));
        } else if (!s.notice.isEmpty()) {
            head = s.notice;
            headCol = AMBER;
            buttons.put("notice", new RectF(w * 0.25f, 0, w * 0.75f, 70 * m));
        } else {
            head = good ? "ZJ 5.9  //  ALL SYSTEMS NOMINAL" : "ZJ 5.9  //  " + JeepTheme.linkText(s).replace(" ● ", " ");
        }
        float hs = fit(head, 20 * m, 600 * mx, rajB);
        text(c, head, w / 2, top(34 * m, hs, rajB), hs, rajB, headCol, 230, Paint.Align.CENTER);
        c.drawLine(w / 2 - 240 * mx, 64 * m, w / 2 + 240 * mx, 64 * m, stroke(CYAN, 120, 1));
        String link = good ? "LINK  •  LIVE" : "LINK  •  " + JeepTheme.linkText(s).replace(" ● ", " ");
        text(c, link, w - 28 * mx, top(30 * m, 18 * m, raj), 18 * m, raj, good ? CYAN : AMBER, 220, Paint.Align.RIGHT);
        buttons.put("status", new RectF(w - 260 * mx, 0, w, 80 * m));

        // footer: music, hints, buttons
        float fy = 930 * m, cy = (fy + h) / 2;
        c.drawLine(28 * mx, fy, w - 28 * mx, fy, stroke(CYAN, 70, 1));
        float bs = Math.max(11, 16 * m), x = w - 28 * mx;
        if (s.editing) {
            x = button(c, "done", x, cy, bs, "DONE", true) - 10;
            x = button(c, "snap", x, cy, bs, s.snap ? "SNAP ON" : "SNAP OFF", false) - 10;
            x = button(c, "add", x, cy, bs, "+ GAUGE", false) - 10;
        } else {
            if (s.nextLook != null) x = button(c, "switch", x, cy, bs, s.nextLook.toUpperCase(Locale.US), false) - 10;
            x = button(c, "menu", x, cy, bs, "SETUP", false) - 10;
            x = button(c, "logs", x, cy, bs, "LOGS", false) - 10;
            if (s.canEdit) x = button(c, "edit", x, cy, bs, "EDIT", false) - 10;
        }
        String hint = st.canOpen ? "TAP TRUCK: SPIN   •   HOLD: OPEN HOOD" : "TAP TRUCK: SPIN";
        if (s.editing) hint = "DRAG TO MOVE   •   CORNER TO RESIZE   •   TAP FOR SETTINGS";
        float hintSize = fit(hint, 16 * m, Math.max(40, x - w * 0.42f), raj);
        text(c, hint, x - 16, cy + hintSize * 0.35f, hintSize, raj, CYAN, 150, Paint.Align.RIGHT);
        if (s.music) {
            String t = s.musicTitle.isEmpty() ? "MUSIC" : (s.musicTitle + (s.musicArtist.isEmpty() ? "" : "  /  " + s.musicArtist)).toUpperCase(Locale.US);
            String line = "♪  " + t;
            float ms = fit(line, 18 * m, w * 0.38f, raj);
            text(c, line, 40 * mx, cy + ms * 0.35f, ms, raj, CYAN, s.musicOpen ? 255 : 210, Paint.Align.LEFT);
            buttons.put("music", new RectF(28 * mx, fy, 40 * mx + w * 0.38f, h));
        }
    }

    @Override
    public RectF buttonRect(String action) { return buttons.get(action); }

    @Override
    public String hit(float x, float y) {
        for (Map.Entry<String, RectF> e : buttons.entrySet())
            if (e.getValue().contains(x, y)) return e.getKey().equals("status2") ? "status" : e.getKey();
        return null;
    }

    @Override
    public MusicPainter.Dress musicDress() {
        MusicPainter.Dress d = new MusicPainter.Dress();
        d.frame = 0xF0040C14;
        d.edge = 0xB046E8FF;
        d.title = new int[]{0xFF0A2030, 0xFF06121C};
        d.titleInk = WHITE;
        d.close = new int[]{0xFF0A2030};
        d.closeInk = CYAN;
        d.body = 0xF0030A10;
        d.ink = WHITE;
        d.soft = 0xFF7FC8DA;
        d.button = 0xFF06141E;
        d.buttonEdge = CYAN;
        d.buttonInk = CYAN;
        d.glow = 0x6646E8FF;
        d.spacing = 0.1f;
        d.corner = 4;
        d.face = raj;
        d.titleFace = rajB;
        return d;
    }
}
