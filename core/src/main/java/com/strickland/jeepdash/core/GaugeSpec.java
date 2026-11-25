package com.strickland.jeepdash.core;

import java.util.Objects;

/**
 * One placed gauge: what it shows, where it sits (dp), its warning flash setting, and the unit it is shown in
 * (null = the value's own). The warning limit is always kept in the value's own unit.
 */
public final class GaugeSpec {
    public final String key;
    public String paramId;
    public GaugeType type;
    public float x, y, w, h;
    public boolean warnOn = false, warnAbove = true;
    public double warnLimit = 0;
    public String unit;
    /** Holo look: how far in front of (+) or behind (-) the truck the panel floats, -2..2. */
    public int depth;

    public GaugeSpec(String key, String paramId, GaugeType type, float x, float y, float w, float h) {
        this.key = key; this.paramId = paramId; this.type = type;
        this.x = x; this.y = y; this.w = w; this.h = h;
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof GaugeSpec)) return false;
        GaugeSpec g = (GaugeSpec) o;
        return key.equals(g.key) && Objects.equals(paramId, g.paramId) && type == g.type
                && x == g.x && y == g.y && w == g.w && h == g.h
                && warnOn == g.warnOn && warnAbove == g.warnAbove && warnLimit == g.warnLimit
                && Objects.equals(unit, g.unit) && depth == g.depth;
    }

    @Override public int hashCode() { return Objects.hash(key, paramId, type, x, y, w, h); }

    @Override public String toString() {
        return key + "[" + paramId + " " + type + " " + x + "," + y + " " + w + "x" + h
                + (warnOn ? " warn" + (warnAbove ? ">" : "<") + warnLimit : "") + (unit != null ? " in " + unit : "")
                + (depth != 0 ? " depth " + depth : "") + "]";
    }
}
