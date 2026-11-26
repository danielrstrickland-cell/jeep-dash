package com.strickland.jeepdash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import java.util.Locale;

/**
 * The Sound hub's graph: what the mic hears as 31 bars (lined up on its own mid-range level), the target
 * curve as a line, and the gains the tablet DSP is applying as a second line. Plus or minus 24 dB top to bottom.
 */
final class RtaView extends View {
    private static final float RANGE = 24;
    private final Paint bar = new Paint(), grid = new Paint(), text = new Paint(Paint.ANTI_ALIAS_FLAG),
            targetLine = new Paint(Paint.ANTI_ALIAS_FLAG), gainLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private double[] live, target, gains;
    private final float dp;

    RtaView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        bar.setColor(Color.rgb(64, 132, 214));
        grid.setColor(Color.rgb(38, 46, 60));
        grid.setStrokeWidth(dp);
        text.setColor(Color.rgb(130, 142, 160));
        text.setTextSize(10 * dp);
        targetLine.setColor(Color.rgb(240, 196, 92));
        targetLine.setStyle(Paint.Style.STROKE);
        targetLine.setStrokeWidth(2 * dp);
        gainLine.setColor(Color.rgb(96, 214, 150));
        gainLine.setStyle(Paint.Style.STROKE);
        gainLine.setStrokeWidth(2 * dp);
    }

    /** Any of the three may be null (not known yet). */
    void set(double[] liveDb, double[] targetDb, double[] gainDb) {
        live = liveDb;
        target = targetDb;
        gains = gainDb;
        postInvalidate();
    }

    private float y(double db, float h) {
        double c = Math.max(-RANGE, Math.min(RANGE, db));
        return (float) (h / 2 - c / RANGE * (h / 2));
    }

    private void line(Canvas c, double[] v, Paint p, float w, float h) {
        if (v == null || v.length == 0) return;
        path.reset();
        float step = w / v.length;
        for (int i = 0; i < v.length; i++) {
            float x = step * (i + 0.5f), yy = y(v[i], h);
            if (i == 0) path.moveTo(x, yy);
            else path.lineTo(x, yy);
        }
        c.drawPath(path, p);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight() - 14 * dp;
        c.drawColor(Color.rgb(14, 18, 26));
        for (int db = -12; db <= 12; db += 12) c.drawLine(0, y(db, h), w, y(db, h), grid);
        if (live != null) {
            float step = w / live.length;
            for (int i = 0; i < live.length; i++) {
                float top = y(live[i], h);
                c.drawRect(step * i + dp, Math.min(top, h), step * (i + 1) - dp, h, bar);
            }
        }
        line(c, target, targetLine, w, h);
        line(c, gains, gainLine, w, h);
        String[] marks = {"20", "50", "100", "200", "500", "1k", "2k", "5k", "10k", "20k"};
        int[] bands = {0, 4, 7, 10, 14, 17, 20, 24, 27, 30};
        float step = w / 31f;
        for (int i = 0; i < marks.length; i++)
            c.drawText(marks[i], step * bands[i], getHeight() - 3 * dp, text);
        c.drawText(String.format(Locale.US, "+%d dB", (int) RANGE), 4 * dp, 11 * dp, text);
    }
}
