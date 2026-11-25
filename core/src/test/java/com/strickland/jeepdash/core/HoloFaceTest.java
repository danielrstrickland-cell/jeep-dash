package com.strickland.jeepdash.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** The Holo look: its default layout, its gauge styles, the menu, and the files the 3D view reads. */
public final class HoloFaceTest {
    private HoloFaceTest() { }

    private static GaugeSpec find(Layout l, String param, GaugeType t) {
        for (GaugeSpec g : l.gauges) if (g.paramId.equals(param) && g.type == t) return g;
        return null;
    }

    public static void defaultLayoutMatchesTheMockup() {
        Layout l = LayoutCodec.defaultFor("holo", 1280, 800);
        // the mockup is 1600 x 1000; the dash's layouts are kept at 1280 x 800
        GaugeSpec coolant = find(l, "coolant", GaugeType.DIGITAL);
        T.near(28 * 0.8, coolant.x, 0.01, "coolant panel top left");
        T.near(120 * 0.8, coolant.y, 0.01, "coolant panel y");
        T.near(230 * 0.8, coolant.w, 0.01, "panel width");
        T.near(62 * 0.8, coolant.h, 0.01, "panel height: smaller than other looks allow");
        String[] left = {"coolant", "map_kpa", "ltft", "batt_v", "spark"};
        String[] right = {"mpg_avg", "fuel_used", "speedo_err", "roll", "normal"};
        for (String id : left) T.ok(find(l, id, GaugeType.DIGITAL).x < 100, id + " on the left");
        for (String id : right) T.ok(find(l, id, GaugeType.DIGITAL).x > 1000, id + " on the right");
        GaugeSpec truck = find(l, "speed", GaugeType.TRUCK);
        GaugeSpec rpm = find(l, "rpm", GaugeType.ARC_LEFT), mph = find(l, "speed", GaugeType.ARC_RIGHT);
        T.ok(truck != null && rpm != null && mph != null, "truck in the middle, engine speed and road speed arcs");
        T.near(640, truck.x + truck.w / 2, 0.5, "truck centred");
        float[] rc = HoloFace.arcCentre(rpm), sc = HoloFace.arcCentre(mph);
        T.near(truck.x + truck.w / 2, rc[0], 1, "rpm arc wraps round the truck");
        T.near(truck.y + truck.h / 2, rc[1], 1, "rpm arc centre height");
        T.near(truck.x + truck.w / 2, sc[0], 1, "speed arc wraps round the truck");
        T.ok(find(l, "gear", GaugeType.HEX) != null, "gear hexagon under the platform");
        for (GaugeSpec g : l.gauges)
            T.ok(g.x >= 0 && g.y >= 0 && g.x + g.w <= 1280.01 && g.y + g.h <= 800.01, g.key + " on screen");
        for (GaugeSpec a : l.gauges)
            for (GaugeSpec b : l.gauges) {
                if (a == b || a.type != GaugeType.DIGITAL || (b.type != GaugeType.DIGITAL && b.type != GaugeType.TRUCK)) continue;
                boolean apart = a.x + a.w <= b.x || b.x + b.w <= a.x || a.y + a.h <= b.y || b.y + b.h <= a.y;
                T.ok(apart, a.key + " overlaps " + b.key);
            }
    }

    public static void holoPanelsCanBeSmallButOtherLooksKeepTheirMinimum() {
        Layout l = LayoutCodec.defaultFor("holo", 1280, 800);
        GaugeSpec g = find(l, "coolant", GaugeType.DIGITAL);
        l.resize(g.key, -500, -500);
        T.near(HoloFace.MIN_SIZE, g.h, 0.01, "holo minimum");
        Layout xp = LayoutCodec.defaultFor("xp", 1280, 800);
        GaugeSpec x = xp.gauges.get(0);
        xp.resize(x.key, -2000, -2000);
        T.near(Layout.MIN_SIZE, x.h, 0.01, "other looks unchanged");
        Layout back = LayoutCodec.decode(LayoutCodec.encode(l), "holo", 1280, 800);
        T.near(HoloFace.MIN_SIZE, back.find(g.key).h, 0.01, "a saved small panel loads small");
    }

    public static void theLayoutSurvivesSavingWithTheNewStyles() {
        Layout l = LayoutCodec.defaultFor("holo", 1280, 800);
        Layout back = LayoutCodec.decode(LayoutCodec.encode(l), "holo", 1280, 800);
        T.eq(l.gauges, back.gauges, "same after a save and load");
    }

