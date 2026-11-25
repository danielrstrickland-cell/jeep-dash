package com.strickland.jeepdash.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The dash's menu: a short top list of groups, each opening its own entries, with the current setting shown
 * beside every entry that has one. Entries that only apply to one car or one look, or need the link, are left
 * out just as before. The Android side shows the lists and runs the entry it is given back by id.
 */
public final class DashMenu {
    private DashMenu() { }

    public static final String VEHICLE = "vehicle", FAST = "fast", SPEED_CORRECTION = "speed_correction",
            BLUETOOTH = "bluetooth", LINK_LOG = "link_log",
            EDIT = "edit", ADD = "add", SNAP = "snap", RESET = "reset",
            SWITCH_LOOK = "switch_look", XP_SCHEME = "xp_scheme", XP_MODE = "xp_mode", JEEP_BACKLIGHT = "jeep_backlight",
            MUSIC_BUTTON = "music_button", VOLUME = "volume", VISUALIZER = "visualizer",
            FUEL = "fuel",
            CODES = "codes", LOGS = "logs",
            AUTO_START = "auto_start", UPDATE = "update", LISTEN = "listen", TUNE_LOG = "tune_log";

    /** What the menu needs to know, filled in by the dash each time it opens. */
    public static final class State {
        public boolean porsche;
        public String look = "xp";
        public String nextLook;
        public boolean linked;                 // the link service is bound
        public boolean codes, fastAvailable;
        public String fast = "off";            // off, on, backed_off
        public String xpScheme, xpMode, jeepBacklight;
        public boolean musicButton;
        public String volume = "OFF";          // SpeedVolume.Strength name
        public String autoStart;               // the chosen device's name; null when off
        public double speedCorrectionPct;
        public boolean canEdit, snap;
    }

    public static final class Entry {
        public final String id, label;
        Entry(String id, String label) { this.id = id; this.label = label; }
    }

    public static final class Group {
        public final String title;
        public final List<Entry> entries;
        Group(String title, List<Entry> entries) { this.title = title; this.entries = Collections.unmodifiableList(entries); }
    }

    public static List<Group> build(State s) {
        List<Group> out = new ArrayList<>();

        List<Entry> car = new ArrayList<>();
        car.add(new Entry(VEHICLE, "Vehicle: " + (s.porsche ? "Porsche Cayenne" : "Jeep Grand Cherokee")));
        if (s.linked && s.fastAvailable) car.add(new Entry(FAST, fastLabel(s.fast)));
        car.add(new Entry(SPEED_CORRECTION, correctionLabel(s.speedCorrectionPct)));
        car.add(new Entry(BLUETOOTH, "Android Bluetooth settings (to pair the dongle)"));
        car.add(new Entry(LINK_LOG, "Share link log (for troubleshooting)"));
        add(out, "Car and connection", car);

        List<Entry> gauges = new ArrayList<>();
        if (s.canEdit) gauges.add(new Entry(EDIT, "Edit gauges"));
        gauges.add(new Entry(ADD, "Add a gauge"));
        gauges.add(new Entry(SNAP, "Snap to grid: " + onOff(s.snap)));
        gauges.add(new Entry(RESET, "Put this look's gauges back to the original layout"));
        add(out, "Gauges", gauges);

        List<Entry> look = new ArrayList<>();
        look.add(new Entry(SWITCH_LOOK, s.nextLook == null ? "Switch look" : "Switch look: " + s.nextLook));
        if ("xp".equals(s.look)) {
            look.add(new Entry(XP_SCHEME, "Window colour: " + s.xpScheme));
            look.add(new Entry(XP_MODE, "Brightness: " + NightHours.label(s.xpMode)
                    + (NightHours.AUTO.equals(s.xpMode) ? " (light sensor, else sunset)" : "")));
        } else if ("jeep".equals(s.look)) {
            look.add(new Entry(JEEP_BACKLIGHT, "Backlight: " + s.jeepBacklight));
        }
        add(out, "Look", look);

        List<Entry> music = new ArrayList<>();
        music.add(new Entry(MUSIC_BUTTON, "Music button: " + onOff(s.musicButton)));
        if (s.linked)
            music.add(new Entry(VOLUME, "Volume follows speed (experimental): " + SpeedVolume.Strength.of(s.volume).name().toLowerCase(Locale.US)));
        if (!s.porsche) music.add(new Entry(VISUALIZER, "Music visualizer"));
        add(out, "Music", music);

        List<Entry> fuel = new ArrayList<>();
        fuel.add(new Entry(FUEL, "Fuel: calibrate at fill-up"));
        add(out, "Fuel", fuel);

        List<Entry> diag = new ArrayList<>();
        if (s.linked && s.codes) diag.add(new Entry(CODES, "Trouble codes (read and clear)"));
        if (s.linked) diag.add(new Entry(TUNE_LOG, "Tune log"));
        diag.add(new Entry(LOGS, "Drive logs"));
        diag.add(new Entry(LISTEN, "Listen mode (Kenwood stereo Bluetooth)"));
        add(out, "Diagnostics", diag);

        List<Entry> app = new ArrayList<>();
        app.add(new Entry(AUTO_START, "Auto-start with Bluetooth device: " + (s.autoStart == null ? "off" : s.autoStart)));
        app.add(new Entry(UPDATE, "Check for update"));
        add(out, "App", app);
        return out;
    }

    private static void add(List<Group> out, String title, List<Entry> entries) {
        if (!entries.isEmpty()) out.add(new Group(title, entries));
    }

    private static String onOff(boolean on) { return on ? "on" : "off"; }

    static String fastLabel(String fast) {
        if ("on".equals(fast)) return "Fast refresh (experimental): ON";
        if ("backed_off".equals(fast)) return "Fast refresh: turned itself off (readings went missing). Tap to try again";
        return "Fast refresh (experimental): off";
    }

    /** "Speed correction: +3.6%", "-2.5%" or "0%". */
    public static String correctionLabel(double pct) {
        double p = Math.round(SpeedCorrection.clamp(pct) * 10) / 10.0;
        if (p == 0) return "Speed correction: 0%";
        return String.format(Locale.US, "Speed correction: %+.1f%%", p);
    }
}
