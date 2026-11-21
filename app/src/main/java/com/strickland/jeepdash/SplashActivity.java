package com.strickland.jeepdash;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/**
 * Start-up sequence: a command-line boot in neon on a glowing grid, then the dash, whose own gauges
 * sweep once. Tap to skip.
 */
public class SplashActivity extends Activity {
    private static final int NEON = 0xFF6FE8FF, WHITE = 0xFFFFFFFF;
    private static final float CHARS_PER_SECOND = 120, HOLD = 0.45f;
    private boolean left;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(new Intro(this));
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) return;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController ic = getWindow().getInsetsController();
            if (ic != null) {
                ic.hide(WindowInsets.Type.systemBars());
                ic.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            hideBarsOld();
        }
    }

    @SuppressWarnings("deprecation")
    private void hideBarsOld() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
    }

    @SuppressWarnings("deprecation")
    private void openDash() {
        if (left) return;
        left = true;
        startActivity(new Intent(this, DashActivity.class).putExtra("sweep", true));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private String[] bootLines() {
        long build = 0;
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            build = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : buildOld(pi);
        } catch (Exception ignored) {
            // the number is decoration
        }
        return new String[]{
                "JEEP DASH  //  BUILD " + build,
                "> POWER ........................ OK",
                "> DISPLAY ...................... OK",
                "> ENGINE COMPUTER LINK ......... STANDBY",
                "> GAUGE CHANNELS ............... " + com.strickland.jeepdash.core.Catalog.all().size(),
                "> VISUALIZER ................... READY",
                "> STARTING DASH",
        };
    }

    @SuppressWarnings("deprecation")
    private static long buildOld(android.content.pm.PackageInfo pi) { return pi.versionCode; }

    private final class Intro extends View {
        private final Paint paint = new Paint();
        private final String[] lines = bootLines();
        private final float typed;                      // seconds until the last character is on screen
        private long start;

        Intro(Context context) {
            super(context);
            int chars = 0;
            for (String l : lines) chars += l.length();
            typed = 0.25f + chars / CHARS_PER_SECOND;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) openDash();
            return true;
        }

        @Override
        protected void onDraw(Canvas c) {
            if (start == 0) start = System.nanoTime();
            float t = (System.nanoTime() - start) / 1e9f, end = typed + HOLD;
            int w = getWidth(), h = getHeight();
            float u = h / 800f, out = 1 - ramp(t, end - 0.25f, end);
            c.drawColor(0xFF000000);

            // a floor of light running toward the horizon
            float horizon = h * 0.64f, cx = w / 2f, glow = ramp(t, 0.1f, 0.9f) * out;
            for (int i = -14; i <= 14; i++)
                line(c, cx + i * w * 0.012f, horizon, cx + i * w * 0.11f, h, 1.5f * u, NEON, 70 * glow);
            float roll = (t * 0.35f) % 1;
            for (int k = 0; k < 9; k++) {
                float d = (k + roll) / 9f, y = horizon + (h - horizon) * d * d;
                line(c, 0, y, w, y, 1.5f * u, NEON, 90 * glow * (0.25f + 0.75f * d));
            }
            line(c, 0, horizon, w, horizon, 3 * u, NEON, 200 * glow);

            // the boot text, typed out
            float budget = Math.max(0, (t - 0.25f) * CHARS_PER_SECOND), x = w * 0.07f, y = h * 0.17f;
            for (int i = 0; i < lines.length && budget > 0; i++) {
                String l = lines[i];
                int n = (int) Math.min(l.length(), budget);
                budget -= l.length();
                boolean last = budget <= 0;
                String cursor = last && (t * 3) % 1 < 0.6f ? "█" : "";
                text(c, l.substring(0, n) + cursor, x, y, (i == 0 ? 40 : 28) * u, i == 0 ? WHITE : NEON, 255 * out, u);
                y += (i == 0 ? 70 : 46) * u;
            }

            // the screen snaps shut on a line of light
            float snap = ramp(t, end - 0.25f, end);
            if (snap > 0) line(c, cx - w * 0.5f * (1 - snap), h / 2f, cx + w * 0.5f * (1 - snap), h / 2f, 4 * u, WHITE, 255);

            if (t >= end) openDash();
            else postInvalidateOnAnimation();
        }

        private void line(Canvas c, float x0, float y0, float x1, float y1, float width, int color, float alpha) {
            paint.reset();
            paint.setAntiAlias(true);
            paint.setColor(color);
            paint.setAlpha(Math.max(0, Math.min(255, (int) alpha)));
            paint.setStrokeWidth(width);
            c.drawLine(x0, y0, x1, y1, paint);
        }

        private void text(Canvas c, String s, float x, float baseline, float size, int color, float alpha, float u) {
            paint.reset();
            paint.setAntiAlias(true);
            paint.setColor(color);
            paint.setAlpha(Math.max(0, Math.min(255, (int) alpha)));
            paint.setTypeface(Typeface.MONOSPACE);
            paint.setTextSize(size);
            paint.setShadowLayer(14 * u, 0, 0, NEON);
            c.drawText(s, x, baseline, paint);
        }
    }

    static float ramp(float t, float from, float to) { return Math.max(0, Math.min(1, (t - from) / (to - from))); }
}
