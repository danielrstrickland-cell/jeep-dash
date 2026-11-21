package com.strickland.jeepdash;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.Random;

/**
 * The swirling light-show look with a ferrofluid heart. Every frame is the last one drawn a little larger,
 * turned and dimmer, so light leaves trails that spiral outward. The light comes from a glossy black blob
 * in the middle whose spikes shoot out with the music; bass hits kick the zoom, flip the spin now and then,
 * send out a shock ring and throw droplets that fall back into the blob.
 */
final class SwirlLook implements VizPainter.Extra {
    private static final int SHRINK = 3;                 // the trails are drawn at a third of the screen size
    private static final float TRAIL_SECONDS = 0.55f;
    private static final int DROPS = 28;

    private Bitmap front, back;
    private Canvas frontCanvas, backCanvas;
    private final Paint copy = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint show = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ColorMatrix fade = new ColorMatrix();
    private final float[] fadeValues = new float[20];
    private final RectF whole = new RectF();
    private final Path blob = new Path();
    private final Random random = new Random(7);

    // everything below is in "blob units": 1 = the shorter side of the picture, 0,0 = its centre
    private final float[] dropX = new float[DROPS], dropY = new float[DROPS], dropVx = new float[DROPS],
            dropVy = new float[DROPS];
    private final boolean[] dropLive = new boolean[DROPS];
    private float angle, direction = 1, lastBeat, shock = -1, body;
    private int beats;

    private void ensure(int w, int h) {
        int bw = Math.max(64, w / SHRINK), bh = Math.max(64, h / SHRINK);
        if (front != null && front.getWidth() == bw && front.getHeight() == bh) return;
        front = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        back = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        frontCanvas = new Canvas(front);
        backCanvas = new Canvas(back);
        frontCanvas.drawColor(0xFF000000);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeJoin(Paint.Join.ROUND);
    }

    /** A colour that drifts through the profile and back without a jump. */
    private static int drift(VizPainter p, float t) {
        t = t - (float) Math.floor(t);
        return p.color(1 - Math.abs(2 * t - 1));
    }

    /** The blob's outline: a ring of pointed spikes, one per band, mirrored left and right. */
    private void shape(VizPainter p, float seconds) {
        int half = Math.min(p.shown, 24), total = 2 * half;
        double step = 2 * Math.PI / total;
        blob.rewind();
        for (int i = 0; i < total; i++) {
            int k = i < half ? i : total - 1 - i;
            float level = p.bar(k * p.shown / half);
            float len = (float) Math.pow(level, 1.3) * 0.22f * (1 + 0.6f * p.beat)
                    + 0.012f * (1 + (float) Math.sin(seconds * 3.1 + i * 1.7));
            double a0 = angle + i * step, a1 = a0 + step, tip = a0 + step / 2;
            float valley = body * 0.98f, shoulder = body + len * 0.12f;
            if (i == 0) blob.moveTo((float) Math.cos(a0) * valley, (float) Math.sin(a0) * valley);
            double c0 = a0 * 0.35 + tip * 0.65, c1 = a1 * 0.35 + tip * 0.65;
            blob.quadTo((float) Math.cos(c0) * shoulder, (float) Math.sin(c0) * shoulder,
                    (float) Math.cos(tip) * (body + len), (float) Math.sin(tip) * (body + len));
            blob.quadTo((float) Math.cos(c1) * shoulder, (float) Math.sin(c1) * shoulder,
                    (float) Math.cos(a1) * valley, (float) Math.sin(a1) * valley);
        }
        blob.close();
    }

