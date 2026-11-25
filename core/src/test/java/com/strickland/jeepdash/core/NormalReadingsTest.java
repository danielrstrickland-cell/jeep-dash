package com.strickland.jeepdash.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class NormalReadingsTest {
    static final long DAY = 24L * 3600 * 1000, T0 = 1_760_000_000_000L;

    // ---------------- warm idle ----------------

    static WarmIdle idleFor(boolean porsche, int seconds, double mph, double rpm, double coolantF) {
        WarmIdle w = new WarmIdle(porsche);
        for (int s = 0; s <= seconds; s++) w.update(mph, rpm, coolantF, s * 1000L);
        return w;
    }

    public static void warmIdleNeedsTenSteadySeconds() {
        T.ok(!idleFor(false, 9, 0, 700, 195).idle(9000), "nine seconds");
        T.ok(idleFor(false, 10, 0, 700, 195).idle(10000), "ten seconds");
        T.ok(idleFor(false, 0, 0, 700, 195).stoppedWarm(), "the conditions themselves hold at once (for polling)");
    }

    public static void coldMovingOrRevvingIsNotWarmIdle() {
        T.ok(!idleFor(false, 20, 0, 700, 175).idle(20000), "Jeep below 180 F");
        T.ok(!idleFor(false, 20, 3, 700, 195).idle(20000), "rolling");
        T.ok(!idleFor(false, 20, 0, 1500, 195).idle(20000), "revving in Park");
        T.ok(!idleFor(false, 20, 0, 300, 195).idle(20000), "stalling");
        T.ok(!idleFor(false, 20, 0, 1500, 195).stoppedWarm(), "and not worth polling the idle values for");
    }

    public static void eachCarHasItsOwnWarmTemperature() {
        T.ok(idleFor(true, 10, 0, 650, 177).idle(10000), "Porsche is warm at 80 C (176 F)");
        T.ok(!idleFor(false, 10, 0, 650, 177).idle(10000), "the Jeep is not warm until 180 F");
    }

    public static void aBreakStartsTheTenSecondsAgain() {
        WarmIdle w = new WarmIdle(false);
        for (int s = 0; s < 8; s++) w.update(0.0, 700.0, 195.0, s * 1000L);
        w.update(0.0, 1500.0, 195.0, 8000);
        for (int s = 9; s < 18; s++) w.update(0.0, 700.0, 195.0, s * 1000L);
        T.ok(!w.idle(17000), "nine seconds since the blip");
        w.update(0.0, 700.0, 195.0, 19000);
        T.ok(w.idle(19000), "ten");
        w.update(null, 700.0, 195.0, 20000);
        T.ok(!w.idle(20000), "a missing reading ends it");
    }

    public static void coolantCountsAsSettledAfterFiveWarmMinutesOnTheMove() {
        WarmIdle w = new WarmIdle(false);
        for (int s = 0; s < 299; s++) w.update(45.0, 1800.0, 196.0, s * 1000L);
        T.ok(!w.warmDriving(298000), "not yet");
        w.update(45.0, 1800.0, 196.0, 300000);
        T.ok(w.warmDriving(300000), "five warm minutes");
        w.update(5.0, 900.0, 196.0, 301000);
        T.ok(!w.warmDriving(301000), "crawling does not count");
    }

    // ---------------- the rig: drives on given days ----------------

    static Map<String, Double> jeepIdle() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("rpm", 700.0);
        m.put("map_kpa", 52.0);
        m.put("iac", 30.0);
        m.put("stft", 0.0);
        m.put("ltft", 2.0);
        m.put("inj_ms", 3.0);
        m.put("batt_v", 14.0);
        return m;
    }

    static Map<String, Double> with(Map<String, Double> base, String id, double v) {
        Map<String, Double> m = new LinkedHashMap<>(base);
        m.put(id, v);
        return m;
    }

    static final class Rig {
        final boolean porsche;
        NormalReadings n;
        final LiveValues v = new LiveValues();
        long now = 1000, wall = T0;

        Rig(boolean porsche) {
            this.porsche = porsche;
            n = new NormalReadings(porsche);
        }

        Rig start(double day) {
            now += 3_600_000;
            wall = T0 + (long) (day * DAY);
            n.drive(now, wall);
            return this;
        }

        /** Seconds of steady warm idle (after the ten it takes to count as steady), with these readings. */
        Rig idle(int seconds, Map<String, Double> readings) {
            for (int s = 0; s <= WarmIdle.STEADY_MS / 1000 + seconds; s++) {
                now += 1000;
                wall += 1000;
                v.put("speed", 0, now);
                v.put("coolant", 195, now);
                if (!porsche && !readings.containsKey("o2_front")) v.put("o2_front", s % 2 == 0 ? 0.8 : 0.1, now);
                for (Map.Entry<String, Double> e : readings.entrySet()) v.put(e.getKey(), e.getValue(), now);
                n.update(v, now, wall);
            }
            return this;
        }

        Rig cruise(int seconds, double coolantF) {
            for (int s = 0; s < seconds; s++) {
                now += 1000;
                wall += 1000;
                v.put("speed", 50, now);
                v.put("rpm", 2000, now);
                v.put("coolant", coolantF, now);
                n.update(v, now, wall);
            }
            return this;
        }

        /** Five ordinary drives over eight days, then the next drive starts: the normal is frozen. */
        Rig learned(Map<String, Double> idle) {
            double[] days = {0, 2, 4, 6, 7.5};
            for (double d : days) start(d).idle(40, idle);
            start(8);
            T.ok(!n.learning(), "learned after five drives and seven days");
            return this;
        }

        NormalReadings.Row row(String id) {
            for (NormalReadings.Row r : n.rows()) if (r.watch.id.equals(id)) return r;
            throw new AssertionError("no row " + id);
        }
    }

    // ---------------- what is watched, and when it is read ----------------

    public static void watchesTheAgreedReadings() {
        T.eq(Arrays.asList("map_kpa", "rpm", "iac", "stft", "ltft", "inj_ms", "batt_v", "o2_rich", "coolant_warm"),
                ids(NormalReadings.watched(false)), "Jeep");
        T.eq(Arrays.asList("rpm", "batt_v", "maf", "trim_1", "trim_2", "inj_ms", "load", "coolant_warm"),
                ids(NormalReadings.watched(true)), "Porsche");
        for (NormalReadings.Watch w : NormalReadings.watched(false))
            T.ok(Catalog.jeep(w.source) != null, "Jeep reads " + w.source);
        for (NormalReadings.Watch w : NormalReadings.watched(true))
            T.ok(PorscheCatalog.byId(w.source) != null, "Porsche reads " + w.source);
    }

    static List<String> ids(List<NormalReadings.Watch> ws) {
        List<String> out = new java.util.ArrayList<>();
        for (NormalReadings.Watch w : ws) out.add(w.id);
        return out;
    }

    public static void idleValuesAreReadOnlyWhileStoppedAndWarm() {
        Set<String> screen = new LinkedHashSet<>(Arrays.asList("throttle"));
        T.eq(new LinkedHashSet<>(Arrays.asList("throttle", "rpm")),
                NormalReadings.withInputs(screen, false, false, false), "driving: engine speed only");
        T.eq(new LinkedHashSet<>(Arrays.asList("throttle", "rpm", "speed", "coolant")),
                NormalReadings.withInputs(screen, false, true, false), "near idle: road speed and coolant to tell");
        Set<String> idle = NormalReadings.withInputs(screen, false, true, true);
        T.ok(idle.containsAll(Arrays.asList("map_kpa", "iac", "stft", "ltft", "inj_ms", "batt_v", "o2_front")), "stopped and warm: Jeep idle values");
        Set<String> p = NormalReadings.withInputs(screen, true, true, true);
        T.ok(p.containsAll(Arrays.asList("maf", "trim_1", "trim_2", "inj_ms", "load", "batt_v")), "Porsche idle values");
        T.ok(!p.contains("o2_front") && !p.contains("iac"), "nothing of the Jeep's on the Porsche");
        T.eq(Collections.emptySet(), NormalReadings.withInputs(Collections.emptySet(), false, true, true),
                "the link is not woken for learning alone");
        T.eq(new LinkedHashSet<>(Arrays.asList("throttle")), screen, "the given set is not changed");
    }

    public static void theRigTellsDrivingFromIdle() {
        Rig r = new Rig(false).start(0).cruise(20, 195);
        T.ok(!r.n.nearIdle(r.now) && !r.n.stoppedWarm(), "cruising");
        r.idle(1, jeepIdle());
        T.ok(r.n.nearIdle(r.now) && r.n.stoppedWarm(), "stopped and warm");
    }

    // ---------------- learning ----------------

    public static void learningNeedsSevenDaysAndFiveDrives() {
        Rig quick = new Rig(false);
        for (int d = 0; d < 5; d++) quick.start(d).idle(40, jeepIdle());
        quick.start(5);
        T.ok(quick.n.learning(), "five drives in five days: still learning");
        T.eq("Learning normal: drive 5 of 5, day 5 of 7", quick.n.progress(quick.wall), "progress");
        quick.start(7.1);
        T.ok(!quick.n.learning(), "and on day eight it is done");
        T.eq(null, quick.n.progress(quick.wall), "no progress line once learned");

        Rig few = new Rig(false);
        for (int d = 0; d < 4; d++) few.start(d * 3).idle(40, jeepIdle());
        few.start(12);
        T.ok(few.n.learning(), "four drives in twelve days: still learning");
        T.eq("Learning normal: drive 4 of 5, day 7 of 7", few.n.progress(few.wall), "progress");
    }

    public static void progressCountsTheDriveUnderWay() {
        Rig r = new Rig(false);
        T.eq("Learning normal: waiting for the first warm idle", r.n.progress(r.wall), "nothing yet");
        r.start(0).idle(40, jeepIdle()).start(1).idle(40, jeepIdle()).start(3).idle(40, jeepIdle());
        T.eq("Learning normal: drive 3 of 5, day 4 of 7", r.n.progress(r.wall), "third drive, fourth day");
    }

    public static void aDriveNeedsHalfAMinuteOfWarmIdleToCount() {
        Rig r = new Rig(false);
        r.start(0).idle(20, jeepIdle()).start(1).idle(30, jeepIdle());
        T.eq("Learning normal: drive 1 of 5, day 2 of 7", r.n.progress(r.wall), "20 s does not count, 30 s does");
    }

    public static void medianAndSpreadAreRobust() {
        T.near(3, NormalReadings.median(new double[]{5, 1, 3}), 1e-12, "odd count");
        T.near(2.5, NormalReadings.median(new double[]{4, 1, 3, 2}), 1e-12, "even count");
        // the middle of the distances from the median, scaled so it matches a standard deviation
        T.near(1.4826, NormalReadings.spread(new double[]{50, 51, 52, 53, 70}), 1e-9, "one odd drive barely counts");
        T.near(0, NormalReadings.spread(new double[]{52, 52, 52}), 1e-12, "all the same");
    }

    public static void normalIsTheMiddleOfTheDrivesWithAStatisticalMargin() {
        Rig r = new Rig(false);
        double[] kpa = {50, 51, 52, 53, 70};
        double[] days = {0, 2, 4, 6, 7.5};
        for (int i = 0; i < 5; i++) r.start(days[i]).idle(40, with(jeepIdle(), "map_kpa", kpa[i]));
        r.start(8);
        NormalReadings.Row row = r.row("map_kpa");
        T.near(52, row.median, 1e-9, "middle of the five drives' own middles");
        T.near(52 - 3 * 1.4826, row.low, 1e-9, "three spreads below");
        T.near(52 + 3 * 1.4826, row.high, 1e-9, "three spreads above");
    }

    public static void theNormalIsFrozenOnceLearned() {
        Rig r = new Rig(false).learned(jeepIdle());
        for (int d = 9; d < 14; d++) r.start(d).idle(40, with(jeepIdle(), "map_kpa", 60));
        r.start(15);
        T.near(52, r.row("map_kpa").median, 1e-9, "slow drift is caught, not taken in");
    }

    // ---------------- flagging ----------------

    public static void flagsOnlyAfterTwoDrivesInARowOutside() {
        Rig r = new Rig(false).learned(jeepIdle());
        r.idle(40, with(jeepIdle(), "map_kpa", 58));
        T.eq(NormalReadings.Status.OUTSIDE_ONCE, r.row("map_kpa").status, "once");
        T.eq(null, r.n.notice(Collections.emptyMap()), "nothing said after one drive");
        r.start(9).idle(40, with(jeepIdle(), "map_kpa", 58));
        T.eq(NormalReadings.Status.HIGH, r.row("map_kpa").status, "twice in a row");
        T.eq("Idle manifold pressure is higher than usual: 58 kPa now, normally 52", r.n.notice(Collections.emptyMap()), "notice");
        T.eq(NormalReadings.Status.NORMAL, r.row("rpm").status, "the rest stay normal");
    }

    public static void aNormalDriveInBetweenStartsTheCountAgain() {
        Rig r = new Rig(false).learned(jeepIdle());
        r.idle(40, with(jeepIdle(), "map_kpa", 58));
        r.start(9).idle(40, jeepIdle());
        r.start(10).idle(40, with(jeepIdle(), "map_kpa", 58));
        T.eq(NormalReadings.Status.OUTSIDE_ONCE, r.row("map_kpa").status, "not two in a row");
        T.eq(null, r.n.notice(Collections.emptyMap()), "quiet");
    }

    public static void highThenLowIsNotADrift() {
        Rig r = new Rig(false).learned(jeepIdle());
        r.idle(40, with(jeepIdle(), "map_kpa", 58));
        r.start(9).idle(40, with(jeepIdle(), "map_kpa", 46));
        T.eq(NormalReadings.Status.OUTSIDE_ONCE, r.row("map_kpa").status, "opposite sides");
    }

    public static void aDriveWithNoIdleNeitherConfirmsNorClears() {
        Rig r = new Rig(false).learned(jeepIdle());
        r.idle(40, with(jeepIdle(), "map_kpa", 58));
        r.start(9).cruise(60, 195);
        r.start(10).idle(40, with(jeepIdle(), "map_kpa", 58));
        T.eq(NormalReadings.Status.HIGH, r.row("map_kpa").status, "the drive without idle is skipped");
    }

    public static void smallChangesNeverFlagWhenLearningWasSteady() {
        // every learning drive the same: no spread, so the minimums decide
        String[][] cases = {{"map_kpa", "52", "54.5", "55.5"}, {"rpm", "700", "740", "760"},
                {"stft", "0", "2.5", "3.5"}, {"batt_v", "14", "13.75", "13.65"}, {"iac", "30", "38", "42"},
                {"inj_ms", "3", "3.25", "3.35"}};
        for (String[] c : cases) {
            double normal = Double.parseDouble(c[1]), small = Double.parseDouble(c[2]), big = Double.parseDouble(c[3]);
            Rig r = new Rig(false).learned(with(jeepIdle(), c[0], normal));
            r.idle(40, with(jeepIdle(), c[0], small)).start(9).idle(40, with(jeepIdle(), c[0], small));
            T.eq(NormalReadings.Status.NORMAL, r.row(c[0]).status, c[0] + " within its minimum");
            r.start(10).idle(40, with(jeepIdle(), c[0], big)).start(11).idle(40, with(jeepIdle(), c[0], big));
            T.ok(r.row(c[0]).status.flagged(), c[0] + " past its minimum");
        }
    }

    public static void aNoisyLearningNeedsABiggerChange() {
        Rig r = new Rig(false);
        double[] kpa = {46, 49, 52, 55, 58};      // spread 4.45 kPa: three spreads is 13.3, more than the 3 minimum
        double[] days = {0, 2, 4, 6, 7.5};
        for (int i = 0; i < 5; i++) r.start(days[i]).idle(40, with(jeepIdle(), "map_kpa", kpa[i]));
        r.start(8).idle(40, with(jeepIdle(), "map_kpa", 62)).start(9).idle(40, with(jeepIdle(), "map_kpa", 62));
        T.eq(NormalReadings.Status.NORMAL, r.row("map_kpa").status, "10 kPa is inside this car's own scatter");
        r.start(10).idle(40, with(jeepIdle(), "map_kpa", 66)).start(11).idle(40, with(jeepIdle(), "map_kpa", 66));
        T.eq(NormalReadings.Status.HIGH, r.row("map_kpa").status, "14 kPa is not");
    }

    public static void theNoticeFollowsTheGaugeUnits() {
        Rig r = new Rig(false).learned(jeepIdle());
        r.idle(40, with(jeepIdle(), "map_kpa", 58)).start(9).idle(40, with(jeepIdle(), "map_kpa", 58));
        Map<String, String> units = new HashMap<>();
        units.put("map_kpa", "psi");
        T.eq("Idle manifold pressure is higher than usual: 8.4 psi now, normally 7.5", r.n.notice(units), "in psi");
        units.put("map_kpa", "V");
        T.eq("Idle manifold pressure is higher than usual: 58 kPa now, normally 52", r.n.notice(units), "a unit that does not fit is ignored");
    }

    public static void moreThanOneDriftSaysHowManyMore() {
        Rig r = new Rig(false).learned(jeepIdle());
        Map<String, Double> leak = with(with(jeepIdle(), "map_kpa", 58), "ltft", 9);
        r.idle(40, leak).start(9).idle(40, leak);
        T.eq("Idle manifold pressure is higher than usual: 58 kPa now, normally 52 (and 1 more in Normal readings)",
                r.n.notice(Collections.emptyMap()), "two drifts");
    }

    public static void dismissingHidesTheNoticeUntilTheNextDrive() {
        Rig r = new Rig(false).learned(jeepIdle());
        r.idle(40, with(jeepIdle(), "map_kpa", 58)).start(9).idle(40, with(jeepIdle(), "map_kpa", 58));
        r.n.dismiss();
        T.eq(null, r.n.notice(Collections.emptyMap()), "dismissed");
        T.eq(NormalReadings.Status.HIGH, r.row("map_kpa").status, "still listed in Normal readings");
        r.start(10);
        T.ok(r.n.notice(Collections.emptyMap()) != null, "back on the next drive");
    }

    public static void acceptingTheCurrentReadingMakesItTheNormal() {
        Rig r = new Rig(false).learned(jeepIdle());
        r.idle(40, with(jeepIdle(), "map_kpa", 58)).start(9).idle(40, with(jeepIdle(), "map_kpa", 58));
        T.ok(r.n.accept("map_kpa"), "accepted");
        T.eq(null, r.n.notice(Collections.emptyMap()), "nothing to say");
        T.eq(NormalReadings.Status.NORMAL, r.row("map_kpa").status, "normal now");
        T.near(58, r.row("map_kpa").median, 1e-9, "58 is the new normal");
        r.start(10).idle(40, with(jeepIdle(), "map_kpa", 52)).start(11).idle(40, with(jeepIdle(), "map_kpa", 52));
        T.eq(NormalReadings.Status.LOW, r.row("map_kpa").status, "and the old figure now reads low");
        T.ok(!new Rig(false).n.accept("map_kpa"), "nothing to accept while learning");
    }

    public static void startingAgainForgetsEverything() {
        Rig r = new Rig(false).learned(jeepIdle());
        r.idle(40, with(jeepIdle(), "map_kpa", 58)).start(9).idle(40, with(jeepIdle(), "map_kpa", 58));
        r.n.startAgain();
        T.ok(r.n.learning(), "learning again");
        T.eq("Learning normal: waiting for the first warm idle", r.n.progress(r.wall), "from the start");
        T.eq(null, r.n.notice(Collections.emptyMap()), "quiet");
        T.eq(NormalReadings.Status.LEARNING, r.row("map_kpa").status, "no normal");
    }

    // ---------------- the readings that are not plain averages ----------------

    public static void frontO2IsTheShareOfTimeRich() {
        Rig r = new Rig(false).start(0).idle(40, jeepIdle());
        T.near(50, r.row("o2_rich").latest, 3, "switching evenly: about half the time rich");
        Rig lean = new Rig(false).start(0).idle(40, with(jeepIdle(), "o2_front", 0.1));
        T.near(0, lean.row("o2_rich").latest, 1e-9, "always lean");
    }

    public static void coolantIsLearnedFromSettledDriving() {
        Rig r = new Rig(false).start(0).cruise(299, 200);
        T.eq(null, r.row("coolant_warm").latest, "not in the first five warm minutes");
        r.cruise(40, 200);
        T.near(200, r.row("coolant_warm").latest, 1e-9, "settled temperature");
    }

    // ---------------- memory ----------------

    public static void savedAndRestoredItIsTheSame() {
        Rig r = new Rig(false).learned(jeepIdle());
        r.idle(40, with(jeepIdle(), "map_kpa", 58)).start(9).idle(40, with(jeepIdle(), "map_kpa", 58));
        T.ok(r.n.unsaved(), "changes waiting to be saved");
        String text = r.n.save();
        T.ok(!r.n.unsaved(), "saved");
        NormalReadings back = NormalReadings.restore(text, false);
        T.ok(!back.learning(), "still learned");
        T.eq(r.n.notice(Collections.emptyMap()), back.notice(Collections.emptyMap()), "same notice");
        T.near(52, row(back, "map_kpa").median, 1e-9, "same normal");
        T.eq(text, back.save(), "same text again");
    }

    public static void eachCarHasItsOwnMemory() {
        T.ok(!NormalReadings.storeKey(false).equals(NormalReadings.storeKey(true)), "kept under different names");
        Rig jeep = new Rig(false).learned(jeepIdle());
        NormalReadings asPorsche = NormalReadings.restore(jeep.n.save(), true);
        T.ok(asPorsche.learning(), "the Jeep's memory is never read as the Porsche's");
        T.eq("Learning normal: waiting for the first warm idle", asPorsche.progress(T0), "the Porsche starts empty");
        T.ok(NormalReadings.restore("rubbish\nmore", false).learning(), "unreadable memory starts empty");
        T.ok(NormalReadings.restore(null, false).learning(), "no memory yet");
        Set<String> both = new HashSet<>(ids(NormalReadings.watched(true)));
        T.ok(both.contains("maf") && !ids(NormalReadings.watched(false)).contains("maf"), "each car watches its own");
    }

    static NormalReadings.Row row(NormalReadings n, String id) {
        for (NormalReadings.Row r : n.rows()) if (r.watch.id.equals(id)) return r;
        throw new AssertionError("no row " + id);
    }
}
