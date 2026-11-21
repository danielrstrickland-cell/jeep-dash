package com.strickland.jeepdash;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import com.strickland.jeepdash.core.LiveValues;
import com.strickland.jeepdash.theme.RetroBoot;
import com.strickland.jeepdash.theme.GtBoot;
import com.strickland.jeepdash.theme.BootArt;
import com.strickland.jeepdash.core.Catalog;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;

/**
 * Start-up sequence: a command-line boot in neon on a glowing grid. Every line is real: it waits for the
 * dongle to connect and for the engine computer to answer (key on), prints what the engine computer says
 * about the battery and temperatures, says the Jeep is ready to start, then opens the dash, whose gauges
 * sweep once. Anything that stays silent is reported and the dash opens anyway. Tap to skip.
 */
public class SplashActivity extends Activity implements ServiceConnection, LinkService.Listener {
    private static final int NEON = 0xFF6FE8FF, WHITE = 0xFFFFFFFF, AMBER = 0xFFFFB02E;
    private static final float CHARS_PER_SECOND = 120, LINK_WAIT = 12, ENGINE_WAIT = 12, HOLD = 1.1f;
    private static final float READOUT_WAIT = 3;        // for all the readouts together, from the engine's answer
    private static final int LOW_BATTERY_TENTHS = 118;  // under 11.8 V, as shown, is flagged
    private static final int TITLE = 0, LINK = 1, ENGINE = 2, READOUT = 3, VERDICT = 4;
    private static final String[] LABEL = {
            "JEEP DASH  //  BUILD ",
            "> DONGLE LINK .................. ",
            "> ENGINE COMPUTER .............. ",
            "> BATTERY ...................... ",
            "> COOLANT ...................... ",
            "> INTAKE AIR ................... ",
            "> ",
    };
    private static final int[] KIND = {TITLE, LINK, ENGINE, READOUT, READOUT, READOUT, VERDICT};
    /** The value each readout line prints. */
    private static final String[] VALUE = {null, null, null, "batt_v", "coolant", "iat", null};
    private static final int LINK_LINE = 1, ENGINE_LINE = 2;

