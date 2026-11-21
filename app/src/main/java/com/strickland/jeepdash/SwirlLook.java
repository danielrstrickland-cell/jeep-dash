package com.strickland.jeepdash;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * The swirling light-show look, in the spirit of the turn-of-the-century media player visualizers: every
 * frame is the last one drawn a little larger, a little turned and a little dimmer, with new light from
 * the music laid on top, so shapes leave trails that spiral outward. Bass pushes the zoom and the spin.
 */
final class SwirlLook implements VizPainter.Extra {
    private static final int SHRINK = 3;                 // the trails are drawn at a third of the screen size
    private static final float TRAIL_SECONDS = 0.5f;

    private Bitmap front, back;
    private Canvas frontCanvas, backCanvas;
    private final Paint copy = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint show = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ColorMatrix fade = new ColorMatrix();
    private final float[] fadeValues = new float[20];
    private final RectF whole = new RectF();

    private void ensure(int w, int h) {
        int bw = Math.max(64, w / SHRINK), bh = Math.max(64, h / SHRINK);
        if (front != null && front.getWidth() == bw && front.getHeight() == bh) return;
        front = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        back = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        frontCanvas = new Canvas(front);
        backCanvas = new Canvas(back);
        frontCanvas.drawColor(0xFF000000);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
    }

    /** A colour that drifts through the profile and back without a jump. */
    private static int drift(VizPainter p, float t) {
        t = t - (float) Math.floor(t);
        return p.color(1 - Math.abs(2 * t - 1));
    }

    @Override
    public void draw(Canvas screen, int w, int h, float dt, float seconds, VizPainter p) {
        ensure(w, h);
        int bw = front.getWidth(), bh = front.getHeight();
        float cx = bw / 2f, cy = bh / 2f, m = Math.min(bw, bh), frames = dt * 60;
        Canvas c = backCanvas;

        // 1. the last frame: a little larger, a little turned, a little dimmer
        float keep = (float) Math.exp(-dt / TRAIL_SECONDS);
        for (int i = 0; i < 20; i++) fadeValues[i] = 0;
        fadeValues[0] = fadeValues[6] = fadeValues[12] = keep;
        fadeValues[4] = fadeValues[9] = fadeValues[14] = -2;          // so dim trails reach black
        fadeValues[18] = 1;
        fade.set(fadeValues);
        copy.setColorFilter(new ColorMatrixColorFilter(fade));
        float zoom = 1 + (0.010f + 0.035f * p.bass + 0.03f * p.beat) * frames;
        float turn = (float) (0.25 + 0.5 * Math.sin(seconds * 0.21)) * (1 + 2 * p.beat) * frames;
        c.drawColor(0xFF000000);
        c.save();
        c.translate(cx, cy);
        c.scale(zoom, zoom);
        c.rotate(turn);
        c.translate(-cx, -cy);
        c.drawBitmap(front, 0, 0, copy);
        c.restore();

        // 2. new light: the spectrum bent into a closed, slowly spinning ring
        int n = p.shown, steps = 2 * n;
        float spin = seconds * 0.35f, px = 0, py = 0;
        line.setStrokeWidth(Math.max(1.5f, m * 0.006f));
        for (int i = 0; i <= steps; i++) {
            int k = i % steps, b = k < n ? k : steps - 1 - k;
            double a = spin + k * Math.PI / n;
            float r = m * (0.10f + 0.05f * p.bass) + p.bar(b) * m * 0.30f;
            float x = cx + (float) Math.cos(a) * r, y = cy + (float) Math.sin(a) * r;
            if (i > 0) {
                line.setColor(drift(p, seconds * 0.08f + (float) b / n));
                c.drawLine(px, py, x, y, line);
            }
            px = x;
            py = y;
        }
        // three comets circling the ring, growing with the loudness
        for (int j = 0; j < 3; j++) {
            double a = -seconds * (0.9 + 0.3 * j) + j * 2.094;
            float r = m * (0.36f + 0.08f * (float) Math.sin(seconds * 0.7 + j));
            dot.setColor(drift(p, seconds * 0.11f + j / 3f));
            c.drawCircle(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r, m * (0.008f + 0.02f * p.vu), dot);
        }
        // a flash from the middle on every bass hit
        if (p.beat > 0.85f) {
            line.setColor(p.accent());
            line.setStrokeWidth(m * 0.012f);
            c.drawCircle(cx, cy, m * 0.09f, line);
        }

        // 3. swap and show
        Bitmap b0 = front;
        front = back;
        back = b0;
        Canvas c0 = frontCanvas;
        frontCanvas = backCanvas;
        backCanvas = c0;
        whole.set(0, 0, w, h);
        screen.drawBitmap(front, null, whole, show);
    }
}
