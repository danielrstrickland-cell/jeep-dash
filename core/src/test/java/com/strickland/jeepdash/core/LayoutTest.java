package com.strickland.jeepdash.core;

import java.util.Arrays;
import java.util.LinkedHashSet;

public class LayoutTest {
    static Layout one(float x, float y, float w, float h) {
        Layout l = new Layout(1280, 800);
        l.add(new GaugeSpec("g", "rpm", GaugeType.DIAL, x, y, w, h));
        return l;
    }

    public static void moveClampsToScreen() {
        Layout l = one(10, 10, 200, 200);
        l.move("g", -50, 5000);
        GaugeSpec g = l.find("g");
        T.near(0, g.x, 0.001, "x clamped to left edge");
        T.near(600, g.y, 0.001, "y clamped so the bottom stays on screen");
    }

    public static void resizeRespectsMinimum() {
        Layout l = one(100, 100, 200, 200);
        l.resize("g", -500, -500);
        T.near(Layout.MIN_SIZE, l.find("g").w, 0.001, "min width");
        T.near(Layout.MIN_SIZE, l.find("g").h, 0.001, "min height");
        Layout r = one(1000, 100, 200, 200);
        r.resize("g", 500, 0);
        T.near(1280, r.find("g").x + r.find("g").w, 0.001, "right edge stops at the screen edge");
    }

    public static void snapRoundsToGrid() {
        Layout l = one(13, 21, 203, 197);
        l.snap(8);
        T.near(16, l.find("g").x, 0.001, "x snapped");
        T.near(24, l.find("g").y, 0.001, "y snapped");
        T.near(200, l.find("g").w, 0.001, "w snapped");
        T.near(200, l.find("g").h, 0.001, "h snapped");
    }

    public static void warningRule() {
        GaugeSpec g = new GaugeSpec("g", "coolant", GaugeType.DIAL, 0, 0, 100, 100);
        g.warnOn = true; g.warnAbove = true; g.warnLimit = 230;
        T.ok(Layout.shouldFlash(g, 231.0), "above limit flashes");
        T.ok(!Layout.shouldFlash(g, 229.0), "below limit is calm");
        T.ok(!Layout.shouldFlash(g, null), "no reading never flashes");
        g.warnAbove = false;
        T.ok(Layout.shouldFlash(g, 229.0), "below-limit mode flashes under the limit");
        T.ok(!Layout.shouldFlash(g, 231.0), "below-limit mode is calm over the limit");
        g.warnOn = false;
        T.ok(!Layout.shouldFlash(g, 100.0), "warning off never flashes");
    }

    public static void activeParamIds() {
        Layout xp = LayoutCodec.defaultFor("xp", 1280, 800);
        T.eq(new LinkedHashSet<>(Arrays.asList("rpm", "speed", "coolant", "iat", "spark", "throttle", "map_v")),
                xp.activeParamIds(), "default XP values");
    }
}
