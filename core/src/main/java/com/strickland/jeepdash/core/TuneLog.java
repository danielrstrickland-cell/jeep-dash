package com.strickland.jeepdash.core;

import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Records one tune log as CSV, driven by a {@link LogProfile}. Channels the app cannot read are left out and
 * named in a "# missing=" line. File layout:
 * <pre>
 * # profile=idle
 * # started=2026-11-24T22:58:00Z
 * # missing=Idle air control
 * time_ms,RPM (rpm),MAP (kPa),...
 * </pre>
 * Channels that come from the tablet (GPS) are written only when the caller says they are available; otherwise
 * they are listed as missing. A cruise log pairs the engine computer's raw speed with GPS speed as it goes and,
 * when it is closed, adds "# speed_ratio=" lines (see {@link SpeedCalibration}).
 */
public final class TuneLog {
    private static final class Channel {
        final Param param;
        final String label;
        Channel(Param param, String label) { this.param = param; this.label = label; }
    }

    private final Writer out;
    private final List<Channel> channels = new ArrayList<>();
    private final List<String> missing = new ArrayList<>();
    private final long minGapMs;
    private long lastRowMs = Long.MIN_VALUE;
    private int rows;
    private final SpeedCalibration calibration;
    private SpeedCalibration.Result speedResult;
    private long lastGpsAt = Long.MIN_VALUE;

    /** Uses the Jeep's catalog. */
    public TuneLog(Writer out, LogProfile profile, long wallStartMs) throws IOException {
        this(out, profile, wallStartMs, Catalog::jeep);
    }

    public TuneLog(Writer out, LogProfile profile, long wallStartMs, Function<String, Param> lookup) throws IOException {
        this(out, profile, wallStartMs, lookup, Collections.<String>emptySet());
    }

    /** tabletIds: the tablet-sourced values (like gps_mph) that can be read right now. */
    public TuneLog(Writer out, LogProfile profile, long wallStartMs, Function<String, Param> lookup, Set<String> tabletIds)
            throws IOException {
        this.out = out;
        this.minGapMs = profile.hz >= 1000 ? 0 : Math.round(1000.0 / profile.hz) - 1;   // -1: a tick a hair early still counts
        for (LogProfile.Want want : profile.channels) {
            Param p = lookup.apply(want.paramId);
            if (p == null || (p.tablet && !tabletIds.contains(p.id))) missing.add(want.label);
            else channels.add(new Channel(p, want.label));
        }
        boolean pairs = false, pcm = false;
        for (Channel c : channels) {
            if (c.param.id.equals("speed")) pcm = true;
            if (c.param.id.equals("gps_mph")) pairs = true;
        }
        this.calibration = pairs && pcm ? new SpeedCalibration() : null;
        StringBuilder sb = new StringBuilder("# profile=").append(profile.id).append('\n')
                .append("# started=").append(Instant.ofEpochMilli(wallStartMs)).append('\n');
        if (!missing.isEmpty()) sb.append("# missing=").append(String.join(";", missing)).append('\n');
        sb.append("time_ms");
        for (Channel c : channels) sb.append(',').append(c.label).append(" (").append(ascii(c.param.baseUnit)).append(')');
        out.write(sb.append('\n').toString());
    }

    /** Degree signs would turn to junk in a spreadsheet, so units are written in plain letters. */
    static String ascii(String unit) {
        return unit.replace("°F", "degF").replace("°", "deg");
    }

    /** The Catalog ids to ask the link to read for this log (give them to LinkService.setActiveParams). */
    public List<String> paramIds() {
        List<String> ids = new ArrayList<>();
        for (Channel c : channels) if (!c.param.tablet) ids.add(c.param.id);
        return ids;
    }

    /** Every value in the log, tablet ones included (what the screen shows tiles for). */
    public List<String> channelIds() {
        List<String> ids = new ArrayList<>();
        for (Channel c : channels) ids.add(c.param.id);
        return ids;
    }

    /** True when this log pairs engine-computer speed with GPS speed. */
    public boolean measuresSpeed() { return calibration != null; }

    /** The speedometer result, once the log is closed; null if there was too little good data or no GPS. */
    public SpeedCalibration.Result speedResult() { return speedResult; }

    /** How many good speed pairs have been collected so far. */
    public int speedSamples() { return calibration == null ? 0 : calibration.samples(); }

    public List<String> missing() { return Collections.unmodifiableList(missing); }

    public int rowCount() { return rows; }

    /**
     * Writes a row if the profile's sample gap has passed since the last one. timeMs goes in the first column;
     * nowMs is the clock the readings were stamped with. Returns true when a row was written.
     */
    public boolean row(long timeMs, long nowMs, LiveValues v) throws IOException {
        if (lastRowMs != Long.MIN_VALUE && timeMs - lastRowMs < minGapMs) return false;
        lastRowMs = timeMs;
        feedCalibration(v, nowMs);
        StringBuilder sb = new StringBuilder().append(timeMs);
        for (Channel c : channels) {
            Double d = v.get(c.param.id, nowMs);
            sb.append(',');
            if (d != null) sb.append(d);
        }
        out.write(sb.append('\n').toString());
        rows++;
        return true;
    }

    /** Pairs the raw car speed with each new GPS fix once (a fix seen again at the next row is not counted twice). */
    private void feedCalibration(LiveValues v, long nowMs) {
        if (calibration == null) return;
        Long at = v.timeOf("gps_mph");
        if (at == null || at == lastGpsAt) return;
        Double gps = v.get("gps_mph", nowMs), pcm = v.raw("speed", nowMs), acc = v.get("gps_accuracy_m", nowMs);
        if (gps == null || pcm == null) return;
        lastGpsAt = at;
        calibration.add(pcm, gps, acc == null ? Double.NaN : acc, nowMs);
    }

    public void flush() throws IOException { out.flush(); }

    /** Writes the speedometer footer (cruise logs) and closes. */
    public void close() throws IOException {
        if (calibration != null) {
            speedResult = calibration.result();
            StringBuilder sb = new StringBuilder();
            if (speedResult == null) {
                sb.append("# speed_ratio=none\n# speed_samples=").append(calibration.samples()).append('\n');
            } else {
                sb.append("# speed_ratio=").append(String.format(java.util.Locale.US, "%.4f", speedResult.ratio)).append('\n')
                        .append("# speed_samples=").append(speedResult.samples).append('\n')
                        .append("# speed_spread=").append(String.format(java.util.Locale.US, "%.4f", speedResult.spread)).append('\n')
                        .append("# suggested_constant=").append(speedResult.constantHex()).append('\n');
            }
            try { out.write(sb.toString()); } catch (IOException e) { out.close(); throw e; }
        }
        out.close();
    }
}
