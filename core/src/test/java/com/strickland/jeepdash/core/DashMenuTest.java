package com.strickland.jeepdash.core;

import java.util.ArrayList;
import java.util.List;

/** The dash's menu: a short list of groups, each opening its own entries, settings shown beside them. */
public class DashMenuTest {

    private static DashMenu.State jeepXp() {
        DashMenu.State s = new DashMenu.State();
        s.look = "xp";
        s.nextLook = "Jeep style";
        s.linked = true;
        s.codes = true;
        s.fastAvailable = true;
        s.fast = "off";
        s.xpScheme = "Blue";
        s.xpMode = "Auto";
        s.jeepBacklight = "White";
        s.volume = "OFF";
        s.canEdit = true;
        s.snap = true;
        return s;
    }

    private static List<String> titles(List<DashMenu.Group> gs) {
        List<String> out = new ArrayList<>();
        for (DashMenu.Group g : gs) out.add(g.title);
        return out;
    }

    private static DashMenu.Entry find(List<DashMenu.Group> gs, String id) {
        for (DashMenu.Group g : gs) for (DashMenu.Entry e : g.entries) if (e.id.equals(id)) return e;
        return null;
    }

    private static String groupOf(List<DashMenu.Group> gs, String id) {
        for (DashMenu.Group g : gs) for (DashMenu.Entry e : g.entries) if (e.id.equals(id)) return g.title;
        return null;
    }

    public static void theTopMenuIsAShortListOfGroups() {
        T.eq("[Car and connection, Gauges, Look, Music, Fuel, Diagnostics, App]", titles(DashMenu.build(jeepXp())).toString(), "groups");
    }

    public static void entriesSitInTheirGroups() {
        List<DashMenu.Group> m = DashMenu.build(jeepXp());
        T.eq("Car and connection", groupOf(m, DashMenu.VEHICLE), "vehicle");
        T.eq("Car and connection", groupOf(m, DashMenu.FAST), "fast refresh");
        T.eq("Car and connection", groupOf(m, DashMenu.LINK_LOG), "link log");
        T.eq("Car and connection", groupOf(m, DashMenu.SPEED_CORRECTION), "speed correction");
        T.eq("Gauges", groupOf(m, DashMenu.EDIT), "edit");
        T.eq("Gauges", groupOf(m, DashMenu.ADD), "add");
        T.eq("Gauges", groupOf(m, DashMenu.SNAP), "snap");
        T.eq("Gauges", groupOf(m, DashMenu.RESET), "reset");
        T.eq("Look", groupOf(m, DashMenu.SWITCH_LOOK), "switch look");
        T.eq("Look", groupOf(m, DashMenu.XP_SCHEME), "xp colour");
        T.eq("Music", groupOf(m, DashMenu.MUSIC_BUTTON), "music button");
        T.eq("Music", groupOf(m, DashMenu.VOLUME), "volume");
        T.eq("Music", groupOf(m, DashMenu.VISUALIZER), "visualizer");
        T.eq("Fuel", groupOf(m, DashMenu.FUEL), "fuel");
        T.eq("Diagnostics", groupOf(m, DashMenu.CODES), "codes");
        T.eq("Diagnostics", groupOf(m, DashMenu.LOGS), "drive logs");
        T.eq("App", groupOf(m, DashMenu.AUTO_START), "auto-start");
        T.eq("App", groupOf(m, DashMenu.UPDATE), "update");
        T.eq("App", groupOf(m, DashMenu.CRASH), "last crash report");
    }

    public static void settingsShowBesideTheirEntries() {
        DashMenu.State s = jeepXp();
        s.musicButton = true;
        s.volume = "MEDIUM";
        s.autoStart = "KENWOOD";
        s.speedCorrectionPct = 3.6;
        s.snap = false;
        List<DashMenu.Group> m = DashMenu.build(s);
        T.eq("Music button: on", find(m, DashMenu.MUSIC_BUTTON).label, "music");
        T.eq("Volume follows speed (experimental): medium", find(m, DashMenu.VOLUME).label, "volume");
        T.eq("Auto-start with Bluetooth device: KENWOOD", find(m, DashMenu.AUTO_START).label, "auto-start");
        T.eq("Speed correction: +3.6%", find(m, DashMenu.SPEED_CORRECTION).label, "correction");
        T.eq("Snap to grid: off", find(m, DashMenu.SNAP).label, "snap");
        T.eq("Window colour: Blue", find(m, DashMenu.XP_SCHEME).label, "scheme");
        T.eq("Brightness: Auto (light sensor, else sunset)", find(m, DashMenu.XP_MODE).label, "brightness");
        T.eq("Switch look: Jeep style", find(m, DashMenu.SWITCH_LOOK).label, "next look");
        T.eq("Vehicle: Jeep Grand Cherokee", find(m, DashMenu.VEHICLE).label, "vehicle");
        T.eq("Fast refresh (experimental): off", find(m, DashMenu.FAST).label, "fast");
    }

