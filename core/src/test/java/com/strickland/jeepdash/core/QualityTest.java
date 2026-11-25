package com.strickland.jeepdash.core;

import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Quality modes, frame pacing, heat protection, the tablet's heat and battery watch, and the 2K model copy. */
public final class QualityTest {
    private QualityTest() { }

    // ---------------- quality modes ----------------

    public static void highIsTheDefaultMode() {
        T.eq(QualityMode.HIGH, QualityMode.pick(null), "nothing saved");
        T.eq(QualityMode.HIGH, QualityMode.pick("Full"), "an old Effects value");
        T.eq(QualityMode.ULTRA, QualityMode.pick("Ultra"), "saved choice");
        T.eq(QualityMode.SAVER, QualityMode.pick("Power saver"), "saved choice");
    }

    public static void eachModeHasItsFrameRatesScaleAndFeatures() {
        QualityMode u = QualityMode.of(QualityMode.ULTRA), h = QualityMode.of(QualityMode.HIGH), s = QualityMode.of(QualityMode.SAVER);
        T.eq(30, u.fps(true), "Ultra: 30 a second");
        T.near(1.0, u.renderScale, 1e-6, "Ultra: native with MSAA (supersampling cost twice the pixels)");
        T.ok(u.msaa && u.shadow && u.ssao && u.multiPassBloom && u.fullTextures, "Ultra: everything");
        T.eq(60, h.fps(true), "High: 60 while moving");
        T.eq(30, h.fps(false), "High: 30 when still");
        T.near(1.0, h.renderScale, 1e-6, "High: native");
        T.ok(h.msaa && h.bloom && !h.multiPassBloom && !h.fullTextures && !h.shadow, "High: 4x MSAA, one bloom pass, 2K textures");
        T.eq(30, s.fps(true), "Power saver: 30");
        T.ok(s.fps(false) >= 10 && s.fps(false) <= 15, "Power saver: 10-15 when still");
        T.near(0.75, s.renderScale, 1e-6, "Power saver: under-sampled");
        T.ok(!s.msaa && !s.bloom && !s.fringe && s.liteMesh, "Power saver: no MSAA, bloom or fringe; the lite mesh");
        T.ok(s.scanlineGap > h.scanlineGap, "fewer scanlines");
        for (String m : QualityMode.MODES) T.ok(QualityMode.of(m).fps(true) <= 60, m + " never past 60");
    }

    public static void eachModePicksItsTruckFile() {
        T.eq(QualityMode.FULL, QualityMode.model(QualityMode.ULTRA, true, true, true), "Ultra: full detail");
        T.eq(QualityMode.TWO_K, QualityMode.model(QualityMode.HIGH, true, true, true), "High: the 2K copy");
        T.eq(QualityMode.FULL, QualityMode.model(QualityMode.HIGH, true, false, true), "High: full when its textures are 2K already");
        T.eq(QualityMode.LITE, QualityMode.model(QualityMode.SAVER, true, true, true), "Power saver: lite");
        T.eq(QualityMode.TWO_K, QualityMode.model(QualityMode.SAVER, true, true, false), "no lite: the lightest there is");
        T.eq(QualityMode.LITE, QualityMode.model(QualityMode.ULTRA, false, false, true), "only the lite one: used in every mode");
        T.eq(QualityMode.STAND_IN, QualityMode.model(QualityMode.HIGH, false, false, false), "nothing imported: the stand-in");
    }

    public static void effectsSwitchAndCalmShapeTheHologram() {
        QualityMode h = QualityMode.of(QualityMode.HIGH);
        HoloEffects on = HoloEffects.of(h, true, false), off = HoloEffects.of(h, false, false), calm = HoloEffects.of(h, true, true);
        T.ok(on.bloom && on.fringe && on.flicker && on.scanlineGap > 0 && on.animateMs > 0, "High with effects: the full hologram");
        T.ok(!off.bloom && !off.fringe && !off.flicker && off.scanlineGap == 0 && off.animateMs == 0, "effects off: a clean look");
        T.ok(off.ringSpeed > 0, "the rings still turn");
        T.ok(!calm.flicker && calm.animateMs == 0 && !calm.sweep, "calm while moving: no flicker or big animations");
        T.ok(calm.bloom && calm.scanlineGap > 0, "but the look stays");
        T.ok(HoloEffects.of(QualityMode.of(QualityMode.ULTRA), true, false).multiBloom, "Ultra: multi-pass bloom");
        T.ok(!HoloEffects.of(QualityMode.of(QualityMode.SAVER), true, false).bloom, "Power saver: no bloom");
    }

