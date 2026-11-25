package com.strickland.jeepdash.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Learning alerts: the dash learns what normal looks like for each car at warm idle (and the coolant once
 * settled on the move), then says so when a reading drifts away from it, trouble code or not. The owner's
 * example: manifold pressure at idle used to be 52 kPa and weeks later it is 58, so he looks for a vacuum leak.
 *
 * How it works (docs/superpowers/specs/2026-11-20-jeep-dash-design.md, "Learning alerts"):
 * - Each drive keeps its warm-idle readings and takes the middle one (the median) of each.
 * - Learning lasts until at least 7 days have passed since the first reading and at least 5 drives had half a
 *   minute of warm idle. Then, at the start of the next drive, the normal for each reading is frozen: the middle
 *   of the drives' own middles, and their spread (the middle distance from it, scaled to a standard deviation).
 *   Frozen, so a slow drift is caught rather than taken in.
 * - A reading is flagged when two drives in a row (that had it) read outside normal on the same side, by more
 *   than both three spreads and the reading's own minimum (3 kPa, 50 rpm, 3% trim, 0.3 V and so on).
 * Times: nowMs on the link's clock, wallMs the calendar clock (days and drives must survive a restart). Fed by
 * the link service's ticker and read by the screen, so every method is synchronized.
 */
public final class NormalReadings {
    static final long DAY_MS = 24L * 3600 * 1000;
    static final int LEARN_DRIVES = 5, LEARN_DAYS = 7;
    /** Warm idle a drive needs before its idle readings count. */
    static final long MIN_IDLE_MS = 30_000;
    /** Drives a reading needs before its normal can be frozen. */
    static final int MIN_DRIVES_PER_READING = 3;
    /** How many spreads from the middle still count as normal. */
    static final double SPREADS = 3;
    /** Front O2 above this is rich. */
    static final double RICH_V = 0.45;
    /** Drives kept; older ones are dropped once the normal is frozen anyway. */
    static final int MAX_DRIVES = 60;
    /** Readings kept per value in one drive (the latest). */
    static final int MAX_SAMPLES = 600;
    private static final long NEVER = Long.MIN_VALUE / 2;
    private static final String HEADER = "normal-readings 1 ";

    /** How a watched reading is made from its live value. */
    public enum Kind {
        IDLE(5),            // the middle of the warm-idle readings
        RICH_SHARE(10),     // the share of warm-idle readings that were rich, in percent
        DRIVING(20);        // the middle of the readings once settled on the move

        final int minSamples;
        Kind(int minSamples) { this.minSamples = minSamples; }
    }

    /** One watched reading: what it is made from, what it is called, and the smallest change worth a word. */
    public static final class Watch {
        public final String id, source, name, unit;
        public final double minMargin;
        public final int decimals;
        public final Kind kind;

        Watch(String id, String source, String name, String unit, double minMargin, int decimals, Kind kind) {
            this.id = id; this.source = source; this.name = name; this.unit = unit;
            this.minMargin = minMargin; this.decimals = decimals; this.kind = kind;
        }
    }

    private static Watch atIdle(String id, String name, String unit, double min, int decimals) {
        return new Watch(id, id, name, unit, min, decimals, Kind.IDLE);
    }

    private static final Watch COOLANT = new Watch("coolant_warm", "coolant", "Coolant once warmed up", "°F", 8, 0, Kind.DRIVING);

    private static final List<Watch> JEEP = Collections.unmodifiableList(Arrays.asList(
            atIdle("map_kpa", "Idle manifold pressure", "kPa", 3, 0),
            atIdle("rpm", "Idle speed", "rpm", 50, 0),
            atIdle("iac", "Idle air motor", "steps", 10, 0),
            atIdle("stft", "Idle short-term fuel trim", "%", 3, 1),
            atIdle("ltft", "Idle long-term fuel trim", "%", 3, 1),
            atIdle("inj_ms", "Idle injector time", "ms", 0.3, 2),
            atIdle("batt_v", "Idle battery voltage", "V", 0.3, 1),
            // The share of time the front sensor reads rich. It is read only every few seconds among the
            // others, too slowly to count its switching, but a slow random sample still shows the share fairly.
            new Watch("o2_rich", "o2_front", "Idle front O2 time rich", "%", 15, 0, Kind.RICH_SHARE),
            COOLANT));

