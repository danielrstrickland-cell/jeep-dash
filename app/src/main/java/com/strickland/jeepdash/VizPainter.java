package com.strickland.jeepdash;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import com.strickland.jeepdash.core.VizTune;

/**
 * Draws the music visualizer: the looks, seven colour profiles, 16 to 96 bars, one menu button that opens a
 * side menu, and the tune window (look, colours, effects, presets) in light or dark XP style. The picture (drawScene) and the buttons
 * (drawChrome) are drawn separately so the GPU glow and CRT filter touch only the picture. Only graphics, no
 * Android views.
 */
public final class VizPainter {
    public static final int BANDS = 96;                              // how finely the sound is analysed
    public static final int[] COUNTS = {16, 24, 32, 48, 64, 96};    // how many bars are drawn
    /** By look number; number 2 (the boom meters) was taken out, see VizTune.CHOICES. */
    public static final String[] LOOKS = {"Blocks", "Smooth", "", "Bass ring", "Swirl", "Ferrofluid", "Milk drop", "Road", "Lava lamp", "Mandelbrot"};
    public static final String[] COLORS = {"RPM", "Vice", "Factory", "XP", "Fire", "Ice", "Classic"};
    /** low, middle, high, accent. The first profile follows the engine instead. */
    private static final int[][] PROFILES = {
            null,
            {0xFFFF2FA8, 0xFF9B4DFF, 0xFF22E6FF, 0xFFFFE45C},      // Vice: hot pink, purple, cyan
            {0xFF0B6F60, 0xFF19C9A3, 0xFF9DFFE4, 0xFFFF6A1A},      // Factory: cluster teal, orange needle
            {0xFF245EDC, 0xFF2FB5C4, 0xFF7BD14B, 0xFFFFFFFF},      // XP: Luna blue to green
            {0xFFD81E05, 0xFFFF7A0F, 0xFFFFD21F, 0xFFFFFFFF},      // Fire
            {0xFF1B4DFF, 0xFF39C2FF, 0xFFD6F8FF, 0xFFFFFFFF},      // Ice
            {0xFF19C23A, 0xFFE8D21A, 0xFFFF2A1A, 0xFFFFFFFF},      // Classic stereo
    };
    private static final Typeface BOLD = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
    private static final float[] SWEEP_STOPS = {0f, 0.5f, 1f};

    /** A look drawn by another class (the swirl needs off-screen pictures this class does not use). */
    public interface Extra {
        void draw(Canvas c, int w, int h, float dt, float seconds, VizPainter p);
    }

    public Extra swirl;
    /** The graphics-chip looks, by look number; null where the device cannot run them (smooth bars instead). */
    public final Extra[] gpu = new Extra[10];
    public float sloshX, sloshY;       // the G-meter's push, from VizMotion
    public Double speed;                 // mph from the Jeep, null when not connected

    // What to draw; the view fills these in before every frame.
    public final float[] levels = new float[BANDS], peaks = new float[BANDS];
    public float vu, bass, beat;
    public Double rpm;                   // null when the Jeep is not connected
    public int look, color, shown = 32;
    public String message;               // shown at the bottom (microphone not allowed yet)
    public final RectF slider = new RectF();
    public VizTune tune = new VizTune(); // per-look settings from the tune window
    public final RectF resetButton = new RectF(), musicButton = new RectF();
    // the side menu and its one button; safeTop keeps it all clear of the status bar
    public float safeTop, chromeAlpha = 1;
    public boolean drawerOpen, hidden;
    public final RectF menuButton = new RectF(), drawerRect = new RectF(), drawerClose = new RectF();
    public final RectF rowDash = new RectF(), lookPrev = new RectF(), lookNext = new RectF();
    public final RectF colorPrev = new RectF(), colorNext = new RectF(), rowTune = new RectF();
    public final RectF rowEffects = new RectF(), rowHide = new RectF();
    private float sliderX0, sliderX1;
    private int xpBody, xpInk, xpMuted, xpTitle0, xpTitle1, xpEdge, xpLine, xpTrack, xpFill, xpTabOn, xpTabOff,
            xpButtonFill, xpButtonEdge;
    public boolean music, musicOpen;     // the music button (set on the dash's menu) and its window
    private final RectF[] rows = new RectF[8];
    private float trackLeft, trackRight;

    // the hidden tune menu (hold TUNE): tabs Look, Colors, Effects, Presets
    public static final String[] TABS = {"Look", "Colors", "Effects", "Presets"};
    public static final int TAB_LOOK = 0, TAB_COLORS = 1, TAB_EFFECTS = 2, TAB_PRESETS = 3, PRESETS = 3;
    public boolean menuOpen;
    public int menuTab;
    public final RectF menuWindow = new RectF(), menuClose = new RectF();
    public final RectF[] tabRects = rects(TABS.length), colorCells = rects(COLORS.length);
    public final RectF[] presetSave = rects(PRESETS), presetLoad = rects(PRESETS);
    public final String[] presetInfo = {"Empty", "Empty", "Empty"};

    // a look change fades and zooms from the old look into the new one
    private int fromLook = -1;
    private float blendT = 1;

    // shaders made once and reused while their colours and size stay the same
    private final Matrix unit = new Matrix();
    private RadialGradient roomShader, ringShader;
    private int roomColor, roomW, roomH, ringColor0, ringColor1;
    private LinearGradient sweepShader;
    private float sweepTop, sweepBase;
    private final int[] sweepMade = new int[3];