    public static void theDefaultHoloLayoutNeedsOnlyOneLine() {
        PollScheduler s = new PollScheduler(60000);
        s.setActive(HoloFace.withCompanions(LayoutCodec.defaultFor("holo", 1280, 800).activeParamIds()));
        T.eq(java.util.Collections.emptySet(), s.slowIds(), "every reading on the working line");
    }

    public static void stylesDependOnTheLook() {
        T.eq(Arrays.asList(GaugeType.DIAL, GaugeType.DIGITAL, GaugeType.BAR), Arrays.asList(HoloFace.styles("xp")), "other looks");
        GaugeType[] holo = HoloFace.styles("holo");
        T.ok(Arrays.asList(holo).containsAll(Arrays.asList(GaugeType.DIGITAL, GaugeType.ARC_LEFT, GaugeType.ARC_RIGHT, GaugeType.HEX)), "holo styles");
        T.ok(!Arrays.asList(holo).contains(GaugeType.TRUCK), "the truck is not a style of a reading");
        T.eq(holo.length, HoloFace.styleNames("holo").length, "a name for each");
        T.eq("Panel", HoloFace.styleNames("holo")[Arrays.asList(holo).indexOf(GaugeType.DIGITAL)], "named for the owner");
    }

    public static void twoValuePanelsBringTheirPartner() {
        T.eq("stft", HoloFace.partner("ltft"), "fuel trim long with short");
        T.eq("timing_pulled", HoloFace.partner("spark"), "timing with pulled");
        T.eq("pitch", HoloFace.partner("roll"), "roll with pitch");
        T.eq(null, HoloFace.partner("coolant"), "most have none");
        Set<String> ids = HoloFace.withCompanions(new HashSet<>(Arrays.asList("ltft", "coolant")));
        T.ok(ids.contains("stft") && ids.contains("ltft") && ids.contains("coolant"), "the partner is read too");
        T.eq("FUEL TRIM  L / S", HoloFace.title("ltft", "Fuel trim long"), "mockup title");
        T.eq("INTAKE AIR", HoloFace.title("iat", "Intake air"), "others: their name");
        T.eq("L 2.1°", HoloFace.lean(-2.1), "leaning left");
        T.eq("R 0.4°", HoloFace.lean(0.4), "leaning right");
        T.eq("0.0°", HoloFace.lean(0.01), "level");
    }

    public static void arcsMirrorEachOther() {
        GaugeSpec l = new GaugeSpec("a", "rpm", GaugeType.ARC_LEFT, 100, 50, 300, 600);
        GaugeSpec r = new GaugeSpec("b", "speed", GaugeType.ARC_RIGHT, 700, 50, 300, 600);
        float[] lc = HoloFace.arcCentre(l), rc = HoloFace.arcCentre(r);
        T.ok(lc[0] > l.x + l.w, "the left arc's centre is to its right");
        T.ok(rc[0] < r.x, "the right arc's centre is to its left");
        T.near(lc[0] - (l.x + l.w), r.x - rc[0], 0.01, "mirror images");
        T.near(HoloFace.arcRadius(l), HoloFace.arcRadius(r), 0.01, "same size");
    }

    // ---------------- menu ----------------

    private static DashMenu.Entry entry(DashMenu.State s, String id) {
        for (DashMenu.Group g : DashMenu.build(s)) for (DashMenu.Entry e : g.entries) if (e.id.equals(id)) return e;
        return null;
    }

    public static void theTruckHasItsMenuEntriesOnlyInTheHoloLook() {
        DashMenu.State s = new DashMenu.State();
        s.look = "holo";
        s.hasTruck = true;
        s.truckMotion = "realistic";
        T.ok(entry(s, DashMenu.TRUCK).label.contains("realistic"), "3D truck settings, with the motion strength");
        T.eq(null, entry(s, DashMenu.ADD_TRUCK), "already there");
        s.hasTruck = false;
        T.ok(entry(s, DashMenu.ADD_TRUCK) != null, "hidden: can be put back");
        s.look = "xp";
        T.eq(null, entry(s, DashMenu.TRUCK), "not in other looks");
    }

    public static void theHoloLookIsTheJeepsAlone() {
        T.eq(Arrays.asList("p_retro", "p_gt"), Arrays.asList(HoloFace.looks(true)), "the Porsche keeps its two looks");
        T.eq(Arrays.asList("xp", "jeep", "retro", "holo"), Arrays.asList(HoloFace.looks(false)), "Jeep looks");
        DashMenu.State s = new DashMenu.State();
        s.porsche = true;
        s.look = "p_gt";
        T.eq(null, entry(s, DashMenu.TRUCK), "no truck entries on the Porsche");
        T.eq(null, entry(s, DashMenu.EFFECTS), "no effects entry on the Porsche");
    }