    private static final List<Watch> PORSCHE = Collections.unmodifiableList(Arrays.asList(
            atIdle("rpm", "Idle speed", "rpm", 50, 0),
            atIdle("batt_v", "Idle battery voltage", "V", 0.3, 1),
            atIdle("maf", "Idle air flow", "lb/h", 10, 0),
            atIdle("trim_1", "Idle fuel trim bank 1", "%", 3, 1),
            atIdle("trim_2", "Idle fuel trim bank 2", "%", 3, 1),
            atIdle("inj_ms", "Idle injection time", "ms", 0.3, 2),
            atIdle("load", "Idle engine load", "%", 5, 0),
            COOLANT));

    /** What is watched on each car, in the order the menu lists it. */
    public static List<Watch> watched(boolean porsche) { return porsche ? PORSCHE : JEEP; }

    /** Each car's memory is kept under its own name. */
    public static String storeKey(boolean porsche) { return porsche ? "normal_readings_p" : "normal_readings"; }

    /**
     * What to read: the given list plus engine speed; road speed and coolant as well while the engine speed is
     * near idle (to tell whether it is warm idle); and the watched idle values only while stopped and warm, so
     * gauges are not slowed on the move. Adds nothing when nothing is being read. The given set is not changed.
     */
    public static Set<String> withInputs(Set<String> ids, boolean porsche, boolean nearIdle, boolean stoppedWarm) {
        Set<String> out = new LinkedHashSet<>(ids);
        if (ids.isEmpty()) return out;
        out.add("rpm");
        if (nearIdle || stoppedWarm) {
            out.add("speed");
            out.add("coolant");
        }
        if (stoppedWarm) for (Watch w : watched(porsche)) if (w.kind != Kind.DRIVING) out.add(w.source);
        return out;
    }

    // ---------------- per drive ----------------

    /** One drive's figures: each reading's value for the drive (only those with enough readings). */
    private static final class Drive {
        final long wallStart;
        long idleMs;
        final Map<String, Double> values = new LinkedHashMap<>();
        Drive(long wallStart) { this.wallStart = wallStart; }
        boolean counts() { return idleMs >= MIN_IDLE_MS; }
    }

    /** A frozen normal. Drives that started after afterWall are compared with it. */
    private static final class Normal {
        double median, spread;
        long afterWall;
        Normal(double median, double spread, long afterWall) { this.median = median; this.spread = spread; this.afterWall = afterWall; }
    }

    private final boolean porsche;
    private final List<Watch> watches;
    private WarmIdle idle;
    private long firstWall = NEVER;
    private boolean learned;
    private final List<Drive> drives = new ArrayList<>();          // finished drives, oldest first
    private final Map<String, Normal> normals = new LinkedHashMap<>();
    // the drive under way
    private long driveKey = NEVER;
    private Drive current = new Drive(NEVER);
    private final Map<String, List<Double>> samples = new HashMap<>();
    private final Map<String, Long> lastTaken = new HashMap<>();
    private long lastIdleMs = NEVER;
    private final Set<String> dismissed = new HashSet<>();
    private boolean unsaved;

    public NormalReadings(boolean porsche) {
        this.porsche = porsche;
        watches = watched(porsche);
        idle = new WarmIdle(porsche);
    }

    public boolean porsche() { return porsche; }

    /**
     * The drive that started at startedMs on the link's clock (see LinkService): a different one finishes the
     * last drive, freezes the normal once learning is over, and starts counting the new drive.
     */
    public synchronized void drive(long startedMs, long wallMs) {
        if (startedMs == driveKey) return;
        driveKey = startedMs;
        finishDrive();
        current = new Drive(wallMs);
        samples.clear();
        lastTaken.clear();
        lastIdleMs = NEVER;
        idle = new WarmIdle(porsche);
        dismissed.clear();
        if (!learned && firstWall != NEVER && wallMs - firstWall >= LEARN_DAYS * DAY_MS && countingDrives(false) >= LEARN_DRIVES)
            learned = true;
        if (learned) freeze();
    }

    private void finishDrive() {
        summarise();
        if (current.values.isEmpty()) return;
        drives.add(current);
        while (drives.size() > MAX_DRIVES) drives.remove(0);
        unsaved = true;
    }

    /** Freezes the normal of every reading that has enough drives and none yet. */
    private void freeze() {
        for (Watch w : watches) {
            if (normals.containsKey(w.id)) continue;
            List<Double> xs = new ArrayList<>();
            for (Drive d : drives) if (d.values.containsKey(w.id)) xs.add(d.values.get(w.id));
            if (xs.size() < MIN_DRIVES_PER_READING) continue;
            double[] a = toArray(xs);
            normals.put(w.id, new Normal(median(a), spread(a), drives.get(drives.size() - 1).wallStart));
            unsaved = true;
        }
    }