    private final Paint paint = new Paint();
    private final RectF rect = new RectF();
    private final int[] pal = new int[4], sweep = new int[3];
    private final float[] disp = new float[BANDS], dpeak = new float[BANDS];
    private float hue = 200;

    public void draw(Canvas c, int w, int h, float dt, float seconds) {
        drawScene(c, w, h, dt, seconds);
        drawChrome(c, w, h);
    }

    /** Switches to another look, blending from the one on screen. */
    public void changeLook(int next) {
        if (next == look) return;
        fromLook = look;
        look = next;
        blendT = 0;
    }

    /** The picture alone: background, the look (or two looks while blending). */
    public void drawScene(Canvas c, int w, int h, float dt, float seconds) {
        float gain = tune.get(look, "gain");
        vu = clamp(vu * gain);
        bass = clamp(bass * gain);
        palette(dt, seconds);
        c.drawColor(0xFF000000);
        // the room lights up with the bass
        if (roomShader == null || roomColor != pal[0] || roomW != w || roomH != h) {
            roomShader = new RadialGradient(w / 2f, h, h * 0.95f, pal[0], pal[0] & 0x00FFFFFF, Shader.TileMode.CLAMP);
            roomColor = pal[0];
            roomW = w;
            roomH = h;
        }
        fill(0);
        paint.setShader(roomShader);
        paint.setAlpha(Math.min(255, (int) ((40 + 110 * bass + 90 * beat) * tune.get(look, "glow"))));
        c.drawRect(0, 0, w, h, paint);

        group(dt, gain);
        float time = tune.get(VizTune.GLOBAL, "blend");
        if (fromLook >= 0 && time > 0.02f && (blendT += dt / time) < 1) {
            float t = blendT * blendT * (3 - 2 * blendT);           // eased
            int saved = c.saveLayerAlpha(0, 0, w, h, Math.round(255 * (1 - t)));
            c.scale(1 + 0.3f * t, 1 + 0.3f * t, w / 2f, h / 2f);    // the old look swells and melts away
            look(c, fromLook, w, h, dt, seconds);
            c.restoreToCount(saved);
            saved = c.saveLayerAlpha(0, 0, w, h, Math.round(255 * t));
            float grow = 0.85f + 0.15f * t;                          // while the new one grows in
            c.scale(grow, grow, w / 2f, h / 2f);
            look(c, look, w, h, dt, seconds);
            c.restoreToCount(saved);
        } else {
            fromLook = -1;
            look(c, look, w, h, dt, seconds);
        }
        clock = seconds;
        sparks(c, w, h, dt);
    }

    // ---- particle storm: sparks thrown up on every bass hit, over any look ----

    private static final int SPARKS = 1200;
    private final float[] spX = new float[SPARKS], spY = new float[SPARKS], spVx = new float[SPARKS],
            spVy = new float[SPARKS], spLife = new float[SPARKS];
    private final float[] spPts = new float[SPARKS * 2];
    private final java.util.Random spRandom = new java.util.Random(3);
    private float spBeat, spTrickle, clock;
    private int spNext;

    private void sparks(Canvas c, int w, int h, float dt) {
        float storm = tune.get(VizTune.GLOBAL, "storm");
        boolean hit = beat > 0.9f && spBeat <= 0.9f;
        spBeat = beat;
        int emit = 0;
        if (storm > 0.01f) {
            if (hit) emit += Math.round(160 * storm * (0.5f + bass));
            spTrickle += dt * 60 * storm * vu;
            emit += (int) spTrickle;
            spTrickle -= (int) spTrickle;
        }
        for (int k = 0; k < emit; k++) {
            int i = spNext;
            spNext = (spNext + 1) % SPARKS;
            spX[i] = w * (0.1f + 0.8f * spRandom.nextFloat());
            spY[i] = h * (0.88f + 0.06f * spRandom.nextFloat());
            spVx[i] = (spRandom.nextFloat() - 0.5f) * h * 0.6f;
            spVy[i] = -h * (0.5f + spRandom.nextFloat() * (0.6f + 0.8f * bass));
            spLife[i] = 1;
        }
        // four age groups, each drawn in one go: young sparks bright and pale, old ones dim and coloured
        for (int group = 0; group < 4; group++) {
            int n = 0;
            float hi = 1 - group * 0.25f, lo = hi - 0.25f;
            for (int i = 0; i < SPARKS; i++) {
                if (spLife[i] <= 0) continue;
                if (group == 0) {                                     // move every live spark once a frame
                    spVx[i] += sloshX * h * 0.9f * dt;
                    spVy[i] += h * 0.9f * dt;
                    spVx[i] *= 1 - 0.6f * dt;
                    spX[i] += spVx[i] * dt;
                    spY[i] += spVy[i] * dt;
                    spLife[i] -= dt / 1.4f;
                    if (spLife[i] <= 0 || spY[i] > h) {
                        spLife[i] = 0;
                        continue;
                    }
                }
                if (spLife[i] > lo && spLife[i] <= hi) {
                    spPts[n++] = spX[i];
                    spPts[n++] = spY[i];
                }
            }
            if (n == 0) continue;
            stroke(lerp(mix(1 - hi), pal[3], 0.5f * hi), h / 800f * (5 - group));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setAlpha(Math.round(255 * Math.min(1, hi * 1.2f)));
            c.drawPoints(spPts, 0, n, paint);
        }
    }

