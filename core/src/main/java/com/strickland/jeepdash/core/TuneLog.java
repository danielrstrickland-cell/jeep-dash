package com.strickland.jeepdash.core;

import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
 * The engine has no UI yet and is not started by anything.
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

    /** Uses the Jeep's catalog. */
    public TuneLog(Writer out, LogProfile profile, long wallStartMs) throws IOException {
        this(out, profile, wallStartMs, Catalog::jeep);
    }

    public TuneLog(Writer out, LogProfile profile, long wallStartMs, Function<String, Param> lookup) throws IOException {
        this.out = out;
        this.minGapMs = profile.hz >= 1000 ? 0 : Math.round(1000.0 / profile.hz) - 1;   // -1: a tick a hair early still counts
        for (LogProfile.Want want : profile.channels) {
            Param p = lookup.apply(want.paramId);
            if (p == null || p.tablet) missing.add(want.label);
            else channels.add(new Channel(p, want.label));
        }
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
        for (Channel c : channels) ids.add(c.param.id);
        return ids;
    }

    public List<String> missing() { return Collections.unmodifiableList(missing); }

    public int rowCount() { return rows; }

    /**
     * Writes a row if the profile's sample gap has passed since the last one. timeMs goes in the first column;
     * nowMs is the clock the readings were stamped with. Returns true when a row was written.
     */
    public boolean row(long timeMs, long nowMs, LiveValues v) throws IOException {
        if (lastRowMs != Long.MIN_VALUE && timeMs - lastRowMs < minGapMs) return false;
        lastRowMs = timeMs;
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

    public void flush() throws IOException { out.flush(); }

    public void close() throws IOException { out.close(); }
}
