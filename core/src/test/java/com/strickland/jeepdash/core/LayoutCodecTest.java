package com.strickland.jeepdash.core;

public class LayoutCodecTest {
    public static void codecRoundTrip() {
        Layout xp = LayoutCodec.defaultFor("xp", 1280, 800);
        xp.gauges.get(0).warnOn = true;
        xp.gauges.get(0).warnLimit = 5200;
        Layout back = LayoutCodec.decode(LayoutCodec.encode(xp), "xp", 1280, 800);
        T.eq(xp.gauges, back.gauges, "same gauges after encode/decode");
    }

    public static void aChosenUnitIsSavedWithTheGauge() {
        Layout xp = LayoutCodec.defaultFor("xp", 1280, 800);
        xp.find("g3").unit = "°C";
        String text = LayoutCodec.encode(xp);
        Layout back = LayoutCodec.decode(text, "xp", 1280, 800);
        T.eq("°C", back.find("g3").unit, "the unit comes back");
        T.eq(null, back.find("g1").unit, "a gauge without one stays without");
        T.eq(xp.gauges, back.gauges, "same gauges after encode/decode");
        T.ok(!xp.find("g3").equals(LayoutCodec.defaultFor("xp", 1280, 800).find("g3")), "the unit counts as a change");
    }

    public static void layoutsSavedBeforeUnitsLoadUnchanged() {
        String old = "v1\ng1|coolant|DIAL|0.0|0.0|300.0|300.0|true|true|230.0\n";
        GaugeSpec g = LayoutCodec.decode(old, "xp", 1280, 800).find("g1");
        T.ok(g != null && g.unit == null && g.warnLimit == 230, "an old line reads as before, in the base unit");
        Layout l = LayoutCodec.decode(old, "xp", 1280, 800);
        T.eq(old, LayoutCodec.encode(l), "and is written back exactly as it was");
    }

    public static void decodeGarbageGivesDefault() {
        T.eq(LayoutCodec.defaultFor("jeep", 1280, 800).gauges,
                LayoutCodec.decode("hello", "jeep", 1280, 800).gauges, "garbage -> Jeep default");
        T.eq(LayoutCodec.defaultFor("xp", 1280, 800).gauges,
                LayoutCodec.decode(null, "xp", 1280, 800).gauges, "nothing saved -> XP default");
    }

    public static void decodeClampsOutOfBounds() {
        Layout l = LayoutCodec.decode("v1\ng1|rpm|DIAL|5000|-40|300|300|false|true|0", "xp", 1280, 800);
        GaugeSpec g = l.find("g1");
        T.near(980, g.x, 0.001, "x pulled back on screen");
        T.near(0, g.y, 0.001, "y pulled back on screen");
    }

    public static void defaultsFitTheScreen() {
        for (String look : new String[]{"xp", "jeep"}) {
            Layout l = LayoutCodec.defaultFor(look, 1280, 800);
            for (GaugeSpec g : l.gauges) {
                T.ok(g.x >= 0 && g.y >= 0 && g.x + g.w <= 1280.01 && g.y + g.h <= 800.01, look + " " + g.key + " on screen");
            }
        }
    }

    public static void jeepDefaultLeavesRoomForLabelsAndNothingOverlaps() {
        Layout l = LayoutCodec.defaultFor("jeep", 1280, 800);
        GaugeSpec small = l.find("g3");
        T.ok(small.h >= small.w + 40, "small dials are taller than wide: name and reading go under the dial");
        for (GaugeSpec a : l.gauges)
            for (GaugeSpec b : l.gauges) {
                if (a == b) continue;
                boolean apart = a.x + a.w <= b.x || b.x + b.w <= a.x || a.y + a.h <= b.y || b.y + b.h <= a.y;
                T.ok(apart, a.key + " overlaps " + b.key);
            }
    }

    public static void defaultLayoutsNeedOnlyOneLine() {
        for (String look : new String[]{"xp", "jeep"}) {
            PollScheduler s = new PollScheduler(60000);
            s.setActive(LayoutCodec.defaultFor(look, 1280, 800).activeParamIds());
            T.eq(java.util.Collections.emptySet(), s.slowIds(), look + ": every default gauge is on the working line");
        }
    }

    public static void aGaugesOwnRangeAndRedZoneRoundTrip() {
        Layout l = new Layout(1280, 800);
        GaugeSpec g = new GaugeSpec("g1", "boost_c", GaugeType.DIAL, 10, 10, 300, 300);
        g.rangeMin = -10.0;
        g.rangeMax = 25.0;
        g.redFrom = 18.5;
        l.add(g);
        GaugeSpec plain = new GaugeSpec("g2", "rpm", GaugeType.DIAL, 400, 10, 300, 300);
        plain.redFrom = 6000.0;
        l.add(plain);
        String text = LayoutCodec.encode(l);
        Layout back = LayoutCodec.decode(text, "xp", 1280, 800);
        T.eq(g, back.find("g1"), "range and red zone kept");
        T.eq(plain, back.find("g2"), "a red zone alone kept");
        T.ok(back.find("g2").rangeMin == null && back.find("g2").rangeMax == null, "no range set: none read back");
        Layout none = new Layout(1280, 800);
        none.add(new GaugeSpec("g1", "rpm", GaugeType.DIAL, 10, 10, 300, 300));
        T.ok(!LayoutCodec.encode(none).contains(";"), "a gauge without them is written as before");
    }
}
