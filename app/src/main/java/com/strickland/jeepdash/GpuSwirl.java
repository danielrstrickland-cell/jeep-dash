package com.strickland.jeepdash;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.HardwareRenderer;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;

import com.strickland.jeepdash.core.VizTune;

/**
 * Swirl in the Windows XP media-player style, all on the graphics chip: every frame is the last one twisted
 * round the middle (more near the centre than at the edges), pushed a little outward and softened, so light
 * smokes away in spirals; on top, fresh glowing ribbons traced from the music, mirrored, in slowly drifting
 * colours. The feedback picture is kept in GPU memory (Android's hardware renderer drawing into an
 * ImageReader), so the main processor only records a few lines a frame. Android 13 and up.
 */
@TargetApi(Build.VERSION_CODES.TIRAMISU)
final class GpuSwirl implements VizPainter.Extra {
    static boolean supported() { return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU; }

    private static final String WARP = ""
            + "uniform shader prev;\n"
            + "uniform float2 size;\n"
            + "uniform float zoom;\n"
            + "uniform float twist;\n"
            + "uniform float turn;\n"
            + "uniform float keep;\n"
            + "half4 main(float2 p) {\n"
            + "    float2 c = size * 0.5;\n"
            + "    float2 d = p - c;\n"
            + "    float r = length(d) / min(size.x, size.y);\n"
            + "    float a = turn + twist * (1.0 - smoothstep(0.0, 0.75, r));\n"         // a swirl, strongest in the middle
            + "    float ca = cos(a);\n"
            + "    float sa = sin(a);\n"
            + "    float2 s = float2(ca * d.x + sa * d.y, -sa * d.x + ca * d.y) / zoom + c;\n"
            + "    half4 col = prev.eval(s) * 2.0 + prev.eval(s + float2(1.0, 0.0)) + prev.eval(s - float2(1.0, 0.0))\n"
            + "        + prev.eval(s + float2(0.0, 1.0)) + prev.eval(s - float2(0.0, 1.0));\n"   // softens into smoke
            + "    return half4(max(col.rgb / 6.0 * half(keep) - 0.004, half3(0.0)), 1.0);\n"
            + "}\n";

    private static final int SHRINK = 2;                         // the feedback picture is half the screen size
    private final RuntimeShader warp = new RuntimeShader(WARP);
    private final Paint warpPaint = new Paint(), line = new Paint(Paint.ANTI_ALIAS_FLAG), show = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path ribbon = new Path();
    private final RectF whole = new RectF();
    private HardwareRenderer renderer;
    private ImageReader reader;
    private final RenderNode root = new RenderNode("swirl-feedback");
    private Image older, newer;
    private Bitmap prev;
    private int bw, bh;
    private float angle, direction = 1, lastBeat, turnKick;
    private int beats;
    private boolean broken;

    /** False once the hardware feedback has failed on this device; the plain Swirl is used instead. */
    boolean working() { return !broken; }

