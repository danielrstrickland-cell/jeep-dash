package com.strickland.jeepdash;

import android.annotation.TargetApi;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;

import com.strickland.jeepdash.core.VizTune;

/**
 * Runs the visualizer picture through the tablet's graphics chip: a glow (bloom) where it is bright, and an
 * optional old-TV filter (curved glass, scanlines, colour fringes). The picture is recorded once per frame and
 * the effects are applied on the GPU, so the main processor does no extra work. Needs Android 12 for the glow
 * and Android 13 for the CRT filter; older devices draw the plain picture.
 */
@TargetApi(Build.VERSION_CODES.TIRAMISU)
final class VizEffects {
    /** True when this device can run the effects at all. */
    static boolean supported() { return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S; }

    static final String CRT = ""
            + "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float amount;\n"
            + "half4 main(float2 p) {\n"
            + "    float2 c = p / size * 2.0 - 1.0;\n"
            + "    c *= 1.0 + amount * 0.06 * dot(c, c);\n"                       // curved glass
            + "    float2 q = (c * 0.5 + 0.5) * size;\n"
            + "    if (q.x < 0.0 || q.y < 0.0 || q.x > size.x || q.y > size.y) return half4(0.0, 0.0, 0.0, 1.0);\n"
            + "    float shift = amount * size.x * 0.0012;\n"                    // colour fringes
            + "    half3 col = half3(content.eval(q + float2(shift, 0.0)).r, content.eval(q).g,\n"
            + "                      content.eval(q - float2(shift, 0.0)).b);\n"
            + "    float period = max(3.0, size.y / 320.0);\n"                    // scanlines
            + "    float scan = 0.5 + 0.5 * cos(6.2831853 * q.y / period);\n"
            + "    col *= half(1.0 - amount * 0.35 * scan);\n"
            + "    float edge = dot(c, c);\n"                                     // darker corners
            + "    col *= half((1.0 - amount * 0.25 * edge * edge) * (1.0 + amount * 0.12));\n"
            + "    return half4(col, 1.0);\n"
            + "}\n";

    private final RenderNode scene = new RenderNode("viz-scene");
    private final RenderNode glow = new RenderNode("viz-glow");    // the glow, worked out at quarter size
    private final RenderNode out = new RenderNode("viz-out");      // picture plus glow, for the CRT filter
    private RuntimeShader crt;
    private float madeBloom = -1, madeCrt = -1;
    private int madeW, madeH;

    VizEffects() {
        Paint add = new Paint();
        add.setBlendMode(BlendMode.PLUS);                             // the glow is added on top of the picture
        add.setFilterBitmap(true);
        glow.setUseCompositingLayer(true, add);
    }

    /**
     * Draws the picture with its effects onto a hardware canvas. The glow is blurred from a quarter-size copy
     * of the picture (a sixteenth of the pixels) and stretched back up on top, which looks the same as a
     * full-size blur at a fraction of the work.
     */
    void draw(Canvas screen, VizPainter p, int w, int h, float dt, float seconds) {
        scene.setPosition(0, 0, w, h);
        RecordingCanvas rc = scene.beginRecording(w, h);
        try {
            p.drawScene(rc, w, h, dt, seconds);
        } finally {
            scene.endRecording();
        }
        float bloom = step(p.tune.get(VizTune.GLOBAL, "bloom"));
        float tv = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? step(p.tune.get(VizTune.GLOBAL, "crt")) : 0;
        int gw = Math.max(16, w / 4), gh = Math.max(16, h / 4);
        if (bloom != madeBloom || tv != madeCrt || w != madeW || h != madeH) {
            glow.setRenderEffect(bloom > 0 ? glowEffect(bloom, gw, gh) : null);
            out.setRenderEffect(tv > 0 ? crtEffect(tv, w, h) : null);
            madeBloom = bloom;
            madeCrt = tv;
            madeW = w;
            madeH = h;
        }
        if (bloom > 0) {
            glow.setPosition(0, 0, gw, gh);
            RecordingCanvas g = glow.beginRecording(gw, gh);
            try {
                g.scale(gw / (float) w, gh / (float) h);
                g.drawRenderNode(scene);
            } finally {
                glow.endRecording();
            }
        }
        Canvas target = screen;
        if (tv > 0) {
            out.setPosition(0, 0, w, h);
            target = out.beginRecording(w, h);
        }
        try {
            target.drawRenderNode(scene);
            if (bloom > 0) {
                int saved = target.save();
                target.scale(w / (float) gw, h / (float) gh);
                target.drawRenderNode(glow);
                target.restoreToCount(saved);
            }
        } finally {
            if (tv > 0) out.endRecording();
        }
        if (tv > 0) screen.drawRenderNode(out);
    }

    /** Settings move in small steps, so dragging a slider does not rebuild the effect every frame. */
    private static float step(float v) { return Math.round(v * 20) / 20f; }

    /** Keeps only what is brighter than about 40%, then blurs it wide. */
    private static RenderEffect glowEffect(float bloom, int gw, int gh) {
        float k = 1.6f * bloom, off = -k * 0.4f * 255;
        ColorMatrix bright = new ColorMatrix(new float[]{
                k, 0, 0, 0, off,
                0, k, 0, 0, off,
                0, 0, k, 0, off,
                0, 0, 0, 1, 0});
        float r = Math.max(2, Math.min(gw, gh) * 0.035f);
        return RenderEffect.createBlurEffect(r, r,
                RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(bright)), Shader.TileMode.DECAL);
    }

    private RenderEffect crtEffect(float tv, int w, int h) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null;
        if (crt == null) crt = new RuntimeShader(CRT);
        crt.setFloatUniform("size", w, h);
        crt.setFloatUniform("amount", tv);
        return RenderEffect.createRuntimeShaderEffect(crt, "content");
    }
}