    /** Takes the latest readings: once a second is plenty. */
    public synchronized void update(LiveValues v, long nowMs, long wallMs) {
        idle.update(v.get("speed", nowMs), v.get("rpm", nowMs), v.get("coolant", nowMs), nowMs);
        if (idle.idle(nowMs)) {
            if (lastIdleMs != NEVER && nowMs - lastIdleMs <= WarmIdle.GAP_MS) current.idleMs += nowMs - lastIdleMs;
            lastIdleMs = nowMs;
            for (Watch w : watches) if (w.kind != Kind.DRIVING) take(w, v, nowMs, wallMs);
        } else {
            lastIdleMs = NEVER;
        }
        if (idle.warmDriving(nowMs)) for (Watch w : watches) if (w.kind == Kind.DRIVING) take(w, v, nowMs, wallMs);
    }

    /** One new reading of a watched value (a reading already taken is not taken twice). */
    private void take(Watch w, LiveValues v, long nowMs, long wallMs) {
        Double x = v.get(w.source, nowMs);
        Long t = v.timeOf(w.source);
        if (x == null || t == null || t.equals(lastTaken.get(w.id))) return;
        lastTaken.put(w.id, t);
        List<Double> s = samples.computeIfAbsent(w.id, k -> new ArrayList<>());
        s.add(w.kind == Kind.RICH_SHARE ? (x > RICH_V ? 100.0 : 0.0) : x);
        if (s.size() > MAX_SAMPLES) s.remove(0);
        if (firstWall == NEVER) firstWall = wallMs;
        unsaved = true;
    }

    /** Works the drive under way's figures out from its readings so far. */
    private void summarise() {
        current.values.clear();
        for (Watch w : watches) {
            List<Double> s = samples.get(w.id);
            if (s == null || s.size() < w.kind.minSamples) continue;
            if (w.kind != Kind.DRIVING && !current.counts()) continue;
            double[] a = toArray(s);
            current.values.put(w.id, w.kind == Kind.RICH_SHARE ? mean(a) : median(a));
        }
    }

    /** True while the car is stopped, idling and warm by the latest readings (see withInputs). */
    public synchronized boolean stoppedWarm() { return idle.stoppedWarm(); }

    /** True while engine speed has been near idle lately (see withInputs). */
    public synchronized boolean nearIdle(long nowMs) { return idle.nearIdle(nowMs); }

    // ---------------- what it has to say ----------------

    public synchronized boolean learning() { return !learned; }

    private int countingDrives(boolean withCurrent) {
        int n = 0;
        for (Drive d : drives) if (d.counts()) n++;
        if (withCurrent && current.counts()) n++;
        return n;
    }

    /** Drives counted toward learning, up to LEARN_DRIVES; LEARN_DRIVES once learned. */
    public synchronized int drivesLearned() { return learned ? LEARN_DRIVES : Math.min(countingDrives(true), LEARN_DRIVES); }

    /** "Learning normal: drive 3 of 5, day 4 of 7" while learning; null once learned. */
    public synchronized String progress(long wallMs) {
        if (learned) return null;
        if (firstWall == NEVER) return "Learning normal: waiting for the first warm idle";
        int n = countingDrives(true);
        long days = (wallMs - firstWall) / DAY_MS;
        if (n >= LEARN_DRIVES && wallMs - firstWall >= LEARN_DAYS * DAY_MS)
            return "Learning normal: done, watching from the next drive";
        return "Learning normal: drive " + Math.min(n, LEARN_DRIVES) + " of " + LEARN_DRIVES
                + ", day " + Math.min(days + 1, LEARN_DAYS) + " of " + LEARN_DAYS;
    }

    public enum Status {
        LEARNING, NO_READING, NORMAL, OUTSIDE_ONCE, HIGH, LOW;

        /** Higher or lower than usual on two drives in a row. */
        public boolean flagged() { return this == HIGH || this == LOW; }
    }

    /** One line of the Normal readings list. median, low and high are null until learned; latest when none. */
    public static final class Row {
        public final Watch watch;
        public final Double median, low, high, latest;
        public final Status status;

        Row(Watch watch, Double median, Double low, Double high, Double latest, Status status) {
            this.watch = watch; this.median = median; this.low = low; this.high = high;
            this.latest = latest; this.status = status;
        }
    }