    // ---------------- frame pacing ----------------

    private static int frames(long vsyncNs, int fps) {
        FramePacer p = new FramePacer();
        int n = 0;
        for (long t = vsyncNs; t <= 1_000_000_000L; t += vsyncNs) if (p.due(t, vsyncNs, fps)) n++;
        return n;
    }

    public static void framesArePacedToTheCapWhateverTheScreenRefresh() {
        T.ok(Math.abs(frames(11_111_111, 60) - 60) <= 2, "90 Hz screen, 60 cap: " + frames(11_111_111, 60));
        T.ok(Math.abs(frames(8_333_333, 60) - 60) <= 2, "120 Hz screen: " + frames(8_333_333, 60));
        T.ok(Math.abs(frames(16_666_667, 60) - 60) <= 2, "60 Hz screen: " + frames(16_666_667, 60));
        T.ok(Math.abs(frames(11_111_111, 30) - 30) <= 2, "90 Hz, 30 cap: " + frames(11_111_111, 30));
        T.ok(Math.abs(frames(8_333_333, 12) - 12) <= 2, "power saver idle: " + frames(8_333_333, 12));
    }

    public static void aRequestDrawsAtTheNextFrame() {
        FramePacer p = new FramePacer();
        T.ok(p.due(0, 11_111_111, 30), "first frame");
        T.ok(!p.due(11_111_111, 11_111_111, 30), "too soon at 30");
        p.reset();
        T.ok(p.due(22_222_222, 11_111_111, 30), "something changed: draw now");
    }

    // ---------------- heat protection ----------------

    public static void heatStepsTheModeDownAtOnce() {
        HeatGuard g = new HeatGuard();
        T.eq(0, g.update(HeatGuard.LIGHT, 0, true), "light: nothing");
        T.eq(QualityMode.ULTRA, HeatGuard.mode(QualityMode.ULTRA, g.cap()), "Ultra stays");
        T.eq(1, g.update(HeatGuard.MODERATE, 1000, true), "moderate: stepped down");
        T.eq(QualityMode.HIGH, HeatGuard.mode(QualityMode.ULTRA, g.cap()), "Ultra drops to High");
        T.eq(QualityMode.HIGH, HeatGuard.mode(QualityMode.HIGH, g.cap()), "High stays");
        T.eq(1, g.update(HeatGuard.SEVERE, 2000, true), "severe");
        T.eq(QualityMode.SAVER, HeatGuard.mode(QualityMode.HIGH, g.cap()), "any mode drops to Power saver");
        T.ok(!g.paused3d(), "3D still on");
        T.eq(1, g.update(6, 3000, true), "shutdown-level heat counts as critical");
        T.ok(g.paused3d(), "critical: 3D paused, plain 2D gauges");
    }

    public static void theChosenModeComesBackAfterTwoCoolMinutes() {
        HeatGuard g = new HeatGuard();
        g.update(HeatGuard.SEVERE, 0, true);
        T.eq(0, g.update(HeatGuard.LIGHT, 1000, true), "cooling");
        T.eq(0, g.update(HeatGuard.MODERATE, 60_000, true), "warm again for a moment: no flapping");
        T.eq(HeatGuard.CAP_SAVER, g.cap(), "still held down");
        T.eq(0, g.update(HeatGuard.NONE, 61_000, true), "cool again: the two minutes start over");
        T.eq(0, g.update(HeatGuard.NONE, 61_000 + HeatGuard.COOL_MS - 1, true), "not quite two minutes");
        T.eq(-1, g.update(HeatGuard.NONE, 61_000 + HeatGuard.COOL_MS, true), "restored");
        T.eq(QualityMode.ULTRA, HeatGuard.mode(QualityMode.ULTRA, g.cap()), "the owner's choice is back");
    }

