package com.strickland.jeepdash;

import android.annotation.TargetApi;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.os.Build;

import com.strickland.jeepdash.core.VizTune;

/**
 * A look drawn entirely by the graphics chip from one shader. It is drawn at the resolution the Quality choice
 * sets (full, 85% or 60%) and scaled up; at Max quality the shader runs every other frame (30 a second) so it
 * has twice the time per picture, and that resolution drops by itself when frames run slow and comes back when there is room,
 * so the screen stays smooth. Every shader gets size and the colour profile as c0 (low), c1 (middle), c2
 * (high). Android 13 and up.
 */
@TargetApi(Build.VERSION_CODES.TIRAMISU)
abstract class GpuLook implements VizPainter.Extra {
    static boolean supported() { return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU; }

    static final String COMMON = ""
            + "uniform float2 size;\n"
            + "layout(color) uniform half4 c0;\n"
            + "layout(color) uniform half4 c1;\n"
            + "layout(color) uniform half4 c2;\n";

    protected final RuntimeShader shader;
    private final RenderNode node;
    private final Paint layer = new Paint(), fill = new Paint();
    private float auto = 1, slowFor, fastFor;
    private boolean skip;

    GpuLook(String name, String source) {
        shader = new RuntimeShader(COMMON + source);
        node = new RenderNode(name);
        layer.setFilterBitmap(true);
        node.setUseCompositingLayer(true, layer);
    }

    /** Sets this look's own uniforms; the picture it draws is lw by lh pixels. */
    protected abstract void uniforms(VizPainter p, int lw, int lh, float dt, float seconds);

    @Override
    public final void draw(Canvas c, int w, int h, float dt, float seconds, VizPainter p) {
        int quality = Math.round(p.tune.get(VizTune.GLOBAL, "quality"));
        float want = VizTune.detail(quality);
        adapt(dt, want, quality != VizTune.SMOOTH_90);
        skip = quality == VizTune.MAX_QUALITY && !skip;               // Max quality: a new picture every other frame
        if (skip && node.hasDisplayList()) {
            uniforms(p, node.getWidth(), node.getHeight(), dt, seconds); // the look still moves on in time
            show(c, w, h);
            overlay(c, w, h, p);
            return;
        }
        float scale = Math.min(want, auto);
        int lw = Math.max(64, Math.round(w * scale)), lh = Math.max(64, Math.round(h * scale));
        shader.setFloatUniform("size", lw, lh);
        shader.setColorUniform("c0", p.color(0));
        shader.setColorUniform("c1", p.color(0.5f));
        shader.setColorUniform("c2", p.color(1));
        uniforms(p, lw, lh, dt, seconds);
        node.setPosition(0, 0, lw, lh);
        RecordingCanvas rc = node.beginRecording(lw, lh);
        try {
            fill.setShader(shader);
            rc.drawRect(0, 0, lw, lh, fill);
        } finally {
            node.endRecording();
        }
        show(c, w, h);
        overlay(c, w, h, p);
    }

    /** Anything drawn crisp over the shader picture at full resolution (a car, a readout); nothing by default. */
    protected void overlay(Canvas c, int w, int h, VizPainter p) { }

    /** Draws the last picture, stretched to the screen. */
    private void show(Canvas c, int w, int h) {
        int saved = c.save();
        c.scale(w / (float) node.getWidth(), h / (float) node.getHeight());
        c.drawRenderNode(node);
        c.restoreToCount(saved);
    }

    /**
     * Lowers the drawing size when frames run slow and raises it again once there is room, judged against the
     * frame rate in use (60 when capped, else the screen's fastest).
     */
    private void adapt(float dt, float want, boolean capped) {
        float frame = capped ? 1 / 60f : 1 / 90f;
        if (dt > frame * 1.3f) {
            slowFor += dt;
            fastFor = 0;
        } else if (dt < frame * 1.1f) {
            fastFor += dt;
            slowFor = 0;
        }
        if (slowFor > 0.6f) {
            auto = Math.max(0.3f, Math.min(auto, want) - 0.05f);
            slowFor = 0;
        }
        if (fastFor > 3 && auto < want) {
            auto = Math.min(want, auto + 0.05f);
            fastFor = 0;
        }
    }
}
