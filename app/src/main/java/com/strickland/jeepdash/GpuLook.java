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
 * sets (full, 90% or 60%) and scaled up, and that resolution drops by itself when frames run slow and comes back when there is room,
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
    private float shown = 1;

    /** The share of full resolution the last picture was drawn at, for the graphics log. */
    float detailNow() { return shown; }

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
        adapt(dt, want, p.frameBudget, VizTune.floor(Math.round(p.tune.get(p.look, "priority"))));
        float scale = Math.min(want, auto);
        shown = scale;
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
     * Lowers the drawing size when pictures take longer than the Quality setting gives them, and raises it again
     * once there is room; never below the look's Priority floor (Sharp: not at all, Balance: 60%, Smooth: 30%).
     */
    private void adapt(float dt, float want, float budget, float floorShare) {
        if (dt > budget * 1.25f) {
            slowFor += dt;
            fastFor = 0;
        } else if (dt < budget * 1.1f) {
            fastFor += dt;
            slowFor = 0;
        }
        float lowest = Math.max(0.3f, want * floorShare);
        if (slowFor > 0.6f) {
            auto = Math.max(lowest, Math.min(auto, want) - 0.05f);
            slowFor = 0;
        }
        if (fastFor > 3 && auto < want) {
            auto = Math.min(want, auto + 0.05f);
            fastFor = 0;
        }
        auto = Math.max(auto, lowest);
    }
}