    public static void offSettingsReadOff() {
        List<DashMenu.Group> m = DashMenu.build(jeepXp());
        T.eq("Music button: off", find(m, DashMenu.MUSIC_BUTTON).label, "music");
        T.eq("Auto-start with Bluetooth device: off", find(m, DashMenu.AUTO_START).label, "auto-start");
        T.eq("Speed correction: 0%", find(m, DashMenu.SPEED_CORRECTION).label, "correction");
        T.eq("Speed correction: -2.5%", DashMenu.correctionLabel(-2.5), "below");
    }

    public static void fastRefreshKeepsItsThreeWordings() {
        DashMenu.State s = jeepXp();
        s.fast = "on";
        T.eq("Fast refresh (experimental): ON", find(DashMenu.build(s), DashMenu.FAST).label, "on");
        s.fast = "backed_off";
        T.eq("Fast refresh: turned itself off (readings went missing). Tap to try again",
                find(DashMenu.build(s), DashMenu.FAST).label, "backed off");
    }

    public static void withoutTheLinkItsEntriesAreHiddenAsToday() {
        DashMenu.State s = jeepXp();
        s.linked = false;
        List<DashMenu.Group> m = DashMenu.build(s);
        T.eq(null, find(m, DashMenu.FAST), "fast refresh needs the link");
        T.eq(null, find(m, DashMenu.CODES), "codes need the link");
        T.eq(null, find(m, DashMenu.VOLUME), "volume lives in the link service");
        T.ok(find(m, DashMenu.FUEL) != null, "fuel is still there");
    }

    public static void lookEntriesFollowTheLook() {
        DashMenu.State s = jeepXp();
        s.look = "jeep";
        List<DashMenu.Group> m = DashMenu.build(s);
        T.eq(null, find(m, DashMenu.XP_SCHEME), "no XP colours on the Jeep look");
        T.eq("Backlight: White", find(m, DashMenu.JEEP_BACKLIGHT).label, "its backlight instead");
        s.look = "retro";
        m = DashMenu.build(s);
        T.eq(null, find(m, DashMenu.JEEP_BACKLIGHT), "none on 80s digital");
        T.eq(null, find(m, DashMenu.XP_MODE), "none on 80s digital");
    }

    public static void thePorscheHasNoVisualizerOrFastRefresh() {
        DashMenu.State s = jeepXp();
        s.porsche = true;
        s.look = "p_gt";
        s.fastAvailable = false;
        List<DashMenu.Group> m = DashMenu.build(s);
        T.eq(null, find(m, DashMenu.VISUALIZER), "no visualizer on the Cayenne");
        T.eq(null, find(m, DashMenu.FAST), "fast refresh is the Jeep line's");
        T.eq("Vehicle: Porsche Cayenne", find(m, DashMenu.VEHICLE).label, "vehicle");
    }

    public static void editHidesWhereTheLookCannotEdit() {
        DashMenu.State s = jeepXp();
        s.canEdit = false;
        T.eq(null, find(DashMenu.build(s), DashMenu.EDIT), "no edit");
    }

    public static void normalReadingsSitInDiagnosticsWithTheLearningProgress() {
        DashMenu.State s = jeepXp();
        s.normalProgress = "Learning normal: drive 3 of 5, day 4 of 7";
        List<DashMenu.Group> gs = DashMenu.build(s);
        T.eq("Diagnostics", groupOf(gs, DashMenu.NORMAL), "group");
        T.eq("Normal readings (learning: drive 3 of 5, day 4 of 7)", find(gs, DashMenu.NORMAL).label, "progress beside it");
        s.normalProgress = null;
        T.eq("Normal readings", find(DashMenu.build(s), DashMenu.NORMAL).label, "learned");
        s.porsche = true;
        s.linked = false;
        T.ok(find(DashMenu.build(s), DashMenu.NORMAL) != null, "both cars, link or not");
    }

    public static void scanAllValuesIsForTheJeepOnTheLinkOnly() {
        DashMenu.State s = jeepXp();
        List<DashMenu.Group> gs = DashMenu.build(s);
        T.eq("Diagnostics", groupOf(gs, DashMenu.SCAN), "group");
        T.eq("Scan all values (engine idling warm, in Park)", find(gs, DashMenu.SCAN).label, "label");
        s.scanProgress = "Pass 2 of 3: id 78 of ef (50%)";
        T.eq("Scan all values: Pass 2 of 3: id 78 of ef (50%)", find(DashMenu.build(s), DashMenu.SCAN).label, "while running");
        s.linked = false;
        T.eq(null, find(DashMenu.build(s), DashMenu.SCAN), "not without the link");
        s.linked = true;
        s.porsche = true;
        T.eq(null, find(DashMenu.build(s), DashMenu.SCAN), "not on the Porsche");
    }
}