    /** Each drive's value for one reading, oldest first, the drive under way last (start time and value). */
    private List<double[]> history(String id) {
        summarise();
        List<double[]> out = new ArrayList<>();
        for (Drive d : drives) if (d.values.containsKey(id)) out.add(new double[]{d.wallStart, d.values.get(id)});
        if (current.values.containsKey(id)) out.add(new double[]{current.wallStart, current.values.get(id)});
        return out;
    }

    public synchronized List<Row> rows() {
        List<Row> out = new ArrayList<>();
        for (Watch w : watches) out.add(row(w));
        return out;
    }

    private Row row(Watch w) {
        List<double[]> h = history(w.id);
        Normal n = normals.get(w.id);
        if (n == null) return new Row(w, null, null, null, h.isEmpty() ? null : h.get(h.size() - 1)[1], Status.LEARNING);
        double margin = Math.max(SPREADS * n.spread, w.minMargin);
        List<Double> since = new ArrayList<>();
        for (double[] e : h) if (e[0] > n.afterWall) since.add(e[1]);
        Status s;
        Double latest = since.isEmpty() ? null : since.get(since.size() - 1);
        if (latest == null) s = Status.NO_READING;
        else {
            int side = side(latest, n.median, margin);
            int before = since.size() >= 2 ? side(since.get(since.size() - 2), n.median, margin) : 0;
            s = side == 0 ? Status.NORMAL : side != before ? Status.OUTSIDE_ONCE : side > 0 ? Status.HIGH : Status.LOW;
        }
        return new Row(w, n.median, n.median - margin, n.median + margin, latest, s);
    }

    /** +1 above normal, -1 below, 0 inside. */
    private static int side(double x, double median, double margin) {
        return x > median + margin ? 1 : x < median - margin ? -1 : 0;
    }

    /**
     * The one line for the dash, or null when there is nothing to say (or it was dismissed this drive):
     * "Idle manifold pressure is higher than usual: 58 kPa now, normally 52". units gives the unit each gauge
     * is shown in, by value id; one that does not fit the reading is ignored.
     */
    public synchronized String notice(Map<String, String> units) {
        Row first = null;
        int more = 0;
        for (Watch w : watches) {
            if (dismissed.contains(w.id)) continue;
            Row r = row(w);
            if (!r.status.flagged()) continue;
            if (first == null) first = r;
            else more++;
        }
        if (first == null) return null;
        String unit = unitFor(first.watch, units);
        String line = first.watch.name + " is " + (first.status == Status.HIGH ? "higher" : "lower") + " than usual: "
                + withUnit(show(first.latest, first.watch, unit), unit) + " now, normally " + show(first.median, first.watch, unit);
        return more == 0 ? line : line + " (and " + more + " more in Normal readings)";
    }

    /** One line of the Normal readings list: its normal range, the latest drive's figure and what that means. */
    public static String describe(Row r, Map<String, String> units) {
        String unit = unitFor(r.watch, units);
        StringBuilder sb = new StringBuilder(r.watch.name).append(": ");
        if (r.median == null) sb.append("still learning");
        else sb.append("normally ").append(show(r.low, r.watch, unit)).append(" to ").append(withUnit(show(r.high, r.watch, unit), unit));
        if (r.latest != null) sb.append(", latest ").append(withUnit(show(r.latest, r.watch, unit), unit));
        switch (r.status) {
            case NO_READING: sb.append(", no reading since it learned"); break;
            case NORMAL: sb.append(" (normal)"); break;
            case OUTSIDE_ONCE: sb.append(" (outside normal on the last drive)"); break;
            case HIGH: sb.append(" (higher than usual, two drives in a row)"); break;
            case LOW: sb.append(" (lower than usual, two drives in a row)"); break;
            default: break;
        }
        return sb.toString();
    }

    private static String unitFor(Watch w, Map<String, String> units) {
        String u = units.get(w.source);
        return u != null && Units.offers(w.unit, u) ? u : w.unit;
    }

    private static String show(double x, Watch w, String unit) {
        int d = unit.equals(w.unit) ? w.decimals : "psi".equals(unit) || "g/s".equals(unit) ? 1 : "bar".equals(unit) ? 2 : 0;
        return String.format(Locale.US, "%." + d + "f", Units.convert(x, w.unit, unit));
    }

    private static String withUnit(String number, String unit) {
        return unit.startsWith("%") || unit.startsWith("°") ? number + unit : number + " " + unit;
    }

    // ---------------- what the owner can do ----------------

    /** Hides the notice until the next drive. Listed readings stay as they are. */
    public synchronized void dismiss() {
        for (Watch w : watches) if (row(w).status.flagged()) dismissed.add(w.id);
    }