    public static void effectsAndCalmAreInTheHoloLooksMenu() {
        DashMenu.State s = new DashMenu.State();
        s.look = "holo";
        s.effects = "Lite";
        s.calm = true;
        T.eq("Effects: Lite", entry(s, DashMenu.EFFECTS).label, "effects level");
        T.eq("Calm while moving: on", entry(s, DashMenu.CALM).label, "calm while moving, on by default");
    }

    // ---------------- files ----------------

    /** A tiny cube-map KTX1 like cmgen writes: R11G11B10 texels, two levels, the SH in the key-values. */
    private static byte[] ktx() {
        String sh = "sh\0" + "0.5 0.5 0.5\n0.1 0.2 0.3\n0 0 0\n0 0 0\n0 0 0\n0 0 0\n0 0 0\n0 0 0\n-0.1 -0.1 -0.1\n";
        byte[] kv = sh.getBytes(StandardCharsets.US_ASCII);
        int kvPadded = (kv.length + 3) & ~3;
        int size = 64 + 4 + kvPadded + (4 + 6 * 16) + (4 + 6 * 4);
        ByteBuffer b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{(byte) 0xAB, 'K', 'T', 'X', ' ', '1', '1', (byte) 0xBB, '\r', '\n', 0x1A, '\n'});
        b.putInt(0x04030201).putInt(35898).putInt(1).putInt(6407).putInt(35898).putInt(35898);
        b.putInt(2).putInt(2).putInt(0).putInt(0).putInt(6).putInt(2).putInt(4 + kvPadded);
        b.putInt(kv.length).put(kv).put(new byte[kvPadded - kv.length]);
        b.putInt(16);
        for (int f = 0; f < 6; f++) for (int i = 0; i < 16; i++) b.put((byte) (f * 16 + i));
        b.putInt(4);
        for (int f = 0; f < 6; f++) for (int i = 0; i < 4; i++) b.put((byte) (100 + f));
        return b.array();
    }

    public static void cubeMapFileIsReadWithItsLevelsAndLighting() {
        Ktx1 k = Ktx1.parse(ktx());
        T.eq(2, k.size, "base size");
        T.eq(2, k.levels, "levels");
        T.eq(16, k.faceBytes[0], "face bytes, level 0");
        T.eq(4, k.faceBytes[1], "face bytes, level 1");
        byte[] all = ktx();
        T.eq(16, (int) all[k.offset[0] + 16], "face 1 of level 0 follows face 0");
        T.eq(101, (int) all[k.offset[1] + 4], "level 1, face 1");
        T.eq(27, k.sh.length, "nine colours of lighting");
        T.near(0.2, k.sh[4], 1e-6, "second coefficient, green");
        T.near(-0.1, k.sh[26], 1e-6, "last coefficient");
    }

    public static void aBrokenCubeMapIsRefused() {
        byte[] bad = ktx();
        bad[1] = 'X';
        T.ok(Ktx1.parse(bad) == null, "not a KTX file");
        T.ok(Ktx1.parse(Arrays.copyOf(ktx(), 100)) == null, "cut short");
    }

    private static byte[] glb(String json, int declared) {
        byte[] j = json.getBytes(StandardCharsets.UTF_8);
        int pad = (j.length + 3) & ~3;
        ByteBuffer b = ByteBuffer.allocate(20 + pad).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46546C67).putInt(2).putInt(declared < 0 ? 20 + pad : declared);
        b.putInt(pad).putInt(0x4E4F534A).put(j);
        while (b.hasRemaining()) b.put((byte) ' ');
        return b.array();
    }

    public static void aTruckFileMustBeASelfContainedGlb() {
        byte[] ok = glb("{\"asset\":{\"version\":\"2.0\"}}", -1);
        T.eq(null, Glb.check(Arrays.copyOf(ok, 20), ok.length), "a good GLB");
        T.ok(Glb.check("{\"asset\":{\"version\":\"2.0\"}}".getBytes(StandardCharsets.UTF_8), 200).contains(".glb"), "a .gltf (text) file is turned away");
        T.ok(Glb.check(Arrays.copyOf(ok, 20), ok.length - 4).contains("cut short"), "shorter than it says");
        byte[] v1 = Arrays.copyOf(ok, 20);
        v1[4] = 1;
        T.ok(Glb.check(v1, ok.length).contains("version"), "glTF 1 is too old");
        T.ok(Glb.check(new byte[5], 5) != null, "tiny");
    }
}