    public static void heatProtectionCanBeTurnedOff() {
        HeatGuard g = new HeatGuard();
        g.update(HeatGuard.CRITICAL, 0, true);
        T.eq(-1, g.update(HeatGuard.CRITICAL, 1000, false), "turned off: lifted");
        T.ok(!g.paused3d(), "3D back");
        T.eq(0, g.update(HeatGuard.CRITICAL, 2000, false), "and stays off");
    }

    // ---------------- the tablet's heat and battery, behind any screen ----------------

    public static void tabletReadingsGoToTheLogWithNoTabletGaugeOnTheLayout() {
        T.ok(TabletWatch.wanted(false), "the Jeep always watches the tablet, whatever the layout shows");
        T.ok(!TabletWatch.wanted(true), "the Porsche does not");
        for (String id : new String[]{"tablet_temp", "tablet_batt", "tablet_heat"})
            T.ok(Catalog.byId(id) != null && Catalog.byId(id).tablet, id + " is a value, so the drive log (every value) records it");
        Layout empty = new Layout(1280, 800);
        T.ok(empty.activeParamIds().isEmpty(), "no gauges at all");
        TabletWatch w = new TabletWatch();
        w.battery(40, 80, true, 0);
        w.thermal(HeatGuard.LIGHT, 0);
        Map<String, Double> v = w.values();
        T.near(104, v.get("tablet_temp"), 0.01, "temperature in °F");
        T.near(80, v.get("tablet_batt"), 0.01, "battery %");
        T.near(1, v.get("tablet_heat"), 0.01, "heat level");
        T.ok(!v.containsKey("tablet_ma"), "no current reading: left blank");
        w.current(-850_000);
        T.near(-850, w.values().get("tablet_ma"), 0.01, "discharging 850 mA");
        StringWriter log = new StringWriter();
        try {
            List<String> ids = new ArrayList<>();
            for (Param p : Catalog.all()) ids.add(p.id);
            new DriveLogWriter(log, ids).flush();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        T.ok(log.toString().contains("tablet_temp") && log.toString().contains("tablet_heat"), "drive log columns");
    }

    public static void hotTabletAlertsWithHysteresis() {
        TabletWatch w = new TabletWatch();
        w.battery(44.9, 80, true, 0);
        T.eq(TabletWatch.OK, w.alert(), "fine");
        w.battery(45, 80, true, 1000);
        T.eq(TabletWatch.WARM, w.alert(), "45 °C: warning");
        T.ok(w.message("°F").contains("113"), "said in °F: " + w.message("°F"));
        w.battery(50, 80, true, 2000);
        T.eq(TabletWatch.HOT, w.alert(), "50 °C: overheating");
        T.ok(w.message("°C").startsWith("Tablet overheating, shade or cool it"), w.message("°C"));
        w.battery(49, 80, true, 3000);
        T.eq(TabletWatch.HOT, w.alert(), "49: still hot (no flapping)");
        w.battery(47.5, 80, true, 4000);
        T.eq(TabletWatch.WARM, w.alert(), "below 48: back to a warning");
        w.battery(42.5, 80, true, 5000);
        T.eq(TabletWatch.OK, w.alert(), "below 43: clear");
    }

    public static void lowBatteryWarnsOnlyWhenNotCharging() {
        TabletWatch w = new TabletWatch();
        w.battery(30, 15, true, 0);
        T.eq(TabletWatch.OK, w.alert(), "charging: fine");
        w.battery(30, 15, false, 1000);
        T.eq(TabletWatch.LOW, w.alert(), "15% on battery: warning");
        T.ok(w.message("°F").contains("15%"), w.message("°F"));
        w.battery(30, 16, false, 2000);
        T.eq(TabletWatch.LOW, w.alert(), "16%: still low");
        w.battery(30, 17, false, 3000);
        T.eq(TabletWatch.OK, w.alert(), "17%: clear");
        w.battery(51, 10, false, 4000);
        T.eq(TabletWatch.HOT, w.alert(), "heat comes first");
    }

    public static void thermalHeadroomRaisesTheHeatLevel() {
        TabletWatch w = new TabletWatch();
        w.thermal(HeatGuard.NONE, 0);
        w.headroom(Float.NaN);
        T.eq(HeatGuard.NONE, w.heat(), "as reported");
        w.headroom(1.05f);
        T.eq(HeatGuard.MODERATE, w.heat(), "about to throttle counts as moderate");
        w.thermal(HeatGuard.SEVERE, 10);
        T.eq(HeatGuard.SEVERE, w.heat(), "never lowered");
        T.eq("Severe", HeatGuard.name(w.heat()), "named");
    }

    // ---------------- the 2K copy of a model ----------------

    private static byte[] glb(String json, byte[] bin) {
        byte[] j = json.getBytes(StandardCharsets.UTF_8);
        int jp = (j.length + 3) & ~3, bp = (bin.length + 3) & ~3;
        ByteBuffer b = ByteBuffer.allocate(12 + 8 + jp + 8 + bp).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46546C67).putInt(2).putInt(b.capacity());
        b.putInt(jp).putInt(0x4E4F534A).put(j);
        for (int i = j.length; i < jp; i++) b.put((byte) ' ');
        b.putInt(bp).putInt(0x004E4942).put(bin);
        return b.array();
    }