    /** "Accept current as normal": the latest drive's figure becomes the normal (spread kept). False if none. */
    public synchronized boolean accept(String id) {
        Normal n = normals.get(id);
        List<double[]> h = history(id);
        if (n == null || h.isEmpty()) return false;
        double[] last = h.get(h.size() - 1);
        if (last[0] <= n.afterWall) return false;           // nothing read since the normal was set
        n.median = last[1];
        n.afterWall = (long) last[0] - 1;                   // that drive is the first one compared with it
        dismissed.remove(id);
        unsaved = true;
        return true;
    }

    /** "Start learning again": forgets every drive and normal of this car. */
    public synchronized void startAgain() {
        firstWall = NEVER;
        learned = false;
        drives.clear();
        normals.clear();
        current = new Drive(current.wallStart);
        samples.clear();
        lastTaken.clear();
        lastIdleMs = NEVER;
        dismissed.clear();
        unsaved = true;
    }

    // ---------------- memory ----------------

    /** True when something changed since the last save. */
    public synchronized boolean unsaved() { return unsaved; }

    /** The memory as text, the drive under way included (saved as a finished drive). */
    public synchronized String save() {
        summarise();
        StringBuilder sb = new StringBuilder(HEADER).append(porsche ? "porsche" : "jeep").append('\n');
        sb.append("learned ").append(learned).append('\n');
        if (firstWall != NEVER) sb.append("first ").append(firstWall).append('\n');
        for (Map.Entry<String, Normal> e : normals.entrySet())
            sb.append("normal ").append(e.getKey()).append(' ').append(e.getValue().median).append(' ')
                    .append(e.getValue().spread).append(' ').append(e.getValue().afterWall).append('\n');
        List<Drive> all = new ArrayList<>(drives);
        if (!current.values.isEmpty()) all.add(current);
        for (Drive d : all) {
            sb.append("drive ").append(d.wallStart).append(' ').append(d.idleMs);
            for (Map.Entry<String, Double> e : d.values.entrySet()) sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
            sb.append('\n');
        }
        unsaved = false;
        return sb.toString();
    }

    /** The memory saved for this car; empty (learning from the start) when there is none or it is another car's. */
    public static NormalReadings restore(String text, boolean porsche) {
        NormalReadings n = new NormalReadings(porsche);
        if (text == null || !text.startsWith(HEADER + (porsche ? "porsche" : "jeep") + "\n")) return n;
        Set<String> known = new HashSet<>();
        for (Watch w : n.watches) known.add(w.id);
        try {
            for (String line : text.split("\n")) {
                String[] f = line.split(" ");
                switch (f[0]) {
                    case "learned": n.learned = Boolean.parseBoolean(f[1]); break;
                    case "first": n.firstWall = Long.parseLong(f[1]); break;
                    case "normal":
                        if (known.contains(f[1]))
                            n.normals.put(f[1], new Normal(Double.parseDouble(f[2]), Double.parseDouble(f[3]), Long.parseLong(f[4])));
                        break;
                    case "drive": {
                        Drive d = new Drive(Long.parseLong(f[1]));
                        d.idleMs = Long.parseLong(f[2]);
                        for (int i = 3; i < f.length; i++) {
                            String[] kv = f[i].split("=");
                            if (known.contains(kv[0])) d.values.put(kv[0], Double.parseDouble(kv[1]));
                        }
                        if (!d.values.isEmpty()) n.drives.add(d);
                        break;
                    }
                    default: break;
                }
            }
        } catch (RuntimeException e) {
            return new NormalReadings(porsche);               // unreadable: start again rather than half-load it
        }
        return n;
    }

    // ---------------- the sums ----------------

    /** The middle value (the average of the two middle ones for an even count). */
    static double median(double[] a) {
        double[] s = a.clone();
        Arrays.sort(s);
        int n = s.length;
        return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2;
    }

    /** How far the values usually sit from their middle: the middle distance, x 1.4826 to match a standard deviation. */
    static double spread(double[] a) {
        double m = median(a);
        double[] d = new double[a.length];
        for (int i = 0; i < a.length; i++) d[i] = Math.abs(a[i] - m);
        return 1.4826 * median(d);
    }

    private static double mean(double[] a) {
        double s = 0;
        for (double x : a) s += x;
        return s / a.length;
    }

    private static double[] toArray(List<Double> xs) {
        double[] a = new double[xs.size()];
        for (int i = 0; i < a.length; i++) a[i] = xs.get(i);
        return a;
    }
}
