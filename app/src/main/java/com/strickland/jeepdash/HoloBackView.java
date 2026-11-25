package com.strickland.jeepdash;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

import com.strickland.jeepdash.core.GaugeSpec;
import com.strickland.jeepdash.core.GaugeType;
import com.strickland.jeepdash.theme.HoloTheme;

/** The Holo look's layer behind the 3D truck: the room, the projector stage, and the panels floating behind it. */
final class HoloBackView extends View {
    private final DashView dash;
    private final HoloTheme holo;
    private final float density;

    HoloBackView(Context c, DashView dash, HoloTheme holo) {
        super(c);
        this.dash = dash;
        this.holo = holo;
        this.density = c.getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth() / density, h = getHeight() / density;
        if (w <= 0) return;
        c.save();
        c.scale(density, density);
        holo.drawBackground(c, w, h);
        GaugeSpec truck = null;
        if (dash.layout() != null) for (GaugeSpec g : dash.layout().gauges) if (g.type == GaugeType.TRUCK) truck = g;
        if (!dash.chrome.editing || truck != null) holo.drawStage(c, truck, w, h, true);
        dash.drawHoloLayer(c, true, LinkService.now());
        c.restore();
    }
}