    public static void imagesCanBeSwappedForSmallerOnes() {
        byte[] bin = new byte[24 + 16 + 8];
        for (int i = 0; i < 24; i++) bin[i] = (byte) i;                  // vertex data
        for (int i = 0; i < 16; i++) bin[24 + i] = (byte) (100 + i);     // image 0
        for (int i = 0; i < 8; i++) bin[40 + i] = (byte) (200 + i);      // image 1
        String json = "{\"asset\":{\"version\":\"2.0\",\"generator\":\"t \\\"q\\\"\"},\"buffers\":[{\"byteLength\":48}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,\"byteLength\":24},{\"buffer\":0,\"byteOffset\":24,\"byteLength\":16},"
                + "{\"buffer\":0,\"byteOffset\":40,\"byteLength\":8}],"
                + "\"images\":[{\"bufferView\":1,\"mimeType\":\"image/png\"},{\"bufferView\":2,\"mimeType\":\"image/jpeg\"}],"
                + "\"accessors\":[{\"bufferView\":0,\"count\":2,\"type\":\"VEC3\",\"componentType\":5126,\"min\":[-1.5,0,2.25e-3],\"max\":[1,2,3]}]}";
        byte[] in = glb(json, bin);
        List<GlbImages.Image> imgs = GlbImages.list(in);
        T.eq(2, imgs.size(), "two images");
        T.eq("image/jpeg", imgs.get(1).mime, "mime type");
        T.eq((int) (byte) 100, (int) in[imgs.get(0).offset], "image 0 found in the file");
        Map<Integer, byte[]> swap = new HashMap<>();
        swap.put(0, new byte[]{1, 2, 3, 4, 5});
        byte[] out = GlbImages.replace(in, swap);
        T.eq(out.length, ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).getInt(8), "header length is the file length");
        T.eq(0, out.length % 4, "4-byte aligned");
        List<GlbImages.Image> after = GlbImages.list(out);
        T.eq(5, after.get(0).length, "smaller image 0");
        T.eq((int) (byte) 1, (int) out[after.get(0).offset], "its new bytes");
        T.eq(8, after.get(1).length, "image 1 kept");
        T.eq((int) (byte) 200, (int) out[after.get(1).offset], "moved along with its bytes");
        T.eq(0, after.get(1).offset % 4, "views stay aligned");
        T.eq(null, Glb.check(java.util.Arrays.copyOf(out, 20), out.length), "still a good GLB");
        Object j = Json.parse(GlbImages.json(out));
        @SuppressWarnings("unchecked") Map<String, Object> root = (Map<String, Object>) j;
        T.eq("t \"q\"", ((Map<?, ?>) root.get("asset")).get("generator"), "strings survive");
        List<?> min = (List<?>) ((Map<?, ?>) ((List<?>) root.get("accessors")).get(0)).get("min");
        T.near(-1.5, ((Number) min.get(0)).doubleValue(), 1e-12, "numbers survive");
        T.near(0.00225, ((Number) min.get(2)).doubleValue(), 1e-12, "small numbers too");
    }

    public static void jsonRoundTrips() {
        String s = "{\"a\":[1,2.5,-3e2,true,false,null,\"x\\n\\u00e9\"],\"b\":{}}";
        Object o = Json.parse(s);
        T.eq(o, Json.parse(Json.write(o)), "write then read gives the same");
        T.ok(Json.write(o).contains("\"x\\né\""), "escapes kept");
    }
}
