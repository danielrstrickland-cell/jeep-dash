package com.strickland.jeepdash.core;

/** The Holo look's hologram effects: parallax, open and close animation, effects levels, calm while moving, depth. */
public final class HoloEffectsTest {
    private HoloEffectsTest() { }

    // ---------------- parallax ----------------

    public static void parallaxIsSmoothedAndNeverPastItsLimit() {
        Parallax p = new Parallax();
        for (long t = 0; t < 2000; t += 16) p.sample(0, 0, t);
        T.near(0, p.yawDeg(), 1e-9, "level to start");
        p.sample(40, -40, 2016);                               // the tablet swung a long way at once
        T.ok(Math.abs(p.yawDeg()) < 0.5, "one reading moves it only a little: " + p.yawDeg());
        double most = 0;
        for (long t = 2032; t < 6000; t += 16) {
            p.sample(40, -40, t);
            most = Math.max(most, Math.max(Math.abs(p.yawDeg()), Math.abs(p.pitchDeg())));
        }
        T.ok(most <= Parallax.LIMIT_DEG + 1e-9, "clamped: " + most);
        T.ok(Math.abs(p.yawDeg()) > 2, "but it does move: " + p.yawDeg());
        T.ok(p.pitchDeg() < -2, "both ways");
    }

    public static void aSteadyTiltRecentresAndJitterBarelyShows() {
        Parallax p = new Parallax();
        for (long t = 0; t < 1000; t += 16) p.sample(0, 0, t);
        for (long t = 1000; t < 40000; t += 16) p.sample(10, 0, t);
        T.ok(Math.abs(p.yawDeg()) < 0.3, "held at an angle, the view drifts back to the middle: " + p.yawDeg());
        double most = 0;
        for (long t = 40000; t < 45000; t += 16) {
            p.sample(10 + ((t / 16) % 2 == 0 ? 1 : -1), 0, t);       // 1° of buzz at 30 Hz
            if (t > 42000) most = Math.max(most, Math.abs(p.yawDeg()));
        }
        T.ok(most < 0.3, "buzz is filtered out: " + most);
    }

    public static void parallaxWrapsThroughNorth() {
        Parallax p = new Parallax();
        for (long t = 0; t < 2000; t += 16) p.sample(359, 0, t);
        for (long t = 2000; t < 3000; t += 16) p.sample(1, 0, t);
        T.ok(Math.abs(p.yawDeg()) < 1.5, "a 2° turn, not 358°: " + p.yawDeg());
    }

    public static void parallaxIsTinyWhileDriving() {
        Parallax p = new Parallax();
        p.setCalm(true);
        for (long t = 0; t < 1000; t += 16) p.sample(0, 0, t);
        double most = 0;
        for (long t = 1000; t < 8000; t += 16) {
            p.sample(30, 30, t);
            most = Math.max(most, Math.abs(p.yawDeg()));
        }
        T.ok(most <= Parallax.LIMIT_DEG * Parallax.CALM_SCALE + 1e-6, "mounted and moving: the scene does not swim: " + most);
    }

    // ---------------- materialize ----------------

    public static void panelsGrowFromALineThenWipeIn() {
        Materialize m = Materialize.opening(1000, Materialize.MS);
        T.near(Materialize.LINE, m.height(1000), 1e-9, "starts as a line");
        T.near(0, m.reveal(1000), 1e-9, "nothing shown yet");
        T.ok(m.height(1100) > 0.8 && m.reveal(1100) == 0, "stretched open before the wipe");
        double mid = m.reveal(1000 + Materialize.MS * 4 / 5);
        T.ok(mid > 0 && mid < 1, "the scanline is part way down: " + mid);
        T.near(1, m.height(1250), 1e-9, "full height at 250 ms");
        T.near(1, m.reveal(1250), 1e-9, "all shown");
        T.ok(m.done(1250) && !m.done(1249), "done on time");
        T.ok(!m.gone(1250), "an opening panel stays");
    }

    public static void closingRunsBackwardsAndThenIsGone() {
        Materialize m = Materialize.closing(0, Materialize.MS);
        T.near(1, m.height(0), 1e-9, "starts whole");
        T.near(1, m.reveal(0), 1e-9, "starts shown");
        T.ok(m.reveal(100) < 1, "wipes out first");
        T.near(Materialize.LINE, m.height(250), 1e-9, "ends as a line");
        T.ok(m.gone(250), "then it is gone");
    }

    public static void calmOrOffMeansNoAnimation() {
        Materialize m = Materialize.opening(500, 0);
        T.ok(m.done(500), "instant");
        T.near(1, m.height(500), 1e-9, "full height");
        T.near(1, m.reveal(500), 1e-9, "all shown");
    }

