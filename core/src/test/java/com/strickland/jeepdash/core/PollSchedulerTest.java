package com.strickland.jeepdash.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

public class PollSchedulerTest {
    static PollScheduler sched(String... ids) {
        PollScheduler s = new PollScheduler(60000, TwoLine::byId);
        s.setActive(Arrays.asList(ids));
        return s;
    }

    static String show(PollScheduler.Step st) { return st.kind + (st.param == null ? "" : " " + st.param.id); }

    public static void sciOnlyRoundRobinByWeight() {
        PollScheduler s = sched("rpm", "coolant");
        T.eq("SWITCH_TO_SCI", show(s.next(0)), "first step");
        int rpm = 0, clt = 0;
        for (int i = 0; i < 8; i++) {
            PollScheduler.Step st = s.next(1 + i);
            T.eq(PollScheduler.Kind.READ_SCI, st.kind, "read " + i);
            if (st.param.id.equals("rpm")) rpm++; else if (st.param.id.equals("coolant")) clt++;
        }
        T.eq(6, rpm, "rpm reads");
        T.eq(2, clt, "coolant reads");
        T.eq(Source.SCI, s.currentBus(), "bus");
        T.eq(Source.SCI, s.primary(), "Jeep line is the only line");
    }

    public static void anyGenericOnlyValuePutsEverythingOnTheGenericLine() {
        PollScheduler s = sched("rpm", "speed", "coolant");
        T.eq("SWITCH_TO_GENERIC", show(s.next(0)), "one wake-up, then no more line switching");
        int rpm = 0, speed = 0, clt = 0;
        for (int i = 0; i < 14; i++) {
            PollScheduler.Step st = s.next(1000L * i);
            T.eq(PollScheduler.Kind.READ_GENERIC, st.kind, "generic read " + i);
            if (st.param.id.equals("rpm")) rpm++; else if (st.param.id.equals("speed")) speed++; else clt++;
        }
        T.eq(6, rpm, "rpm read over generic too");
        T.eq(6, speed, "speed reads");
        T.eq(2, clt, "coolant reads");
        T.eq(Source.GENERIC, s.primary(), "generic is the working line");
        T.eq(Collections.emptySet(), s.slowIds(), "nothing is slow");
    }

    public static void jeepOnlyValueIsVisitedFirstThenOncePerInterval() {
        PollScheduler s = sched("speed", "map_v");
        T.eq("SWITCH_TO_SCI", show(s.next(0)), "Jeep-only value first");
        T.eq("READ_SCI map_v", show(s.next(1)), "read it");
        T.eq("SWITCH_TO_GENERIC", show(s.next(2)), "then settle on generic");
        for (long t = 1000; t < 60000; t += 1000) T.eq("READ_GENERIC speed", show(s.next(t)), "generic read at " + t);
        T.eq("SWITCH_TO_SCI", show(s.next(60000)), "a minute later: visit the Jeep line");
        T.eq("READ_SCI map_v", show(s.next(60001)), "read it");
        T.eq("SWITCH_TO_GENERIC", show(s.next(60002)), "and back");
        T.eq("READ_GENERIC speed", show(s.next(60003)), "generic reads resume");
        T.eq(Collections.singleton("map_v"), s.slowIds(), "map_v is the slow one in this set");
    }

    public static void twoFailedWakeUpsFallBackToTheJeepLine() {
        PollScheduler s = sched("rpm", "speed");
        T.eq("SWITCH_TO_GENERIC", show(s.next(0)), "first try");
        s.genericFailed(8000);
        T.eq("SWITCH_TO_GENERIC", show(s.next(8000)), "second try straight away");
        s.genericFailed(16000);
        T.eq("SWITCH_TO_SCI", show(s.next(16000)), "give up for now: Jeep line");
        T.eq("READ_SCI rpm", show(s.next(16001)), "values the Jeep line has keep working");
        T.eq(Source.SCI, s.primary(), "Jeep line is the working line");
        T.eq("READ_SCI rpm", show(s.next(16000 + 29999)), "no retry before 30 s");
        T.eq("SWITCH_TO_GENERIC", show(s.next(16000 + 30000)), "retry after 30 s");
        s.genericWorked();
        T.eq(PollScheduler.Kind.READ_GENERIC, s.next(16000 + 38000).kind, "wake-up worked: everything on generic");
        T.eq(Source.GENERIC, s.primary(), "generic is the working line again");
    }

