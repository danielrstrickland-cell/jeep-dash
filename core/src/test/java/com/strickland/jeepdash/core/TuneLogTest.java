package com.strickland.jeepdash.core;

import java.io.StringWriter;
import java.time.ZoneId;
import java.util.List;

public class TuneLogTest {
    private static final long START = java.time.Instant.parse("2026-11-24T22:58:00Z").toEpochMilli();

    public static void fourBuiltInProfilesInOrder() {
        List<LogProfile> all = LogProfile.all();
        T.eq(4, all.size(), "four profiles");
        T.eq("idle", all.get(0).id, "1");
        T.eq("cruise", all.get(1).id, "2");
        T.eq("coldstart", all.get(2).id, "3");
        T.eq("pull", all.get(3).id, "4");
    }

    public static void profileSelectionById() {
        T.eq("cruise", LogProfile.byId("Cruise").id, "case-insensitive");
        T.eq("pull", LogProfile.byId(" pull ").id, "trimmed");
        T.ok(LogProfile.byId("nope") == null, "unknown");
        T.ok(LogProfile.byId(null) == null, "null");
    }

    public static void profileSettings() {
        LogProfile idle = LogProfile.byId("idle");
        T.eq(10, idle.hz, "idle 10 Hz");
        T.eq(3, idle.minutes, "idle 3 min");
        T.eq(3, idle.steps.size(), "idle steps");
        T.eq("Warm engine, Park 1 min", idle.steps.get(0), "idle step 1");
        T.eq(5, LogProfile.byId("cruise").hz, "cruise 5 Hz");
        T.eq(10, LogProfile.byId("cruise").minutes, "cruise 10 min");
        T.eq(4, LogProfile.byId("cruise").steps.size(), "cruise steps");
        T.eq(2, LogProfile.byId("coldstart").hz, "coldstart 2 Hz");
        T.eq(15, LogProfile.byId("coldstart").minutes, "coldstart 15 min");
        T.eq(LogProfile.MAX_HZ, LogProfile.byId("pull").hz, "pull fastest");
        T.eq(1, LogProfile.byId("pull").minutes, "pull 1 min");
        T.eq(11, LogProfile.byId("cruise").channels.size(), "cruise channels, GPS speed and accuracy included");
    }

    public static void fileNameCarriesProfileAndTime() {
        T.eq("idle-20261124-2258.csv", LogProfile.byId("idle").fileName(START, ZoneId.of("UTC")), "utc name");
        T.eq("pull-20261124-1758.csv", LogProfile.byId("pull").fileName(START, ZoneId.of("America/New_York")), "local name");
    }

    public static void headerHasProfileUnitsAndMissingLine() throws Exception {
        StringWriter out = new StringWriter();
        new TuneLog(out, LogProfile.byId("pull"), START);          // Jeep catalog: knock retard is not mapped
        T.eq("# profile=pull\n# started=2026-11-24T22:58:00Z\n# missing=Knock retard\n"
                + "time_ms,RPM (rpm),MAP (kPa),TPS (%),Spark advance (deg),Speed (mph),STFT (%)\n", out.toString(), "pull header");
    }

    public static void noMissingLineWhenEverythingIsThere() throws Exception {
        StringWriter out = new StringWriter();
        new TuneLog(out, LogProfile.byId("idle"), START);
        String s = out.toString();
        T.ok(s.startsWith("# profile=idle\n"), "profile first");
        T.ok(!s.contains("# missing"), "idle channels all readable on the Jeep line: " + s);
        T.ok(s.contains("Idle air control (steps)"), "iac in idle log");
        T.ok(s.contains("Coolant temp (degF)"), "degrees written in ascii");
        T.ok(s.contains("Battery (V)"), "volts");
    }

    public static void unreadableChannelsAreSkippedAndListed() throws Exception {
        StringWriter out = new StringWriter();
        TuneLog log = new TuneLog(out, LogProfile.byId("idle"), START,
                id -> id.equals("rpm") || id.equals("coolant") ? Catalog.jeep(id) : null);
        T.eq(2, log.paramIds().size(), "only rpm and coolant");
        T.ok(out.toString().contains("# missing=Target idle;Spark advance;MAP;IAT;STFT;LTFT;Battery;Idle air control\n"), out.toString());
        LiveValues v = new LiveValues();
        v.put("rpm", 720, 1000);
        T.ok(log.row(1000, 1000, v), "row written");
        T.ok(out.toString().endsWith("time_ms,RPM (rpm),Coolant temp (degF)\n1000,720.0,\n"), out.toString());
    }

    public static void rowsFollowTheProfileRate() throws Exception {
        StringWriter out = new StringWriter();
        TuneLog log = new TuneLog(out, LogProfile.byId("coldstart"), START);     // 2 Hz: one row per 500 ms
        LiveValues v = new LiveValues();
        int written = 0;
        for (long t = 0; t <= 2000; t += 100) if (log.row(t, t, v)) written++;
        T.eq(5, written, "rows at 0, 500, 1000, 1500, 2000");
        T.eq(5, log.rowCount(), "count");
    }