    // ---- 3D bars: solid columns standing on a floor, seen from a camera that drifts and leans ----

    private final android.graphics.Path face = new android.graphics.Path();

    private void bars3d(Canvas c, int w, int h, float width, boolean caps, float depth) {
        float left = w * 0.06f, right = w * 0.94f, base = h * 0.86f, top = h * 0.18f, full = base - top;
        float slot = (right - left) / shown, bw = slot * width;
        float vx = w / 2f + (float) Math.sin(clock * 0.13) * w * 0.12f + sloshX * w * 0.1f;
        float vy = h * 0.02f + sloshY * h * 0.04f, k = 1 - 0.09f * depth;    // k: how far back the far face sits
        System.arraycopy(pal, 0, sweep, 0, 3);
        if (sweepShader == null || sweepTop != top || sweepBase != base || !java.util.Arrays.equals(sweep, sweepMade)) {
            sweepShader = new LinearGradient(0, base, 0, top, sweep, SWEEP_STOPS, Shader.TileMode.CLAMP);
            System.arraycopy(sweep, 0, sweepMade, 0, 3);
            sweepTop = top;
            sweepBase = base;
        }
        stroke(pal[0], h / 800f * 2);                                 // the floor's front edge
        paint.setAlpha(120);
        c.drawLine(left, base, right, base, paint);
        // outside in, so bars nearer the middle cover the sides of the ones beyond them
        for (int n = 0; n < shown; n++) {
            int b = n % 2 == 0 ? n / 2 : shown - 1 - n / 2;
            float x0 = left + b * slot + (slot - bw) / 2, x1 = x0 + bw;
            float yt = base - Math.max(full * 0.01f, disp[b] * full);
            int col = mix(disp[b] * 0.7f + (float) b / shown * 0.3f);
            box(c, x0, x1, yt, base, vx, vy, k, sweepShader, lerp(col, 0xFFFFFFFF, 0.35f), lerp(col, 0xFF000000, 0.5f));
            if (caps && dpeak[b] > 0.03f) {
                float py = base - dpeak[b] * full - full * 0.02f;
                box(c, x0, x1, py - full * 0.012f, py, vx, vy, k, null, pal[3], lerp(pal[3], 0xFF000000, 0.4f));
            }
        }
    }

    /** One box: front face (shaded, or the top colour), top face, and the side facing the middle. */
    private void box(Canvas c, float x0, float x1, float yt, float yb, float vx, float vy, float k,
                     Shader front, int topColor, int sideColor) {
        float bx0 = vx + (x0 - vx) * k, bx1 = vx + (x1 - vx) * k, byt = vy + (yt - vy) * k, byb = vy + (yb - vy) * k;
        fill(topColor);
        face.rewind();
        face.moveTo(x0, yt);
        face.lineTo(x1, yt);
        face.lineTo(bx1, byt);
        face.lineTo(bx0, byt);
        face.close();
        c.drawPath(face, paint);
        if (x1 < vx || x0 > vx) {
            float fx = x1 < vx ? x1 : x0, bx = x1 < vx ? bx1 : bx0;
            fill(sideColor);
            face.rewind();
            face.moveTo(fx, yt);
            face.lineTo(bx, byt);
            face.lineTo(bx, byb);
            face.lineTo(fx, yb);
            face.close();
            c.drawPath(face, paint);
        }
        fill(topColor);
        if (front != null) paint.setShader(front);
        c.drawRect(x0, yt, x1, yb, paint);
    }

    private void look(Canvas c, int which, int w, int h, float dt, float seconds) {
        boolean caps = tune.on(which, "caps");
        if (which < gpu.length && gpu[which] != null) {
            try {
                gpu[which].draw(c, w, h, dt, seconds, this);
                return;
            } catch (RuntimeException e) {                         // the graphics chip refused it: use smooth bars
                gpu[which] = null;
                message = LOOKS[which] + " could not start on this tablet";
            }
        }
        if (which == 4 && swirl != null) swirl.draw(c, w, h, dt, seconds, this);
        else if (which == 3) ring(c, w, h, caps);
        else if (which == 1 && tune.on(1, "3d")) bars3d(c, w, h, tune.get(1, "width"), caps, tune.get(1, "depth"));
        else if (which == 1 || which >= VizTune.FERRO) smooth(c, w * 0.04f, w * 0.96f, h * 0.14f, h * 0.93f, tune.get(1, "width"), caps);
        else bars(c, w * 0.04f, w * 0.96f, h * 0.14f, h * 0.93f, 30, tune.get(0, "width"), caps);
    }

    /** The menu button, the side menu, the tune window and any message, drawn over the picture. */
    public void drawChrome(Canvas c, int w, int h) {
        float u = h / 800f;
        theme(tune.on(VizTune.GLOBAL, "dark"));
        if (message != null) text(c, message, w / 2f, h - 30 * u, 26 * u, 0xFFFFFFFF, Paint.Align.CENTER);
        if (hidden) {
            menuButton.setEmpty();
            return;
        }
        float top = safeTop + 14 * u, b = 60 * u;
        menuButton.set(16 * u, top, 16 * u + b * 1.15f, top + b);
        if (chromeAlpha > 0.01f) {
            int a = Math.round(255 * chromeAlpha);
            fill(0x66000000);
            paint.setAlpha(Math.round(0x66 * chromeAlpha));
            c.drawRoundRect(menuButton, 14 * u, 14 * u, paint);
            stroke(0xFFFFFFFF, 4 * u);
            paint.setAlpha(a);
            paint.setStrokeCap(Paint.Cap.ROUND);
            float x0 = menuButton.left + 20 * u, x1 = menuButton.right - 20 * u, cy = menuButton.centerY();
            for (int i = -1; i <= 1; i++) c.drawLine(x0, cy + i * 12 * u, x1, cy + i * 12 * u, paint);
        }
        if (drawerOpen) drawer(c, w, h, u);
        if (menuOpen) menu(c, w, h, u);
    }