    public static void retryIntervalDoublesUpToTwoMinutes() {
        PollScheduler s = sched("rpm", "speed");
        s.next(0); s.genericFailed(0); s.next(0); s.genericFailed(0);
        T.eq(30000L, s.genericRetryMs(), "after two failures");
        s.genericFailed(0);
        T.eq(60000L, s.genericRetryMs(), "after three");
        s.genericFailed(0);
        T.eq(120000L, s.genericRetryMs(), "after four");
        s.genericFailed(0);
        T.eq(120000L, s.genericRetryMs(), "capped at two minutes");
    }

    public static void retrySoonSkipsTheWait() {
        PollScheduler s = sched("rpm", "speed");
        s.next(0); s.genericFailed(0); s.next(0); s.genericFailed(1000);
        T.eq("SWITCH_TO_SCI", show(s.next(1000)), "fallback");
        s.retryGenericSoon();
        T.eq("SWITCH_TO_GENERIC", show(s.next(1001)), "engine computer just came alive: try generic now");
    }

    public static void genericOnlySetIdlesBetweenTriesWhenGenericIsDown() {
        PollScheduler s = sched("speed");
        s.next(0); s.genericFailed(0); s.next(0); s.genericFailed(1000);
        T.eq("IDLE", show(s.next(1001)), "nothing readable right now");
        T.eq("SWITCH_TO_GENERIC", show(s.next(31000)), "try again later");
    }

    public static void emptyIsIdle() {
        T.eq("IDLE", show(sched().next(0)), "nothing active");
    }

    public static void resetBusForcesSetupAgain() {
        PollScheduler s = sched("rpm");
        s.next(0);
        T.eq(PollScheduler.Kind.READ_SCI, s.next(1).kind, "reading");
        s.resetBus();
        T.eq(null, s.currentBus(), "bus unknown after reset");
        T.eq(PollScheduler.Kind.SWITCH_TO_SCI, s.next(2).kind, "sets up SCI again");
    }

    public static void visitsToTheOtherLineAreNeverMoreOftenThanOncePerMinute() {
        PollScheduler s = new PollScheduler(10000, TwoLine::byId);        // a caller asking for every 10 s
        s.setActive(Arrays.asList("speed", "map_v"));
        s.next(0); s.next(1); s.next(2);                   // first visit, then generic
        T.eq("READ_GENERIC speed", show(s.next(30000)), "each visit costs about 9 s of dead gauges: not at 30 s");
        T.eq("SWITCH_TO_SCI", show(s.next(60000)), "at one minute");
        T.eq(60000L, s.otherEveryMs(), "reported interval is the real one");
    }

    public static void readGapsFollowTheWeights() {
        Map<String, Integer> g = sched("rpm", "coolant").readGaps();   // cycle: rpm, coolant, rpm, rpm
        T.eq(4, g.get("coolant"), "a weight-1 value comes round once per cycle");
        T.eq(2, g.get("rpm"), "rpm's longest wait is two reads");
    }

    public static void engineAnsweringGivesGenericAFreshStart() {
        PollScheduler s = sched("rpm", "speed");
        for (int i = 0; i < 5; i++) s.genericFailed(0);
        T.eq(Source.SCI, s.primary(), "key off long enough: working on the Jeep line");
        s.engineAnswering();
        T.eq(Source.GENERIC, s.primary(), "key on: generic is the working line again");
        T.eq("SWITCH_TO_GENERIC", show(s.next(1000)), "and is tried at once");
    }

    static String pair(PollScheduler.Step st) {
        return st.kind + " " + st.param.id + (st.second == null ? "" : "+" + st.second.id);
    }

    public static void oneByteValuesShareAJeepLineRead() {
        PollScheduler s = sched("coolant", "map_v", "spark");      // weights 1, 2, 2
        s.next(0);
        T.eq("READ_SCI coolant+map_v", pair(s.next(1)), "two one-byte values in one read");
        T.eq("READ_SCI spark+map_v", pair(s.next(2)), "pairs keep forming across the cycle");
        T.eq("READ_SCI spark", pair(s.next(3)), "an odd one out goes alone");
        T.eq("READ_SCI coolant+map_v", pair(s.next(4)), "three reads instead of five per cycle");
    }

    public static void twoByteValuesAndRepeatsAreNeverPaired() {
        PollScheduler s = sched("rpm", "map_v");                   // rpm is a two-byte value; map_v comes twice
        s.next(0);
        for (int i = 1; i <= 10; i++) {
            PollScheduler.Step st = s.next(i);
            T.ok(st.second == null, "read " + i + " carries one value: " + pair(st));
        }
    }

    public static void readGapsCountPairedReads() {
        Map<String, Integer> g = sched("coolant", "map_v", "spark").readGaps();
        T.eq(3, g.get("coolant"), "coolant comes round every third read now");
        T.eq(2, g.get("map_v"), "map_v at most two reads apart");
    }
}
