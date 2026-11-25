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
 * Draws the music visualizer: the looks, seven colour profiles, 16 to 96 bars, the drop-down TUNE panel that
 * adjusts the look on screen, and the hidden tune menu (hold TUNE). The picture (drawScene) and the buttons
 * (drawChrome) are drawn separately so the GPU glow and CRT filter touch only the picture. Only graphics, no
 * Android views.
 */
public final class VizPainter {
    public static final int BANDS = 96;                              // how finely the sound is analysed
    public static final int[] COUNTS = {16, 24, 32, 48, 64, 96};    // how many bars are drawn
    /** By look number; number 2 (the boom meters) was taken out, see VizTune.CHOICES. */
    public static final String[] LOOKS = {"Blocks", "Smooth", "", "Bass ring", "Swirl"};
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

    // What to draw; the view fills these in before every frame.
    public final float[] levels = new float[BANDS], peaks = new float[BANDS];
    public float vu, bass, beat;
    public Double rpm;                   // null when the Jeep is not connected
    public int look, color, shown = 32;
    public String message;               // shown at the bottom (microphone not allowed yet)
    public final RectF dashButton = new RectF(), colorButton = new RectF(), slider = new RectF();
    public VizTune tune = new VizTune(); // per-look settings from the TUNE panel
    public boolean tuneOpen;
    public final RectF tuneButton = new RectF(), panel = new RectF(), resetButton = new RectF();
    public final RectF lookButton = new RectF(), musicButton = new RectF();
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
    }

    private void look(Canvas c, int which, int w, int h, float dt, float seconds) {
        boolean caps = tune.on(which, "caps");
        if (which == 4 && swirl != null) swirl.draw(c, w, h, dt, seconds, this);
        else if (which == 3) ring(c, w, h, caps);
        else if (which == 1) smooth(c, w * 0.04f, w * 0.96f, h * 0.14f, h * 0.93f, tune.get(1, "width"), caps);
        else bars(c, w * 0.04f, w * 0.96f, h * 0.14f, h * 0.93f, 30, tune.get(0, "width"), caps);
    }

    /** The buttons, slider, labels and panels, drawn over the picture. */
    public void drawChrome(Canvas c, int w, int h) {
        float u = h / 800f;
        pill(c, dashButton, 18 * u, 16 * u, "DASH", u);
        pill(c, colorButton, dashButton.right + 12 * u, 16 * u, "COLORS: " + COLORS[color].toUpperCase(), u);
        pill(c, tuneButton, colorButton.right + 12 * u, 16 * u, tuneOpen ? "TUNE \u25B4" : "TUNE \u25BE", u);
        // looks change only here, on purpose: a tap or swipe on the picture does nothing (brightness swipes)
        pill(c, lookButton, tuneButton.right + 12 * u, 16 * u, "LOOK: " + LOOKS[look].toUpperCase(), u);
        if (music) pill(c, musicButton, lookButton.right + 12 * u, 16 * u, musicOpen ? "\u266A MUSIC \u25B4" : "\u266A MUSIC", u);
        else musicButton.setEmpty();
        slider(c, w, u);
        text(c, LOOKS[look].toUpperCase(), w - 22 * u, 46 * u, 24 * u, 0x99FFFFFF, Paint.Align.RIGHT);
        if (rpm != null)
            text(c, Math.round(rpm) + " rpm", w - 22 * u, 78 * u, 22 * u, pal[2], Paint.Align.RIGHT);
        if (message != null) text(c, message, w / 2f, h - 30 * u, 26 * u, 0xFFFFFFFF, Paint.Align.CENTER);
        if (tuneOpen) tunePanel(c, w, u);
        if (menuOpen) menu(c, w, h, u);
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

    private static final int XP_BODY = 0xFFECE9D8, XP_INK = 0xFF1D2A4A, XP_BLUE = 0xFF0A55E0, XP_EDGE = 0xFF919B9C;

    private void menu(Canvas c, int w, int h, float u) {
        float mw = Math.min(w * 0.62f, 940 * u), mh = Math.min(h - 120 * u, 600 * u);
        float x = (w - mw) / 2, y = 90 * u, bar = 46 * u, r = 10 * u;
        menuWindow.set(x, y, x + mw, y + mh);
        fill(XP_BODY);
        c.drawRoundRect(menuWindow, r, r, paint);
        // title bar
        fill(0);
        paint.setShader(new LinearGradient(0, y, 0, y + bar, 0xFF3D8BFF, XP_BLUE, Shader.TileMode.CLAMP));
        c.drawRoundRect(set(x, y, x + mw, y + bar), r, r, paint);
        c.drawRect(x, y + bar - r, x + mw, y + bar, paint);
        String title = menuTab == TAB_EFFECTS ? "Effects (every look)" : LOOKS[look];
        text(c, "Tune — " + title, x + 18 * u, y + 31 * u, 21 * u, 0xFFFFFFFF, Paint.Align.LEFT);
        float cs = 32 * u;
        menuClose.set(x + mw - cs - 8 * u, y + 7 * u, x + mw - 8 * u, y + 7 * u + cs);
        fill(0xFFD9442B);
        c.drawRoundRect(menuClose, 5 * u, 5 * u, paint);
        stroke(0xFFFFFFFF, 3.5f * u);
        float in = 9 * u;
        c.drawLine(menuClose.left + in, menuClose.top + in, menuClose.right - in, menuClose.bottom - in, paint);
        c.drawLine(menuClose.right - in, menuClose.top + in, menuClose.left + in, menuClose.bottom - in, paint);
        stroke(XP_BLUE, 2 * u);
        c.drawRoundRect(menuWindow, r, r, paint);

        // tabs
        float tx = x + 14 * u, ty = y + bar + 12 * u, th = 40 * u;
        for (int i = 0; i < TABS.length; i++) {
            paint.reset();
            paint.setTypeface(BOLD);
            paint.setTextSize(18 * u);
            float tw = paint.measureText(TABS[i]) + 36 * u;
            tabRects[i].set(tx, ty, tx + tw, ty + th);
            fill(i == menuTab ? 0xFFFFFFFF : 0xFFD6D2C2);
            c.drawRoundRect(tabRects[i], 5 * u, 5 * u, paint);
            stroke(i == menuTab ? 0xFFFFC73C : XP_EDGE, (i == menuTab ? 3 : 1.5f) * u);
            c.drawRoundRect(tabRects[i], 5 * u, 5 * u, paint);
            text(c, TABS[i], tx + 18 * u, ty + 27 * u, 18 * u, XP_INK, Paint.Align.LEFT);
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
            text(c, k.label, left, mid + 7 * u, 19 * u, XP_INK, Paint.Align.LEFT);
            if (k.toggle) {
                boolean on = tune.on(t, k.id);
                float b = 26 * u;
                fill(0xFFFFFFFF);
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
            fill(0xFFA7A391);
            c.drawRoundRect(set(trackLeft, mid - 3 * u, trackRight, mid + 3 * u), 3 * u, 3 * u, paint);
            fill(0xFF316AC5);
            c.drawRoundRect(set(trackLeft, mid - 3 * u, knob, mid + 3 * u), 3 * u, 3 * u, paint);
            fill(XP_BLUE);
            c.drawRoundRect(set(knob - 9 * u, mid - 15 * u, knob + 9 * u, mid + 15 * u), 4 * u, 4 * u, paint);
            stroke(0xFFFFFFFF, 2 * u);
            c.drawRoundRect(set(knob - 9 * u, mid - 15 * u, knob + 9 * u, mid + 15 * u), 4 * u, 4 * u, paint);
            String shows = VizTune.seconds(k.id) ? Math.round(v * 100) / 100f + " s"
                    : k.id.equals("crt") && v < 0.01f ? "Off" : Math.round(v * 100) + "%";
            text(c, shows, right, mid + 7 * u, 18 * u, XP_INK, Paint.Align.RIGHT);
        }
        xpButton(c, resetButton, left, bottom - 44 * u, "Reset", u);
    }

    private void colorsTab(Canvas c, float left, float top, float right, float u) {
        int cols = 4;
        float gap = 12 * u, cw = (right - left - gap * (cols - 1)) / cols, ch = 92 * u;
        for (int i = 0; i < COLORS.length; i++) {
            float cx = left + (i % cols) * (cw + gap), cy = top + (i / cols) * (ch + gap);
            colorCells[i].set(cx, cy, cx + cw, cy + ch);
            fill(0xFFFFFFFF);
            c.drawRoundRect(colorCells[i], 6 * u, 6 * u, paint);
            int[] p = PROFILES[i];
            float sw = (cw - 24 * u) / 3;
            for (int k = 0; k < 3; k++) {
                fill(p == null ? hsv(200 - k * 90, 1, 0.9f) : p[k]);
                c.drawRect(cx + 12 * u + k * sw, cy + 12 * u, cx + 12 * u + (k + 1) * sw, cy + 46 * u, paint);
            }
            text(c, COLORS[i] + (i == 0 ? " (engine)" : ""), cx + 12 * u, cy + 76 * u, 18 * u, XP_INK, Paint.Align.LEFT);
            stroke(i == color ? XP_BLUE : XP_EDGE, (i == color ? 4 : 1.5f) * u);
            c.drawRoundRect(colorCells[i], 6 * u, 6 * u, paint);
        }
    }

    private void presetsTab(Canvas c, float left, float top, float right, float u) {
        for (int i = 0; i < PRESETS; i++) {
            float y = top + i * 74 * u;
            text(c, "Preset " + (i + 1), left, y + 30 * u, 20 * u, XP_INK, Paint.Align.LEFT);
            text(c, presetInfo[i], left + 120 * u, y + 30 * u, 17 * u, 0xFF4B5563, Paint.Align.LEFT);
            xpButton(c, presetLoad[i], right - 110 * u, y, "Load", u);
            xpButton(c, presetSave[i], right - 230 * u, y, "Save", u);
        }
        text(c, "Save keeps the look, colours and every tune setting.", left, top + PRESETS * 74 * u + 20 * u,
                16 * u, 0xFF4B5563, Paint.Align.LEFT);
    }

    private void xpButton(Canvas c, RectF where, float x, float y, String label, float u) {
        where.set(x, y, x + 104 * u, y + 42 * u);
        fill(0xFFFFFFFF);
        c.drawRoundRect(where, 5 * u, 5 * u, paint);
        stroke(0xFF003C74, 1.5f * u);
        c.drawRoundRect(where, 5 * u, 5 * u, paint);
        text(c, label, where.centerX(), y + 28 * u, 18 * u, XP_INK, Paint.Align.CENTER);
    }

    private static RectF[] rects(int n) {
        RectF[] out = new RectF[n];
        for (int i = 0; i < n; i++) out[i] = new RectF();
        return out;
    }

    // ---- small helpers ----

    private void slider(Canvas c, int w, float u) {
        float x1 = w * 0.60f, x0 = Math.min(x1 - 120 * u, Math.max(w * 0.40f, tuneButton.right + 44 * u)), y = 39 * u;
        slider.set(x0 - 30 * u, y - 30 * u, x1 + 30 * u, y + 30 * u);
        int at = 0;
        for (int i = 0; i < COUNTS.length; i++) if (COUNTS[i] == shown) at = i;
        float knob = x0 + (x1 - x0) * at / (COUNTS.length - 1);
        stroke(0x55FFFFFF, 6 * u);
        paint.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(x0, y, x1, y, paint);
        stroke(0xFFFFFFFF, 6 * u);
        paint.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(x0, y, knob, y, paint);
        fill(0xFFFFFFFF);
        c.drawCircle(knob, y, 13 * u, paint);
        text(c, shown + " BARS", x1 + 26 * u, y + 7 * u, 20 * u, 0xCCFFFFFF, Paint.Align.LEFT);
    }

    /** A finger on the slider at x picks the nearest bar count. */
    public void slide(float x) {
        float t = clamp((x - (slider.left + slider.height() / 2)) / (slider.width() - slider.height()));
        shown = COUNTS[Math.round(t * (COUNTS.length - 1))];
    }

    // ---- the TUNE panel: one row per setting of the look on screen ----

    private void tunePanel(Canvas c, int w, float u) {
        VizTune.Knob[] knobs = VizTune.knobs(look);
        float rowH = 62 * u, pw = Math.min(w * 0.5f, 560 * u), x = dashButton.left, y = tuneButton.bottom + 12 * u;
        panel.set(x, y, x + pw, y + rowH * knobs.length + 78 * u);
        fill(0xE6101114);
        c.drawRoundRect(panel, 20 * u, 20 * u, paint);
        stroke(0x33FFFFFF, 1.5f * u);
        c.drawRoundRect(panel, 20 * u, 20 * u, paint);
        trackLeft = x + 26 * u;
        trackRight = x + pw - 26 * u;
        for (int i = 0; i < knobs.length && i < rows.length; i++) {
            VizTune.Knob k = knobs[i];
            float top = y + 12 * u + i * rowH;
            if (rows[i] == null) rows[i] = new RectF();
            rows[i].set(x, top, x + pw, top + rowH);
            text(c, k.label, trackLeft, top + 26 * u, 19 * u, 0xDDFFFFFF, Paint.Align.LEFT);
            if (k.toggle) {
                boolean on = tune.on(look, k.id);
                float sw = 62 * u, sh = 30 * u, sx = trackRight - sw, sy = top + 16 * u;
                fill(on ? pal[1] : 0x44FFFFFF);
                c.drawRoundRect(set(sx, sy, sx + sw, sy + sh), sh / 2, sh / 2, paint);
                fill(0xFFFFFFFF);
                c.drawCircle(on ? sx + sw - sh / 2 : sx + sh / 2, sy + sh / 2, sh * 0.38f, paint);
                continue;
            }
            float v = tune.get(look, k.id), ty = top + 46 * u, knob = trackLeft + (trackRight - trackLeft) * tune.at(look, k.id);
            String shows = VizTune.seconds(k.id) ? Math.round(v * 100) / 100f + " s" : Math.round(v * 100) + "%";
            text(c, shows, trackRight, top + 26 * u, 19 * u, 0x99FFFFFF, Paint.Align.RIGHT);
            stroke(0x44FFFFFF, 5 * u);
            paint.setStrokeCap(Paint.Cap.ROUND);
            c.drawLine(trackLeft, ty, trackRight, ty, paint);
            stroke(pal[1], 5 * u);
            paint.setStrokeCap(Paint.Cap.ROUND);
            c.drawLine(trackLeft, ty, knob, ty, paint);
            fill(0xFFFFFFFF);
            c.drawCircle(knob, ty, 11 * u, paint);
        }
        float by = panel.bottom - 58 * u;
        pill(c, resetButton, trackLeft, by, "RESET", u);
        text(c, LOOKS[look].toUpperCase() + "  \u00B7  tap outside to close", trackRight, by + 30 * u, 17 * u, 0x88FFFFFF, Paint.Align.RIGHT);
    }

    /** Which row of the open panel is at this point, or -1. */
    public int rowAt(float x, float y) {
        if (menuOpen && menuTab != TAB_LOOK && menuTab != TAB_EFFECTS) return -1;
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