    public static void menuRowsUnfoldOneAfterAnother() {
        T.eq(0L, Materialize.rowDelay(0, true), "first row at once");
        T.ok(Materialize.rowDelay(3, true) > Materialize.rowDelay(2, true), "each a little later");
        T.ok(Materialize.rowDelay(20, true) <= 300, "a long list still opens quickly");
        T.eq(0L, Materialize.rowDelay(5, false), "no unfolding when animations are off");
    }

    // ---------------- calm while moving ----------------

    public static void calmStartsAboveFiveMphAndEndsBelowThree() {
        HoloEffects.Calm c = new HoloEffects.Calm();
        T.ok(!c.update(4.9, true), "below five: not yet");
        T.ok(c.update(5.0, true), "five: calm");
        T.ok(c.update(3.5, true), "still calm slowing down");
        T.ok(!c.update(2.9, true), "below three: lively again");
        T.ok(!c.update(40, false), "turned off: never calm");
        T.ok(!c.update(Double.NaN, true), "no speed known: not calm");
    }

    // ---------------- depth ----------------

    public static void flatPanelsLandExactlyOnTheirBoxes() {
        GaugeSpec g = new GaugeSpec("a", "coolant", GaugeType.DIGITAL, 22, 96, 184, 50);
        g.depth = 2;
        float[] q = HoloFace.project(g, 1280, 800, 0, 0, 0);
        float[] box = {22, 96, 206, 96, 206, 146, 22, 146};
        for (int i = 0; i < 8; i++) T.near(box[i], q[i], 0.01, "corner " + i);
    }

    public static void sidePanelsTurnInTowardTheViewer() {
        GaugeSpec left = new GaugeSpec("a", "coolant", GaugeType.DIGITAL, 22, 96, 184, 50);
        float[] q = HoloFace.project(left, 1280, 800, 1, 0, 0);
        double outer = q[7] - q[1], inner = q[5] - q[3];             // left edge height, right edge height
        T.ok(outer > inner, "the outer edge is nearer, so taller: " + outer + " vs " + inner);
        GaugeSpec right = new GaugeSpec("b", "coolant", GaugeType.DIGITAL, 1074, 96, 184, 50);
        float[] r = HoloFace.project(right, 1280, 800, 1, 0, 0);
        T.ok(r[5] - r[3] > r[7] - r[1], "mirrored on the right");
    }

    public static void layersSlideAgainstEachOtherWithParallax() {
        GaugeSpec front = new GaugeSpec("a", "batt_v", GaugeType.DIGITAL, 600, 600, 100, 50);
        GaugeSpec back = new GaugeSpec("b", "coolant", GaugeType.DIGITAL, 600, 600, 100, 50);
        GaugeSpec level = new GaugeSpec("c", "iat", GaugeType.DIGITAL, 600, 600, 100, 50);
        front.depth = 1;
        back.depth = -1;
        double f = HoloFace.project(front, 1280, 800, 0, 3, 0)[0] - 600;
        double b = HoloFace.project(back, 1280, 800, 0, 3, 0)[0] - 600;
        double l = HoloFace.project(level, 1280, 800, 0, 3, 0)[0] - 600;
        T.ok(f * b < 0, "front and back move opposite ways: " + f + ", " + b);
        T.near(0, l, 0.01, "level with the truck: stays put");
        T.ok(Math.abs(f) > 1 && Math.abs(f) < 15, "a few dp, not a lurch: " + f);
    }

    public static void depthIsSavedWithTheLayout() {
        Layout l = LayoutCodec.defaultFor("holo", 1280, 800);
        T.ok(l.gauges.stream().anyMatch(g -> g.depth < 0) && l.gauges.stream().anyMatch(g -> g.depth > 0),
                "the mockup puts some panels behind the truck and some in front");
        Layout back = LayoutCodec.decode(LayoutCodec.encode(l), "holo", 1280, 800);
        T.eq(l.gauges, back.gauges, "depths come back");
        GaugeSpec g = back.gauges.get(0);
        g.unit = "°C";
        g.depth = -2;
        Layout again = LayoutCodec.decode(LayoutCodec.encode(back), "holo", 1280, 800);
        T.eq(-2, again.find(g.key).depth, "with a unit as well");
        T.eq("°C", again.find(g.key).unit, "unit kept");
        Layout xp = LayoutCodec.defaultFor("xp", 1280, 800);
        T.ok(!LayoutCodec.encode(xp).contains("||"), "other looks are written as before");
    }
}