    // ---- the side menu: everything the old top row had, in one panel down the left ----

    private void drawer(Canvas c, int w, int h, float u) {
        float dw = Math.min(w * 0.34f, 460 * u), x = 0, y = 0, bar = safeTop + 56 * u;
        drawerRect.set(x, y, x + dw, h);
        fill(xpBody);
        c.drawRect(drawerRect, paint);
        fill(0);
        paint.setShader(new LinearGradient(0, 0, 0, bar, xpTitle0, xpTitle1, Shader.TileMode.CLAMP));
        c.drawRect(x, y, x + dw, bar, paint);
        text(c, "Visualizer", x + 22 * u, bar - 18 * u, 23 * u, 0xFFFFFFFF, Paint.Align.LEFT);
        float cs = 36 * u;
        drawerClose.set(x + dw - cs - 12 * u, bar - cs - 10 * u, x + dw - 12 * u, bar - 10 * u);
        closeBox(c, drawerClose, u);
        stroke(xpEdge, 2 * u);
        c.drawLine(x + dw, 0, x + dw, h, paint);

        float rowH = Math.min(78 * u, (h - bar - 20 * u) / 8), left = x + 24 * u, right = x + dw - 24 * u, r = bar + 8 * u;
        r = row(c, rowDash, left, right, r, rowH, "Dash", "\u203A", u);
        divider(c, left, right, r, u);
        r = chooser(c, lookPrev, lookNext, left, right, r, rowH, "Look", LOOKS[look], u);
        r = chooser(c, colorPrev, colorNext, left, right, r, rowH, "Colors", COLORS[color], u);
        // bars: a slider with six stops
        text(c, "Bars", left, r + rowH * 0.42f, 21 * u, xpInk, Paint.Align.LEFT);
        text(c, String.valueOf(shown), right, r + rowH * 0.42f, 20 * u, xpInk, Paint.Align.RIGHT);
        int at = 0;
        for (int i = 0; i < COUNTS.length; i++) if (COUNTS[i] == shown) at = i;
        sliderX0 = left + 10 * u;
        sliderX1 = right - 10 * u;
        float sy = r + rowH * 0.75f, knob = sliderX0 + (sliderX1 - sliderX0) * at / (COUNTS.length - 1);
        slider.set(left, r + rowH * 0.5f, right, r + rowH);
        track(c, sliderX0, sliderX1, knob, sy, u);
        r += rowH;
        divider(c, left, right, r, u);
        r = row(c, rowTune, left, right, r, rowH, "Tune this look", "\u203A", u);
        r = row(c, rowEffects, left, right, r, rowH, "Effects and presets", "\u203A", u);
        r = row(c, rowHide, left, right, r, rowH, "Hide controls", "3 fingers", u);
        if (music) row(c, musicButton, left, right, r, rowH, "Music", musicOpen ? "Close" : "\u203A", u);
        else musicButton.setEmpty();
    }

    private float row(Canvas c, RectF hit, float left, float right, float top, float rowH, String label, String end, float u) {
        hit.set(left - 12 * u, top, right + 12 * u, top + rowH);
        text(c, label, left, top + rowH * 0.6f, 21 * u, xpInk, Paint.Align.LEFT);
        text(c, end, right, top + rowH * 0.6f, 19 * u, xpMuted, Paint.Align.RIGHT);
        return top + rowH;
    }

    /** A row with the choice in the middle and arrows to step back and on. */
    private float chooser(Canvas c, RectF prev, RectF next, float left, float right, float top, float rowH,
                          String label, String value, float u) {
        float y = top + rowH * 0.6f, bw = 48 * u, bh = 44 * u, by = top + (rowH - bh) / 2;
        text(c, label, left, y, 21 * u, xpInk, Paint.Align.LEFT);
        next.set(right - bw, by, right, by + bh);
        float vw = Math.max(150 * u, (right - left) * 0.45f);
        prev.set(next.left - vw - bw - 8 * u, by, next.left - vw - 8 * u, by + bh);
        xpBox(c, prev, "\u25C0", u);
        xpBox(c, next, "\u25B6", u);
        text(c, value, (prev.right + next.left) / 2, y, 19 * u, xpInk, Paint.Align.CENTER);
        return top + rowH;
    }

    private void divider(Canvas c, float left, float right, float y, float u) {
        stroke(xpLine, 1.5f * u);
        c.drawLine(left, y, right, y, paint);
    }

    private void xpBox(Canvas c, RectF where, String label, float u) {
        fill(xpButtonFill);
        c.drawRoundRect(where, 5 * u, 5 * u, paint);
        stroke(xpButtonEdge, 1.5f * u);
        c.drawRoundRect(where, 5 * u, 5 * u, paint);
        text(c, label, where.centerX(), where.centerY() + 7 * u, 17 * u, xpInk, Paint.Align.CENTER);
    }