    private boolean left, bound;
    private LinkService service;
    private volatile LinkService.Status status = LinkService.Status.SEARCHING;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(Catalog.porsche() ? new CarBoot(this) : new Intro(this));
    }

    @Override
    protected void onStart() {
        super.onStart();
        try {
            startService(new Intent(this, LinkService.class));
        } catch (RuntimeException ignored) {
            // not allowed right now; the checks will report no answer
        }
        bound = bindService(new Intent(this, LinkService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        if (service != null) service.removeListener(this);
        service = null;
        if (bound) {
            unbindService(this);
            bound = false;
        }
        super.onStop();
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((LinkService.LocalBinder) binder).service();
        // rpm shows the engine computer is answering; the rest are the readouts
        service.setActiveParams(new LinkedHashSet<>(Arrays.asList("rpm", "batt_v", "coolant", Catalog.porsche() ? "oil_temp" : "iat")));
        service.addListener(this);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) { service = null; }

    @Override
    public void onValues(LiveValues v) { }

    @Override
    public void onStatus(LinkService.Status s, String detail) { status = s; }

    private boolean linked() {
        return status == LinkService.Status.LIVE || status == LinkService.Status.SETTING_UP;
    }

    /** The latest reading of one value from the engine computer, or null while there is none. */
    private Double reading(String id) {
        LinkService s = service;
        return s != null && linked() ? s.values().get(id, LinkService.now()) : null;
    }

    /** A real reading has come back from the engine computer (0 rpm with the key on counts). */
    private boolean engineAnswers() { return reading("rpm") != null; }

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

    @SuppressWarnings("deprecation")
    private long build() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private final class Intro extends View {
        private final Paint paint = new Paint();
        private final String[] result = new String[LABEL.length];
        private final boolean[] good = new boolean[LABEL.length];
        private int at;                         // the line being typed or waited on
        private float lineStart = 0.25f, doneAt = -1;
        private float engineAt;                 // when the engine computer line was settled
        private long start;

        Intro(Context context) { super(context); }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) openDash();
            return true;
        }

        /** The word that ends line i, or null while its check is still running. */
        private String resolve(int i, float t, float waited) {
            good[i] = true;
            switch (KIND[i]) {
                case LINK:
                    if (linked()) return "OK";
                    good[i] = false;
                    if (status == LinkService.Status.NO_PERMISSION) return "NOT ALLOWED YET";
                    return waited > LINK_WAIT ? "NO ANSWER" : null;
                case ENGINE:
                    engineAt = t;
                    if (engineAnswers()) return "OK";
                    good[i] = false;
                    if (!good[LINK_LINE]) return "SKIPPED";
                    return waited > ENGINE_WAIT ? "NO ANSWER" : null;
                case READOUT:
                    return readout(i, t);
                case VERDICT:
                    // a low battery or a missing readout is on screen already; it does not hold the dash back
                    good[i] = good[LINK_LINE] && good[ENGINE_LINE];
                    return good[i] ? "ALL SYSTEMS GO  //  START ENGINE" : "OPENING DASH  //  CHECK THE LINK";
                default:
                    return String.valueOf(build());
            }
        }

        /** A readout line: the value once it has been read, or why there is none. */
        private String readout(int i, float t) {
            good[i] = false;
            if (!good[ENGINE_LINE]) return "SKIPPED";
            Double v = reading(VALUE[i]);
            if (v == null) return t - engineAt > READOUT_WAIT ? "NO ANSWER" : null;
            if (!"batt_v".equals(VALUE[i])) {
                good[i] = true;
                return Math.round(v) + "°F";
            }
            long tenths = Math.round(v * 10);   // judged as printed, so "11.8 V" is never also "LOW"
            good[i] = tenths >= LOW_BATTERY_TENTHS;
            return String.format(Locale.US, "%.1f V", tenths / 10.0) + (good[i] ? "" : " LOW");
        }

        private String waiting(int i, float t) {
            String dots = "...".substring(0, 1 + (int) (t * 3) % 3);
            if (KIND[i] == LINK) return status == LinkService.Status.SEARCHING ? "SEARCHING" + dots : "CONNECTING" + dots;
            if (KIND[i] == ENGINE) return "WAITING  //  TURN THE KEY ON" + dots;
            return KIND[i] == READOUT ? "READING" + dots : "";
        }

        @Override
        protected void onDraw(Canvas c) {
            if (start == 0) start = System.nanoTime();
            float t = (System.nanoTime() - start) / 1e9f;
            int w = getWidth(), h = getHeight();
            float u = h / 800f, cx = w / 2f;

            while (at < LABEL.length) {         // finish every line whose typing and check are both done
                float typedAt = lineStart + LABEL[at].length() / CHARS_PER_SECOND;
                String r = t < typedAt ? null : resolve(at, t, t - typedAt);
                if (r == null) break;
                result[at++] = r;
                lineStart = t;
            }
            if (at == LABEL.length && doneAt < 0) doneAt = t;
            float out = doneAt < 0 ? 1 : 1 - ramp(t, doneAt + HOLD - 0.25f, doneAt + HOLD);
            c.drawColor(0xFF000000);

            // a floor of light running toward the horizon
            float horizon = h * 0.64f, glow = ramp(t, 0.1f, 0.9f) * out;
            for (int i = -14; i <= 14; i++)
                line(c, cx + i * w * 0.012f, horizon, cx + i * w * 0.11f, h, 1.5f * u, NEON, 70 * glow);
            float roll = (t * 0.35f) % 1;
            for (int k = 0; k < 9; k++) {
                float d = (k + roll) / 9f, y = horizon + (h - horizon) * d * d;
                line(c, 0, y, w, y, 1.5f * u, NEON, 90 * glow * (0.25f + 0.75f * d));
            }
            line(c, 0, horizon, w, horizon, 3 * u, NEON, 200 * glow);

            // the boot text: finished lines with their results, then the one in progress
            float x = w * 0.07f, y = h * 0.17f;
            String cursor = (t * 3) % 1 < 0.6f ? "█" : "";
            for (int i = 0; i <= at && i < LABEL.length; i++) {
                float size = (i == 0 ? 40 : 28) * u;
                boolean done = i < at;
                int n = done ? LABEL[i].length() : (int) Math.max(0, Math.min(LABEL[i].length(), (t - lineStart) * CHARS_PER_SECOND));
                String tail = done ? result[i] : n == LABEL[i].length() ? waiting(i, t) + cursor : cursor;
                int ink = i == 0 ? WHITE : NEON;
                float tx = text(c, LABEL[i].substring(0, n), x, y, size, ink, 255 * out, u);
                text(c, tail, tx, y, size, !done ? ink : good[i] ? WHITE : AMBER, 255 * out, u);
                y += (i == 0 ? 70 : 46) * u;
            }

            // the screen snaps shut on a line of light
            if (out < 1) line(c, cx - w * 0.5f * out, h / 2f, cx + w * 0.5f * out, h / 2f, 4 * u, WHITE, 255);

            if (doneAt >= 0 && t >= doneAt + HOLD) openDash();
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

        /** Draws glowing terminal text and returns where it ends, so a result can follow in another colour. */
        private float text(Canvas c, String s, float x, float baseline, float size, int color, float alpha, float u) {
            paint.reset();
            paint.setAntiAlias(true);
            paint.setColor(color);
            paint.setAlpha(Math.max(0, Math.min(255, (int) alpha)));
            paint.setTypeface(Typeface.MONOSPACE);
            paint.setTextSize(size);
            paint.setShadowLayer(14 * u, 0, 0, color);
            c.drawText(s, x, baseline, paint);
            return x + paint.measureText(s);
        }
    }

    /**
     * The Porsche's start-up: the same real checks as the Jeep's, drawn in the style of whichever look the
     * Cayenne's dash is set to.
     */
    private final class CarBoot extends View {
        private final String[] label = {"Dongle link", "Engine computer", "Battery", "Coolant", "Oil temp"};
        private final String[] value = {null, null, "batt_v", "coolant", "oil_temp"};
        private final String[] result = new String[label.length];
        private final int[] state = new int[label.length];
        private final BootArt art;
        private int at;                         // the check being waited on
        private float lineStart = 1.5f, engineAt, doneAt = -1;
        private boolean linkGood, engineGood;
        private long start;

        CarBoot(Context context) {
            super(context);
            String look = context.getSharedPreferences(DashApp.PREFS, MODE_PRIVATE).getString("look_p", "p_retro");
            art = "p_gt".equals(look) ? new GtBoot() : new RetroBoot();
            Arrays.fill(state, BootArt.NOT_YET);
            Arrays.fill(result, "");
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) openDash();
            return true;
        }

        /** Settles check i if it can be; false while it is still waiting. */
        private boolean settle(int i, float t, float waited) {
            String r;
            boolean good = true;
            if (i == 0) {
                good = linked();
                r = good ? "OK" : status == LinkService.Status.NO_PERMISSION ? "NOT ALLOWED YET" : waited > LINK_WAIT ? "NO ANSWER" : null;
                linkGood = good;
            } else if (i == 1) {
                engineAt = t;
                good = engineAnswers();
                r = good ? "OK" : !linkGood ? "SKIPPED" : waited > ENGINE_WAIT ? "NO ANSWER" : null;
                engineGood = good;
                if (r == null) result[i] = "TURN THE KEY ON";
            } else {
                Double v = engineGood ? reading(value[i]) : null;
                if (!engineGood) {
                    r = "SKIPPED";
                    good = false;
                } else if (v == null) {
                    r = t - engineAt > READOUT_WAIT ? "NO ANSWER" : null;
                    good = false;
                } else if ("batt_v".equals(value[i])) {
                    long tenths = Math.round(v * 10);
                    good = tenths >= LOW_BATTERY_TENTHS;
                    r = String.format(Locale.US, "%.1f V", tenths / 10.0) + (good ? "" : " LOW");
                } else {
                    r = Math.round(v) + " °F";
                }
            }
            if (r == null) return false;
            result[i] = r;
            state[i] = good ? BootArt.GOOD : BootArt.BAD;
            return true;
        }

        @Override
        protected void onDraw(Canvas c) {
            if (start == 0) start = System.nanoTime();
            float t = (System.nanoTime() - start) / 1e9f;
            while (at < label.length && t >= lineStart) {
                if (state[at] == BootArt.NOT_YET) state[at] = BootArt.WAITING;
                if (!settle(at, t, t - lineStart)) break;
                at++;
                lineStart = t + 0.18f;          // a beat between lines, so each one is seen to arrive
            }
            if (at == label.length && doneAt < 0) doneAt = t;
            boolean ready = linkGood && engineGood;
            String verdict = doneAt < 0 ? null : ready ? "READY  ·  START ENGINE" : "OPENING DASH  ·  CHECK THE LINK";
            art.draw(c, getWidth(), getHeight(), t, label, result, state, verdict, ready);
            if (doneAt >= 0 && t >= doneAt + HOLD + 0.4f) openDash();
            else postInvalidateOnAnimation();
        }
    }

    static float ramp(float t, float from, float to) { return Math.max(0, Math.min(1, (t - from) / (to - from))); }
}