    private void move(VizPainter p, float dt) {
        body = 0.13f * (1 + 0.25f * p.bass + 0.15f * p.beat);
        boolean hit = p.beat > 0.9f && lastBeat <= 0.9f;
        lastBeat = p.beat;
        if (hit) {
            if (++beats % 4 == 0) direction = -direction;             // every fourth hit the spin turns round
            shock = body;
            for (int n = 0, i = 0; i < DROPS && n < 7; i++) {
                if (dropLive[i]) continue;
                double a = random.nextDouble() * 2 * Math.PI;
                float speed = 0.55f + random.nextFloat() * 0.5f;
                dropX[i] = (float) Math.cos(a) * body;
                dropY[i] = (float) Math.sin(a) * body;
                dropVx[i] = (float) Math.cos(a) * speed - (float) Math.sin(a) * 0.25f * direction;
                dropVy[i] = (float) Math.sin(a) * speed + (float) Math.cos(a) * 0.25f * direction;
                dropLive[i] = true;
                n++;
            }
        }
        angle += direction * (0.25f + 1.6f * p.vu + 1.2f * p.beat) * dt;
        if (shock >= 0 && (shock += 0.9f * dt) > 0.9f) shock = -1;
        for (int i = 0; i < DROPS; i++) {
            if (!dropLive[i]) continue;
            float r = (float) Math.hypot(dropX[i], dropY[i]);
            if (r < body * 0.9f || r > 1.2f) {                       // back in the blob, or off the picture
                dropLive[i] = false;
                continue;
            }
            dropVx[i] -= dropX[i] / r * 1.5f * dt;                    // the blob pulls its droplets home
            dropVy[i] -= dropY[i] / r * 1.5f * dt;
            dropX[i] += dropVx[i] * dt;
            dropY[i] += dropVy[i] * dt;
        }
    }

    @Override
    public void draw(Canvas screen, int w, int h, float dt, float seconds, VizPainter p) {
        ensure(w, h);
        move(p, dt);
        shape(p, seconds);
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
        float zoom = 1 + (0.012f + 0.05f * p.bass + 0.06f * p.beat) * frames;
        float turn = direction * (0.35f + 1.2f * p.vu + 2.5f * p.beat) * frames;
        c.drawColor(0xFF000000);
        c.save();
        c.translate(cx, cy);
        c.scale(zoom, zoom);
        c.rotate(turn);
        c.translate(-cx, -cy);
        c.drawBitmap(front, 0, 0, copy);
        c.restore();

        // 2. new light, drawn in blob units: the glowing outline of the spikes, the shock ring, the droplets
        c.save();
        c.translate(cx, cy);
        c.scale(m, m);
        line.setStrokeWidth(0.010f);
        line.setColor(drift(p, seconds * 0.09f));
        c.drawPath(blob, line);
        line.setStrokeWidth(0.004f);
        line.setColor(drift(p, seconds * 0.09f + 0.33f));
        c.drawPath(blob, line);
        if (shock >= 0) {
            line.setStrokeWidth(0.012f * (1 - shock));
            line.setColor(p.accent());
            c.drawCircle(0, 0, shock, line);
        }
        fill.setShader(null);
        for (int i = 0; i < DROPS; i++) {
            if (!dropLive[i]) continue;
            fill.setColor(drift(p, seconds * 0.11f + i * 0.13f));
            c.drawCircle(dropX[i], dropY[i], 0.011f, fill);
        }
        c.restore();

        // 3. swap, show the trails, then the black blob itself, sharp, on top
        Bitmap b0 = front;
        front = back;
        back = b0;
        Canvas c0 = frontCanvas;
        frontCanvas = backCanvas;
        backCanvas = c0;
        whole.set(0, 0, w, h);
        screen.drawBitmap(front, null, whole, show);

        float ms = Math.min(w, h);
        screen.save();
        screen.translate(w / 2f, h / 2f);
        screen.scale(ms, ms);
        fill.setShader(null);
        fill.setColor(0xFF040405);
        screen.drawPath(blob, fill);
        for (int i = 0; i < DROPS; i++) if (dropLive[i]) screen.drawCircle(dropX[i], dropY[i], 0.008f, fill);
        line.setStrokeWidth(0.0025f);
        line.setColor(drift(p, seconds * 0.09f));
        screen.drawPath(blob, line);
        // the wet shine on the body
        float gx = -body * 0.32f, gy = -body * 0.36f;
        fill.setShader(new RadialGradient(gx, gy, body * 0.62f, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        screen.drawCircle(gx, gy, body * 0.62f, fill);
        fill.setShader(null);
        screen.restore();
    }
}