    public static void tenHertzTakesEveryTick() throws Exception {
        TuneLog log = new TuneLog(new StringWriter(), LogProfile.byId("idle"), START);
        LiveValues v = new LiveValues();
        int written = 0;
        for (long t = 0; t < 1000; t += 100) if (log.row(t, t, v)) written++;
        T.eq(10, written, "every 100 ms tick");
    }

    public static void everyJeepChannelOfTheRequestedListExists() {
        String[] ids = {"rpm", "map_kpa", "spark", "iat", "coolant", "stft", "ltft", "target_idle", "throttle", "speed", "batt_v", "iac"};
        for (String id : ids) T.ok(Catalog.jeep(id) != null, id + " readable");
        T.ok(Catalog.jeep("knock_retard") == null, "knock retard is not mapped");
    }

    public static void screenTextHelpers() {
        LogProfile idle = LogProfile.byId("idle");
        T.eq("Step 1 of 3", idle.stepTitle(0), "step title");
        T.eq("Step 1 of 1", LogProfile.byId("pull").stepTitle(0), "single step");
        T.eq("00:00", LogProfile.clock(0), "zero");
        T.eq("03:07", LogProfile.clock(187999), "mm:ss");
        T.eq("10 Hz", idle.rateText(), "rate");
        T.eq("Warm \u00b7 Park, Drive, lights on/off \u00b7 3 min", idle.description, "idle line");
        T.eq("Hold 35, 45, 55, 65 mph \u00b7 10 min", LogProfile.byId("cruise").description, "cruise line");
        T.eq("After sitting 6+ hrs \u00b7 15 min", LogProfile.byId("coldstart").description, "cold line");
        T.eq("One full-throttle 2nd-gear pull \u00b7 1 min", LogProfile.byId("pull").description, "pull line");
    }

    public static void menuHasTuneLogOnlyWhenLinked() {
        DashMenu.State s = new DashMenu.State();
        s.look = "xp";
        T.ok(!menuHas(s), "not linked");
        s.linked = true;
        T.ok(menuHas(s), "linked");
    }

    private static boolean menuHas(DashMenu.State s) {
        for (DashMenu.Group g : DashMenu.build(s)) for (DashMenu.Entry e : g.entries) if (e.id.equals(DashMenu.TUNE_LOG)) return true;
        return false;
    }

    public static void cruiseGpsChannelsAreMissingWithoutPermission() throws Exception {
        StringWriter out = new StringWriter();
        TuneLog log = new TuneLog(out, LogProfile.byId("cruise"), START);
        T.ok(out.toString().contains("# missing=GPS speed;GPS accuracy\n"), out.toString());
        T.ok(!log.measuresSpeed(), "no pairing without GPS");
        log.close();
        T.ok(!out.toString().contains("speed_ratio"), "no footer without GPS");
    }

    public static void cruiseWithGpsHasColumnsAndFooter() throws Exception {
        StringWriter out = new StringWriter();
        java.util.Set<String> tablet = new java.util.HashSet<>(java.util.Arrays.asList("gps_mph", "gps_accuracy_m"));
        TuneLog log = new TuneLog(out, LogProfile.byId("cruise"), START, Catalog::jeep, tablet);
        T.ok(!out.toString().contains("# missing"), out.toString());
        T.ok(out.toString().contains("Speed (mph),GPS speed (mph),GPS accuracy (m)\n"), out.toString());
        T.ok(!log.paramIds().contains("gps_mph"), "the dongle is never asked for GPS");
        T.ok(log.channelIds().contains("gps_mph"), "but it is a channel");
        T.ok(log.measuresSpeed(), "pairs");
        LiveValues v = new LiveValues();
        v.setScale("speed", 1.036);                      // the dash's correction must not leak into the comparison
        for (int i = 0; i < 30; i++) {
            long t = 1000L * i;
            v.put("speed", 55, t);
            v.put("gps_mph", 57, t);
            v.put("gps_accuracy_m", 4, t);
            log.row(t, t, v);
            log.row(t + 200, t + 200, v);               // same fix at the next row: not counted twice
        }
        log.close();
        SpeedCalibration.Result r = log.speedResult();
        T.ok(r != null, "result");
        T.eq(27, r.samples, "one pair per fix");
        T.near(57 / 55.0, r.ratio, 1e-9, "raw speed used");
        String s = out.toString();
        T.ok(s.contains("# speed_ratio=1.0364\n"), s);
        T.ok(s.contains("# speed_samples=27\n"), s);
        T.ok(s.contains("# suggested_constant=0x"), s);
        T.ok(s.endsWith("\n") && s.lastIndexOf("# speed_ratio") > s.lastIndexOf("\n3"), "footer after the rows");
    }

    public static void footerSaysNoneWithTooFewSamples() throws Exception {
        StringWriter out = new StringWriter();
        java.util.Set<String> tablet = new java.util.HashSet<>(java.util.Arrays.asList("gps_mph", "gps_accuracy_m"));
        TuneLog log = new TuneLog(out, LogProfile.byId("cruise"), START, Catalog::jeep, tablet);
        log.close();
        T.ok(log.speedResult() == null, "none");
        T.ok(out.toString().endsWith("# speed_ratio=none\n# speed_samples=0\n"), out.toString());
    }
}
