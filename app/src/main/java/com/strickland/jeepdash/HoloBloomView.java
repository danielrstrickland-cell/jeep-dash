package com.strickland.jeepdash;

import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

/**
 * The Holo look's soft bloom: the bright lines drawn again at reduced resolution, blurred once by the GPU and
 * added over everything (High: one pass; Ultra adds a second, wider one). Android 12 and later; touches pass through.
 */
final class HoloBloomView extends View {
    private final DashView dash;
    private final float density, scale;

    /** @param scale the layer's resolution against the screen (0.5 = half) @param blurPx the blur in the layer's pixels */
    HoloBloomView(Context c, DashView dash, float scale, float blurPx, int alpha) {
        super(c);
        this.dash = dash;
        this.scale = scale;
        this.density = c.getResources().getDisplayMetrics().density;
        setPivotX(0);
        setPivotY(0);
        setScaleX(1 / scale);
        setScaleY(1 / scale);
        setClickable(false);
        setFocusable(false);
        if (Build.VERSION.SDK_INT >= 31) {
            Paint p = new Paint();
            p.setBlendMode(BlendMode.PLUS);
            p.setAlpha(alpha);
            setLayerType(LAYER_TYPE_HARDWARE, p);
            setRenderEffect(RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.DECAL));
        }
    }

    static boolean supported() { return Build.VERSION.SDK_INT >= 31; }

    float scale() { return scale; }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) { return false; }

    @Override
    protected void onDraw(Canvas c) {
        c.save();
        c.scale(density * scale, density * scale);
        dash.drawHoloGlow(c);
        c.restore();
    }
}
