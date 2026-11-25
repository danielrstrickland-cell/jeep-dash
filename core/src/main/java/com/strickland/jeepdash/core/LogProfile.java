package com.strickland.jeepdash.core;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** A kind of tune log: which values, how often, for how long, and what the driver is asked to do. */
public final class LogProfile {
    /** Sample rate meaning "as fast as the link gives readings" (the service ticks ten times a second). */
    public static final int MAX_HZ = 10;

    /** A wanted value: the Catalog id it is read under (an id the Jeep does not have is reported as missing) and its label. */
    public static final class Want {
        public final String paramId, label;
        Want(String paramId, String label) { this.paramId = paramId; this.label = label; }
    }

    public final String id, name, description;
    public final List<Want> channels;
    public final int hz;
    public final int minutes;
    public final List<String> steps;

    private LogProfile(String id, String name, String description, List<Want> channels, int hz, int minutes, String... steps) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.channels = Collections.unmodifiableList(channels);
        this.hz = hz;
        this.minutes = minutes;
        this.steps = Collections.unmodifiableList(Arrays.asList(steps));
    }

    private static final Want RPM = new Want("rpm", "RPM"),
            TARGET_IDLE = new Want("target_idle", "Target idle"),
            SPARK = new Want("spark", "Spark advance"),
            MAP = new Want("map_kpa", "MAP"),
            COOLANT = new Want("coolant", "Coolant temp"),
            IAT = new Want("iat", "IAT"),
            STFT = new Want("stft", "STFT"),
            LTFT = new Want("ltft", "LTFT"),
            BATTERY = new Want("batt_v", "Battery"),
            IAC = new Want("iac", "Idle air control"),
            TPS = new Want("throttle", "TPS"),
            SPEED = new Want("speed", "Speed"),
            GPS_SPEED = new Want("gps_mph", "GPS speed"),
            GPS_ACC = new Want("gps_accuracy_m", "GPS accuracy"),
            KNOCK_RETARD = new Want("knock_retard", "Knock retard");   // not mapped on the Jeep line yet

    private static List<Want> w(Want... ws) { return new ArrayList<>(Arrays.asList(ws)); }

    private static final List<LogProfile> ALL = Collections.unmodifiableList(Arrays.asList(
            new LogProfile("idle", "Idle", "Warm \u00b7 Park, Drive, lights on/off \u00b7 3 min", w(RPM, TARGET_IDLE, SPARK, MAP, COOLANT, IAT, STFT, LTFT, BATTERY, IAC),
                    10, 3, "Warm engine, Park 1 min", "Drive, foot on brake 1 min", "Toggle headlights and blower"),
            new LogProfile("cruise", "Cruise", "Hold 35, 45, 55, 65 mph \u00b7 10 min", w(RPM, MAP, SPARK, IAT, COOLANT, STFT, LTFT, TPS, SPEED, GPS_SPEED, GPS_ACC),
                    5, 10, "Hold 35 mph 1 min", "Hold 45 mph 1 min", "Hold 55 mph 1 min", "Hold 65 mph 1 min"),
            new LogProfile("coldstart", "Cold start", "After sitting 6+ hrs \u00b7 15 min", w(COOLANT, IAT, RPM, TARGET_IDLE, SPARK, STFT, LTFT, BATTERY),
                    2, 15, "Start from cold (sat 6+ hours), idle 2 min, then drive normally"),
            new LogProfile("pull", "Pull", "One full-throttle 2nd-gear pull \u00b7 1 min", w(RPM, MAP, TPS, SPARK, SPEED, STFT, KNOCK_RETARD),
                    MAX_HZ, 1, "One full-throttle 2nd-gear pull where safe and legal")));

    public static List<LogProfile> all() { return ALL; }

    /** The profile with this id (case does not matter), or null. */
    public static LogProfile byId(String id) {
        if (id == null) return null;
        for (LogProfile p : ALL) if (p.id.equalsIgnoreCase(id.trim())) return p;
        return null;
    }

    /** "Step 2 of 3", or "Step 1 of 1". */
    public String stepTitle(int index) { return "Step " + (index + 1) + " of " + steps.size(); }

    /** "Logging 03:07" clock text for an elapsed time. */
    public static String clock(long elapsedMs) {
        long s = Math.max(0, elapsedMs) / 1000;
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    /** "10 Hz" or "5 Hz". */
    public String rateText() { return hz + " Hz"; }

    /** idle-20261124-2258.csv */
    public String fileName(long wallMs, ZoneId zone) {
        return id + "-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.US).withZone(zone)
                .format(Instant.ofEpochMilli(wallMs)) + ".csv";
    }
}