    private void closeBox(Canvas c, RectF where, float u) {
        fill(0xFFD9442B);
        c.drawRoundRect(where, 5 * u, 5 * u, paint);
        stroke(0xFFFFFFFF, 3.5f * u);
        float in = where.width() * 0.28f;
        c.drawLine(where.left + in, where.top + in, where.right - in, where.bottom - in, paint);
        c.drawLine(where.right - in, where.top + in, where.left + in, where.bottom - in, paint);
    }

    /** An XP slider: a groove, the filled part, and a blue thumb. */
    private void track(Canvas c, float x0, float x1, float knob, float y, float u) {
        fill(xpTrack);
        c.drawRoundRect(set(x0, y - 3 * u, x1, y + 3 * u), 3 * u, 3 * u, paint);
        fill(xpFill);
        c.drawRoundRect(set(x0, y - 3 * u, knob, y + 3 * u), 3 * u, 3 * u, paint);
        fill(0xFF0A55E0);
        c.drawRoundRect(set(knob - 9 * u, y - 15 * u, knob + 9 * u, y + 15 * u), 4 * u, 4 * u, paint);
        stroke(0xFFFFFFFF, 2 * u);
        c.drawRoundRect(set(knob - 9 * u, y - 15 * u, knob + 9 * u, y + 15 * u), 4 * u, 4 * u, paint);
    }

    /** Luna (light) or a dark take on it, for the side menu and tune window. */
    private void theme(boolean dark) {
        xpBody = dark ? 0xFF1E2129 : 0xFFECE9D8;
        xpInk = dark ? 0xFFE3E7F0 : 0xFF1D2A4A;
        xpMuted = dark ? 0xFF9AA3B5 : 0xFF4B5563;
        xpTitle0 = dark ? 0xFF3A3F4B : 0xFF3D8BFF;
        xpTitle1 = dark ? 0xFF16181E : 0xFF0A55E0;
        xpEdge = dark ? 0xFF4A505C : 0xFF919B9C;
        xpLine = dark ? 0xFF343944 : 0xFFC9C5B4;
        xpTrack = dark ? 0xFF3A3F4B : 0xFFA7A391;
        xpFill = dark ? 0xFF4C8DFF : 0xFF316AC5;
        xpTabOn = dark ? 0xFF2C313C : 0xFFFFFFFF;
        xpTabOff = dark ? 0xFF15171C : 0xFFD6D2C2;
        xpButtonFill = dark ? 0xFF2C313C : 0xFFFFFFFF;
        xpButtonEdge = dark ? 0xFF5A6273 : 0xFF003C74;
    }

    private void palette(float dt, float seconds) {
        int[] p = PROFILES[color];
        if (p != null) {
            System.arraycopy(p, 0, pal, 0, 4);
            return;
        }
        // blue at idle through green and yellow to red near the top; a slow drift when there is no engine data
        float want = rpm == null ? (seconds * 14) % 360 : 200 - 200 * clamp((float) ((rpm - 650) / 3850));
        hue = rpm == null ? want : hue + (want - hue) * Math.min(1, dt * 5);
        pal[0] = hsv(hue, 1f, 0.85f);
        pal[1] = hsv(hue - 22, 0.95f, 1f);
        pal[2] = hsv(hue - 45, 0.55f, 1f);
        pal[3] = 0xFFFFFFFF;
    }

    /** Folds the analysed bands into the bars on screen and glides them between sound frames. */
    private void group(float dt, float gain) {
        float k = 1 - (float) Math.exp(-dt * 28);
        for (int j = 0; j < shown; j++) {
            int from = j * BANDS / shown, to = Math.max(from + 1, (j + 1) * BANDS / shown);
            float lv = 0, pk = 0;
            for (int b = from; b < to; b++) {
                lv = Math.max(lv, levels[b]);
                pk = Math.max(pk, peaks[b]);
            }
            disp[j] += (clamp(lv * gain) - disp[j]) * k;
            dpeak[j] += (clamp(pk * gain) - dpeak[j]) * k;
        }
    }

    // ---- look 1: retro LED blocks with falling peak caps ----

    private void bars(Canvas c, float left, float right, float top, float base, int segs, float width, boolean caps) {
        float slot = (right - left) / shown, bw = slot * width, sh = (base - top) / segs;
        float gap = Math.max(1.5f, sh * 0.22f);
        fill(0);
        for (int b = 0; b < shown; b++) {
            float x = left + b * slot + (slot - bw) / 2;
            int lit = Math.round(disp[b] * segs);
            for (int s = 0; s < segs; s++) {
                float y = base - (s + 1) * sh;
                paint.setColor(s < lit ? mix((float) s / (segs - 1)) : 0xFF151515);
                c.drawRect(x, y + gap, x + bw, y + sh, paint);
            }
            if (caps && dpeak[b] > 0.03f) {
                float y = base - Math.min(segs, Math.round(dpeak[b] * segs) + 1) * sh;
                paint.setColor(pal[3]);
                c.drawRect(x, y + gap, x + bw, y + gap + Math.max(3, sh * 0.3f), paint);
            }
        }
    }

    // ---- look 2: smooth bars, one continuous colour sweep, redrawn at the screen's full refresh rate ----