    private void ensure(int w, int h) {
        int nw = Math.max(64, w / SHRINK), nh = Math.max(64, h / SHRINK);
        if (renderer != null && nw == bw && nh == bh) return;
        release();
        bw = nw;
        bh = nh;
        reader = ImageReader.newInstance(bw, bh, PixelFormat.RGBA_8888, 4,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
        renderer = new HardwareRenderer();
        renderer.setSurface(reader.getSurface());
        renderer.setContentRoot(root);
        renderer.setOpaque(true);
        root.setPosition(0, 0, bw, bh);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
    }

    void release() {
        if (renderer != null) renderer.destroy();
        renderer = null;
        if (older != null) older.close();
        if (newer != null) newer.close();
        older = newer = null;
        prev = null;
        if (reader != null) reader.close();
        reader = null;
    }

    /** A colour that drifts through the profile and back without a jump. */
    private static int drift(VizPainter p, float t) {
        t = t - (float) Math.floor(t);
        return p.color(1 - Math.abs(2 * t - 1));
    }

    /** A smooth closed ribbon round the middle, pushed out by the bands, mirrored left and right. */
    private void trace(VizPainter p, float spin, float scale, float seconds) {
        int half = Math.min(p.shown, 20), total = 2 * half;
        ribbon.rewind();
        float px = 0, py = 0, fx = 0, fy = 0;
        for (int i = 0; i <= total; i++) {
            int j = i % total, k = j < half ? j : total - 1 - j;
            float level = p.bar(k * p.shown / half);
            double a = spin + j * 2 * Math.PI / total;
            float r = scale * (0.09f + 0.22f * (float) Math.pow(level, 1.2) * (1 + 0.5f * p.beat)
                    + 0.01f * (float) Math.sin(seconds * 2.3 + j));
            float x = (float) Math.cos(a) * r, y = (float) Math.sin(a) * r;
            if (i == 0) {
                fx = x;
                fy = y;
                ribbon.moveTo(x, y);
            } else {
                ribbon.quadTo(px, py, (px + x) / 2, (py + y) / 2);       // smoothed through the midpoints
            }
            px = x;
            py = y;
        }
        ribbon.quadTo(px, py, fx, fy);
        ribbon.close();
    }

    @Override
    public void draw(Canvas screen, int w, int h, float dt, float seconds, VizPainter p) {
        try {
            ensure(w, h);
            boolean hit = p.beat > 0.9f && lastBeat <= 0.9f;
            lastBeat = p.beat;
            if (hit && ++beats % 6 == 0) direction = -direction;          // now and then the swirl turns round
            if (hit) turnKick = 1;
            turnKick = Math.max(0, turnKick - dt * 2.5f);
            float spin = p.tune.get(4, "spin"), frames = dt * 60;
            angle += direction * (0.3f + 1.4f * p.vu) * spin * dt;

            RecordingCanvas c = root.beginRecording(bw, bh);
            try {
                c.drawColor(0xFF000000);
                if (prev != null) {                                       // 1. last frame, swirled and faded
                    warp.setInputShader("prev", new BitmapShader(prev, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                    warp.setFloatUniform("size", bw, bh);
                    warp.setFloatUniform("zoom", 1 + (0.006f + 0.03f * p.bass + 0.04f * turnKick) * p.tune.get(4, "zoom") * frames);
                    warp.setFloatUniform("twist", direction * (0.02f + 0.05f * p.vu + 0.06f * turnKick) * spin * frames);
                    warp.setFloatUniform("turn", direction * 0.004f * spin * frames);
                    warp.setFloatUniform("keep", (float) Math.exp(-dt / p.tune.get(4, "trail")));
                    warpPaint.setShader(warp);
                    c.drawRect(0, 0, bw, bh, warpPaint);
                }
                float m = Math.min(bw, bh);                                // 2. fresh ribbons of light
                c.save();
                c.translate(bw / 2f, bh / 2f);
                c.scale(m, m);
                trace(p, angle, 1, seconds);
                line.setStrokeWidth(0.014f);
                line.setColor((drift(p, seconds * 0.07f) & 0x00FFFFFF) | 0x70000000);
                c.drawPath(ribbon, line);
                line.setStrokeWidth(0.004f);
                line.setColor(drift(p, seconds * 0.07f + 0.2f));
                c.drawPath(ribbon, line);
                trace(p, -angle * 1.3f + 1.1f, 0.62f, seconds + 4);
                line.setStrokeWidth(0.003f);
                line.setColor(drift(p, seconds * 0.05f + 0.55f));
                c.drawPath(ribbon, line);
                c.restore();
            } finally {
                root.endRecording();
            }
            renderer.createRenderRequest().syncAndDraw();
            Image img = reader.acquireLatestImage();
            if (img != null) {
                HardwareBuffer hb = img.getHardwareBuffer();
                Bitmap b = hb == null ? null : Bitmap.wrapHardwareBuffer(hb, ColorSpace.get(ColorSpace.Named.SRGB));
                if (hb != null) hb.close();
                if (b != null) {
                    if (older != null) older.close();                    // two frames back: no longer on screen
                    older = newer;
                    newer = img;
                    prev = b;
                } else {
                    img.close();
                }
            }
            screen.drawColor(0xFF000000);
            if (prev != null) {
                whole.set(0, 0, w, h);
                screen.drawBitmap(prev, null, whole, show);
            }
        } catch (RuntimeException e) {
            Diag.error(com.strickland.jeepdash.core.DiagLog.GFX, "swirl GPU feedback failed: " + e.getMessage());
            broken = true;
            release();
        }
    }
}
