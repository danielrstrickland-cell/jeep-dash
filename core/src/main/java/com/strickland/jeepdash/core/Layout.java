package com.strickland.jeepdash.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Free-placement layout for one look. Every edit keeps gauges on screen and at least MIN_SIZE. */
public final class Layout {
    public static final float MIN_SIZE = 96;

    public final float screenW, screenH;
    public final List<GaugeSpec> gauges = new ArrayList<>();

    public Layout(float screenW, float screenH) { this.screenW = screenW; this.screenH = screenH; }

    public void add(GaugeSpec g) { gauges.add(g); clamp(g); }

    public void remove(String key) { gauges.removeIf(g -> g.key.equals(key)); }

    public GaugeSpec find(String key) {
        for (GaugeSpec g : gauges) if (g.key.equals(key)) return g;
        return null;
    }

    public void move(String key, float dx, float dy) {
        GaugeSpec g = find(key);
        if (g == null) return;
        g.x += dx; g.y += dy;
        clamp(g);
    }

    public void resize(String key, float dw, float dh) {
        GaugeSpec g = find(key);
        if (g == null) return;
        g.w = Math.max(MIN_SIZE, Math.min(g.w + dw, screenW - g.x));
        g.h = Math.max(MIN_SIZE, Math.min(g.h + dh, screenH - g.y));
        clamp(g);
    }

    public void snap(float grid) {
        for (GaugeSpec g : gauges) {
            g.x = Math.round(g.x / grid) * grid; g.y = Math.round(g.y / grid) * grid;
            g.w = Math.round(g.w / grid) * grid; g.h = Math.round(g.h / grid) * grid;
            clamp(g);
        }
    }

    /** Snaps one gauge (the one just dragged) and leaves the rest exactly where they are. */
    public void snapOne(String key, float grid) {
        GaugeSpec g = find(key);
        if (g == null) return;
        g.x = Math.round(g.x / grid) * grid; g.y = Math.round(g.y / grid) * grid;
        g.w = Math.round(g.w / grid) * grid; g.h = Math.round(g.h / grid) * grid;
        clamp(g);
    }

    public void clampAll() { for (GaugeSpec g : gauges) clamp(g); }

    private void clamp(GaugeSpec g) {
        g.w = Math.max(MIN_SIZE, Math.min(g.w, screenW));
        g.h = Math.max(MIN_SIZE, Math.min(g.h, screenH));
        g.x = Math.max(0, Math.min(g.x, screenW - g.w));
        g.y = Math.max(0, Math.min(g.y, screenH - g.h));
    }

    public Set<String> activeParamIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (GaugeSpec g : gauges) ids.add(g.paramId);
        return ids;
    }

    /** True when the gauge's warning is on and the reading is past its limit. */
    public static boolean shouldFlash(GaugeSpec g, Double value) {
        if (!g.warnOn || value == null) return false;
        return g.warnAbove ? value > g.warnLimit : value < g.warnLimit;
    }
}