    private void smooth(Canvas c, float left, float right, float top, float base, float width, boolean caps) {
        float slot = (right - left) / shown, bw = slot * width, full = base - top;
        float round = Math.min(bw / 2, full * 0.012f);
        System.arraycopy(pal, 0, sweep, 0, 3);
        if (sweepShader == null || sweepTop != top || sweepBase != base || !java.util.Arrays.equals(sweep, sweepMade)) {
            sweepShader = new LinearGradient(0, base, 0, top, sweep, SWEEP_STOPS, Shader.TileMode.CLAMP);
            System.arraycopy(sweep, 0, sweepMade, 0, 3);
            sweepTop = top;
            sweepBase = base;
        }
        Shader colors = sweepShader;
        for (int b = 0; b < shown; b++) {
            float x = left + b * slot + (slot - bw) / 2, tall = Math.max(round * 2, disp[b] * full);
            fill(0xFF000000);
            paint.setShader(colors);
            paint.setAlpha(60);                                     // soft halo
            c.drawRoundRect(set(x - bw * 0.14f, base - tall - bw * 0.14f, x + bw * 1.14f, base), round * 1.6f, round * 1.6f, paint);
            paint.setAlpha(255);
            c.drawRoundRect(set(x, base - tall, x + bw, base), round, round, paint);
            if (caps && dpeak[b] > 0.03f) {
                float y = base - dpeak[b] * full - round * 1.5f;
                fill(pal[3]);
                c.drawRoundRect(set(x, y - round * 0.5f, x + bw, y + round * 0.5f), round * 0.5f, round * 0.5f, paint);
            }
        }
    }

    // ---- look 4: the spectrum wrapped round a circle that swells with the bass ----

    private void ring(Canvas c, int w, int h, boolean caps) {
        float m = Math.min(w, h), cx = w / 2f, cy = h * 0.53f;
        float r = m * 0.18f * tune.get(3, "size") * (1 + (0.12f * bass + 0.07f * beat) * tune.get(3, "pulse"));
        float maxLen = Math.max(m * 0.04f, Math.min(m * 0.20f, m * 0.46f - r - m * 0.048f));   // keep it on the screen
        int n = shown * 2;
        fill(0);
        int inner = mix(0.5f), outer = pal[0] & 0x00FFFFFF;
        if (ringShader == null || ringColor0 != inner || ringColor1 != outer) {
            ringShader = new RadialGradient(0, 0, 1, inner, outer, Shader.TileMode.CLAMP);   // sized by its matrix
            ringColor0 = inner;
            ringColor1 = outer;
        }
        unit.setScale(r, r);
        unit.postTranslate(cx, cy);
        ringShader.setLocalMatrix(unit);
        paint.setShader(ringShader);
        paint.setAlpha(Math.min(255, (int) (90 + 165 * bass)));
        c.drawCircle(cx, cy, r, paint);
        stroke(pal[0], m * 0.006f);
        c.drawCircle(cx, cy, r, paint);
        float thick = (float) (2 * Math.PI * r / n * 0.55f);
        for (int i = 0; i < n; i++) {
            int b = i < shown ? i : n - 1 - i;                      // bass at the bottom, treble at the top
            double a = Math.PI / 2 + (i + 0.5) * 2 * Math.PI / n;
            float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
            float r1 = r + m * 0.02f, r2 = r1 + m * 0.008f + disp[b] * maxLen;
            stroke(mix(disp[b] * 0.65f + (float) b / shown * 0.35f), thick);
            paint.setStrokeCap(Paint.Cap.ROUND);
            c.drawLine(cx + dx * r1, cy + dy * r1, cx + dx * r2, cy + dy * r2, paint);
            if (caps && dpeak[b] > 0.03f) {
                float rp = r1 + m * 0.02f + dpeak[b] * maxLen;
                fill(pal[3]);
                c.drawCircle(cx + dx * rp, cy + dy * rp, thick * 0.3f, paint);
            }
        }
        if (rpm != null) {
            text(c, String.valueOf(Math.round(rpm)), cx, cy + m * 0.025f, m * 0.075f, 0xFFFFFFFF, Paint.Align.CENTER);
            text(c, "RPM", cx, cy + m * 0.065f, m * 0.028f, 0xCCFFFFFF, Paint.Align.CENTER);
        }
    }

    // ---- the hidden tune menu: an XP-style window, held open over the picture ----


    private void menu(Canvas c, int w, int h, float u) {
        float mw = Math.min(w * 0.62f, 940 * u), mh = Math.min(h - safeTop - 48 * u, 640 * u);
        float x = (w - mw) / 2, y = safeTop + 24 * u, bar = 46 * u, r = 10 * u;
        menuWindow.set(x, y, x + mw, y + mh);
        fill(xpBody);
        c.drawRoundRect(menuWindow, r, r, paint);
        // title bar
        fill(0);
        paint.setShader(new LinearGradient(0, y, 0, y + bar, xpTitle0, xpTitle1, Shader.TileMode.CLAMP));
        c.drawRoundRect(set(x, y, x + mw, y + bar), r, r, paint);
        c.drawRect(x, y + bar - r, x + mw, y + bar, paint);
        String title = menuTab == TAB_EFFECTS ? "Effects (every look)" : LOOKS[look];
        text(c, "Tune — " + title, x + 18 * u, y + 31 * u, 21 * u, 0xFFFFFFFF, Paint.Align.LEFT);
        float cs = 32 * u;
        menuClose.set(x + mw - cs - 8 * u, y + 7 * u, x + mw - 8 * u, y + 7 * u + cs);
        closeBox(c, menuClose, u);
        stroke(xpEdge, 2 * u);
        c.drawRoundRect(menuWindow, r, r, paint);

        // tabs
        float tx = x + 14 * u, ty = y + bar + 12 * u, th = 40 * u;
        for (int i = 0; i < TABS.length; i++) {
            paint.reset();
            paint.setTypeface(BOLD);
            paint.setTextSize(18 * u);
            float tw = paint.measureText(TABS[i]) + 36 * u;
            tabRects[i].set(tx, ty, tx + tw, ty + th);
            fill(i == menuTab ? xpTabOn : xpTabOff);
            c.drawRoundRect(tabRects[i], 5 * u, 5 * u, paint);
            stroke(i == menuTab ? 0xFFFFC73C : xpEdge, (i == menuTab ? 3 : 1.5f) * u);
            c.drawRoundRect(tabRects[i], 5 * u, 5 * u, paint);
            text(c, TABS[i], tx + 18 * u, ty + 27 * u, 18 * u, xpInk, Paint.Align.LEFT);
            tx += tw + 8 * u;
        }
        float top = ty + th + 14 * u, left = x + 26 * u, right = x + mw - 26 * u, bottom = y + mh - 16 * u;
        if (menuTab == TAB_COLORS) colorsTab(c, left, top, right, u);
        else if (menuTab == TAB_PRESETS) presetsTab(c, left, top, right, u);
        else knobsTab(c, left, top, right, bottom, u);
    }

    private void knobsTab(Canvas c, float left, float top, float right, float bottom, float u) {
        int t = target();
        VizTune.Knob[] knobs = VizTune.knobs(t);
        float rowH = Math.min(62 * u, (bottom - top - 60 * u) / Math.max(1, knobs.length));
        trackLeft = left + 230 * u;
        trackRight = right - 90 * u;
        for (int i = 0; i < knobs.length && i < rows.length; i++) {
            VizTune.Knob k = knobs[i];
            float rt = top + i * rowH, mid = rt + rowH / 2;
            if (rows[i] == null) rows[i] = new RectF();
            rows[i].set(left, rt, right, rt + rowH);
            text(c, k.label, left, mid + 7 * u, 19 * u, xpInk, Paint.Align.LEFT);
            if (k.toggle) {
                boolean on = tune.on(t, k.id);
                float b = 26 * u;
                fill(xpButtonFill);
                c.drawRect(trackLeft, mid - b / 2, trackLeft + b, mid + b / 2, paint);
                stroke(0xFF1C5180, 1.5f * u);
                c.drawRect(trackLeft, mid - b / 2, trackLeft + b, mid + b / 2, paint);
                if (on) {
                    stroke(0xFF21A121, 4 * u);
                    paint.setStrokeCap(Paint.Cap.ROUND);
                    c.drawLine(trackLeft + 6 * u, mid, trackLeft + 11 * u, mid + 6 * u, paint);
                    c.drawLine(trackLeft + 11 * u, mid + 6 * u, trackLeft + 20 * u, mid - 7 * u, paint);
                }
                continue;
            }
            float v = tune.get(t, k.id), knob = trackLeft + (trackRight - trackLeft) * tune.at(t, k.id);
            track(c, trackLeft, trackRight, knob, mid, u);
            String shows = VizTune.seconds(k.id) ? Math.round(v * 100) / 100f + " s"
                    : k.id.equals("crt") && v < 0.01f ? "Off" : Math.round(v * 100) + "%";
            text(c, shows, right, mid + 7 * u, 18 * u, xpInk, Paint.Align.RIGHT);
        }
        xpButton(c, resetButton, left, bottom - 44 * u, "Reset", u);
    }

    private void colorsTab(Canvas c, float left, float top, float right, float u) {
        int cols = 4;
        float gap = 12 * u, cw = (right - left - gap * (cols - 1)) / cols, ch = 92 * u;
        for (int i = 0; i < COLORS.length; i++) {
            float cx = left + (i % cols) * (cw + gap), cy = top + (i / cols) * (ch + gap);
            colorCells[i].set(cx, cy, cx + cw, cy + ch);
            fill(xpButtonFill);
            c.drawRoundRect(colorCells[i], 6 * u, 6 * u, paint);
            int[] p = PROFILES[i];
            float sw = (cw - 24 * u) / 3;
            for (int k = 0; k < 3; k++) {
                fill(p == null ? hsv(200 - k * 90, 1, 0.9f) : p[k]);
                c.drawRect(cx + 12 * u + k * sw, cy + 12 * u, cx + 12 * u + (k + 1) * sw, cy + 46 * u, paint);
            }
            text(c, COLORS[i] + (i == 0 ? " (engine)" : ""), cx + 12 * u, cy + 76 * u, 18 * u, xpInk, Paint.Align.LEFT);
            stroke(i == color ? xpFill : xpEdge, (i == color ? 4 : 1.5f) * u);
            c.drawRoundRect(colorCells[i], 6 * u, 6 * u, paint);
        }
    }

    private void presetsTab(Canvas c, float left, float top, float right, float u) {
        for (int i = 0; i < PRESETS; i++) {
            float y = top + i * 74 * u;
            text(c, "Preset " + (i + 1), left, y + 30 * u, 20 * u, xpInk, Paint.Align.LEFT);
            text(c, presetInfo[i], left + 120 * u, y + 30 * u, 17 * u, xpMuted, Paint.Align.LEFT);
            xpButton(c, presetLoad[i], right - 110 * u, y, "Load", u);
            xpButton(c, presetSave[i], right - 230 * u, y, "Save", u);
        }
        text(c, "Save keeps the look, colours and every tune setting.", left, top + PRESETS * 74 * u + 20 * u,
                16 * u, xpMuted, Paint.Align.LEFT);
    }

    private void xpButton(Canvas c, RectF where, float x, float y, String label, float u) {
        where.set(x, y, x + 104 * u, y + 42 * u);
        fill(xpButtonFill);
        c.drawRoundRect(where, 5 * u, 5 * u, paint);
        stroke(xpButtonEdge, 1.5f * u);
        c.drawRoundRect(where, 5 * u, 5 * u, paint);
        text(c, label, where.centerX(), y + 28 * u, 18 * u, xpInk, Paint.Align.CENTER);
    }

    private static RectF[] rects(int n) {
        RectF[] out = new RectF[n];
        for (int i = 0; i < n; i++) out[i] = new RectF();
        return out;
    }

    // ---- small helpers ----

    /** A finger at x on the side menu's bar slider picks the nearest bar count. */
    public void slide(float x) {
        float t = clamp((x - sliderX0) / Math.max(1, sliderX1 - sliderX0));
        shown = COUNTS[Math.round(t * (COUNTS.length - 1))];
    }

    /** Which row of the open panel is at this point, or -1. */
    public int rowAt(float x, float y) {
        if (!menuOpen || (menuTab != TAB_LOOK && menuTab != TAB_EFFECTS)) return -1;
        int n = Math.min(VizTune.knobs(target()).length, rows.length);
        for (int i = 0; i < n; i++) if (rows[i] != null && rows[i].contains(x, y)) return i;
        return -1;
    }

    /** Whose knobs the open panel or menu shows: the look on screen, or the shared effects. */
    public int target() { return menuOpen && menuTab == TAB_EFFECTS ? VizTune.GLOBAL : look; }

    public boolean rowIsSwitch(int row) { return VizTune.knobs(target())[row].toggle; }

    /** A finger at x on a slider row moves its knob there. */
    public void dragRow(int row, float x) {
        tune.setAt(target(), VizTune.knobs(target())[row].id, (x - trackLeft) / (trackRight - trackLeft));
    }

    public void flipRow(int row) {
        int t = target();
        String id = VizTune.knobs(t)[row].id;
        tune.set(t, id, tune.on(t, id) ? 0 : 1);
    }

    private void pill(Canvas c, RectF where, float x, float y, String label, float u) {
        paint.reset();
        paint.setTypeface(BOLD);
        paint.setTextSize(20 * u);
        float tw = paint.measureText(label);
        where.set(x, y, x + tw + 36 * u, y + 46 * u);
        fill(0x55FFFFFF);
        c.drawRoundRect(where, 23 * u, 23 * u, paint);
        text(c, label, x + 18 * u, y + 30 * u, 20 * u, 0xFFFFFFFF, Paint.Align.LEFT);
    }

    private void text(Canvas c, String s, float x, float baseline, float size, int color, Paint.Align align) {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setColor(color);
        paint.setTypeface(BOLD);
        paint.setTextSize(size);
        paint.setTextAlign(align);
        c.drawText(s, x, baseline, paint);
    }

    private void fill(int color) {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
    }

    private void stroke(int color, float width) {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setColor(color);
    }

    private RectF set(float l, float t, float r, float b) {
        rect.set(l, t, r, b);
        return rect;
    }

    /** Height of bar j on screen, 0..1 (for looks drawn elsewhere). */
    public float bar(int j) { return disp[j]; }

    public int color(float t) { return mix(t); }

    public int accent() { return pal[3]; }

    /** The profile's colour at t: 0 = low, 0.5 = middle, 1 = high. */
    private int mix(float t) {
        t = clamp(t);
        return t < 0.5f ? lerp(pal[0], pal[1], t * 2) : lerp(pal[1], pal[2], t * 2 - 1);
    }

    static float clamp(float x) { return x < 0 ? 0 : Math.min(x, 1); }

    static int lerp(int a, int b, float t) {
        int out = 0xFF000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            int ca = (a >> shift) & 0xff, cb = (b >> shift) & 0xff;
            out |= Math.round(ca + (cb - ca) * t) << shift;
        }
        return out;
    }

    static int hsv(float h, float s, float v) {
        h = ((h % 360) + 360) % 360;
        float ch = v * s, x = ch * (1 - Math.abs(h / 60 % 2 - 1)), m = v - ch, r, g, b;
        switch ((int) (h / 60)) {
            case 0: r = ch; g = x; b = 0; break;
            case 1: r = x; g = ch; b = 0; break;
            case 2: r = 0; g = ch; b = x; break;
            case 3: r = 0; g = x; b = ch; break;
            case 4: r = x; g = 0; b = ch; break;
            default: r = ch; g = 0; b = x; break;
        }
        return 0xFF000000 | Math.round((r + m) * 255) << 16 | Math.round((g + m) * 255) << 8 | Math.round((b + m) * 255);
    }
}
