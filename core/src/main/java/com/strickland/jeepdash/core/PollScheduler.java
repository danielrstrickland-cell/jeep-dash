package com.strickland.jeepdash.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Decides what to ask the dongle next. Changing between the Jeep's own line (SCI) and generic OBD2 costs
 * about 8 seconds (the generic wake-up), so the dash works on ONE line at a time:
 * <ul>
 * <li>every active value has an SCI id: the Jeep line only;</li>
 * <li>some active value is generic-only: everything that can be read on generic is read there; values only
 *     the Jeep line has are visited once at the start and then once every {@code otherEveryMs};</li>
 * <li>the generic wake-up failed twice: fall back to the Jeep line for what it has, and retry generic after
 *     30 s, 60 s, then every 2 minutes.</li>
 * </ul>
 */
public final class PollScheduler {

    public enum Kind { SWITCH_TO_SCI, SWITCH_TO_GENERIC, READ_SCI, READ_GENERIC, IDLE }

    public static final class Step {
        public final Kind kind;
        public final Param param;
        Step(Kind kind, Param param) { this.kind = kind; this.param = param; }
    }

    static final int FAILS_BEFORE_FALLBACK = 2;
    static final long FIRST_RETRY_MS = 30000, MAX_RETRY_MS = 120000;
    static final long MIN_OTHER_EVERY_MS = 60000;

    private final long otherEveryMs;
    private final Function<String, Param> lookup;
    private List<Param> sciCycle = new ArrayList<>();      // everything the Jeep line can read
    private List<Param> genCycle = new ArrayList<>();      // everything generic can read
    private List<Param> sciOnly = new ArrayList<>();       // only on the Jeep line
    private boolean needsGeneric, any;
    private final Deque<Step> queue = new ArrayDeque<>();
    private Source bus;
    private boolean visitedOthers;
    private long lastBlockMs;
    private long lastGenericTryMs;
    private int sciIdx, genIdx;
    private int genericFails;

    /** A visit to the other line costs about 9 s of frozen gauges, so never more often than once a minute. */
    public PollScheduler(long otherEveryMs) { this(otherEveryMs, Catalog::byId); }

    /** With another catalog (tests). */
    public PollScheduler(long otherEveryMs, Function<String, Param> lookup) {
        this.otherEveryMs = Math.max(MIN_OTHER_EVERY_MS, otherEveryMs);
        this.lookup = lookup;
    }

    /**
     * For each value read on the working line: the longest wait, counted in reads, between two reads of it.
     * The caller turns that into how long a reading may stay on screen.
     */
    public synchronized Map<String, Integer> readGaps() {
        List<Param> cycle = genericIsPrimary() ? genCycle : sciCycle;
        Map<String, Integer> first = new LinkedHashMap<>(), last = new LinkedHashMap<>(), gap = new LinkedHashMap<>();
        for (int i = 0; i < cycle.size(); i++) {
            String id = cycle.get(i).id;
            Integer before = last.put(id, i);
            if (before == null) first.put(id, i);
            else gap.merge(id, i - before, Math::max);
        }
        for (Map.Entry<String, Integer> e : first.entrySet())      // wrap round to the next cycle
            gap.merge(e.getKey(), cycle.size() - last.get(e.getKey()) + e.getValue(), Math::max);
        return gap;
    }

    /** The engine computer just started answering (key on): generic gets a fresh start, tried at once. */
    public synchronized void engineAnswering() {
        genericFails = 0;
        lastGenericTryMs = Long.MIN_VALUE / 2;
    }

    /** True when some active value can be read on the Jeep line. */
    public synchronized boolean hasJeepLineValues() { return !sciCycle.isEmpty(); }

    public synchronized void setActive(Collection<String> paramIds) {
        List<Param> sci = new ArrayList<>(), gen = new ArrayList<>(), only = new ArrayList<>();
        boolean needs = false;
        for (String id : new LinkedHashSet<>(paramIds)) {
            Param p = lookup.apply(id);
            if (p == null) continue;
            if (p.sciIds != null) sci.add(p);
            if (p.pid != null) gen.add(p);
            if (p.pid == null) only.add(p);
            if (p.sciIds == null) needs = true;
        }
        sciCycle = weighted(sci);
        genCycle = weighted(gen);
        sciOnly = only;
        needsGeneric = needs;
        any = !sci.isEmpty() || !gen.isEmpty();
        sciIdx = 0;
        genIdx = 0;
        visitedOthers = false;
        queue.clear();
    }

    /** Spreads reads by weight: weight 3 appears three times per cycle for every once of weight 1. */
    private static List<Param> weighted(List<Param> ps) {
        int max = 0;
        for (Param p : ps) max = Math.max(max, p.weight);
        List<Param> cycle = new ArrayList<>();
        for (int round = 0; round < max; round++)
            for (Param p : ps) if (round < p.weight) cycle.add(p);
        return cycle;
    }

    private boolean genericIsPrimary() { return needsGeneric && genericFails < FAILS_BEFORE_FALLBACK; }

    public synchronized Step next(long nowMs) {
        Step queued = queue.poll();
        if (queued != null) {
            if (queued.kind == Kind.SWITCH_TO_SCI) bus = Source.SCI;
            else if (queued.kind == Kind.SWITCH_TO_GENERIC) bus = Source.GENERIC;
            return queued;
        }
        if (!any) return new Step(Kind.IDLE, null);

        if (!needsGeneric) {
            if (bus != Source.SCI) { bus = Source.SCI; return new Step(Kind.SWITCH_TO_SCI, null); }
            return new Step(Kind.READ_SCI, sciCycle.get(sciIdx++ % sciCycle.size()));
        }

        if (genericIsPrimary()) {
            if (bus != Source.GENERIC) {
                if (!sciOnly.isEmpty() && !visitedOthers) return visitJeepLine(nowMs);
                bus = Source.GENERIC;
                return new Step(Kind.SWITCH_TO_GENERIC, null);
            }
            if (!sciOnly.isEmpty() && nowMs - lastBlockMs >= otherEveryMs) return visitJeepLine(nowMs);
            return new Step(Kind.READ_GENERIC, genCycle.get(genIdx++ % genCycle.size()));
        }

        // The generic line is not answering: work on the Jeep line and try generic again now and then.
        if (nowMs - lastGenericTryMs >= genericRetryMs()) {
            lastGenericTryMs = nowMs;
            bus = Source.GENERIC;
            return new Step(Kind.SWITCH_TO_GENERIC, null);
        }
        if (sciCycle.isEmpty()) return new Step(Kind.IDLE, null);
        if (bus != Source.SCI) { bus = Source.SCI; return new Step(Kind.SWITCH_TO_SCI, null); }
        return new Step(Kind.READ_SCI, sciCycle.get(sciIdx++ % sciCycle.size()));
    }

    private Step visitJeepLine(long nowMs) {
        visitedOthers = true;
        lastBlockMs = nowMs;
        for (Param p : sciOnly) queue.add(new Step(Kind.READ_SCI, p));
        queue.add(new Step(Kind.SWITCH_TO_GENERIC, null));
        bus = Source.SCI;
        return new Step(Kind.SWITCH_TO_SCI, null);
    }

    /** Forgets which line the dongle is on, so the next step sets the line up again. */
    public synchronized void resetBus() {
        bus = null;
        queue.clear();
    }

    public long otherEveryMs() { return otherEveryMs; }

    /** How long to stay off the generic line after it failed. */
    public synchronized long genericRetryMs() {
        if (sciCycle.isEmpty()) return FIRST_RETRY_MS;      // nothing else to do meanwhile: keep trying
        int extra = Math.max(0, Math.min(genericFails - FAILS_BEFORE_FALLBACK, 2));
        return Math.min(MAX_RETRY_MS, FIRST_RETRY_MS << extra);
    }

    /** The generic wake-up got no answer. */
    public synchronized void genericFailed(long nowMs) {
        genericFails++;
        lastGenericTryMs = nowMs;
        bus = null;
        queue.clear();
    }

    /** The generic wake-up worked. */
    public synchronized void genericWorked() { genericFails = 0; }

    /** Something changed (the engine computer just started answering): try generic at the next step. */
    public synchronized void retryGenericSoon() { lastGenericTryMs = Long.MIN_VALUE / 2; }

    /** The line the dash is working on (the other one is only visited); null when nothing is active. */
    public synchronized Source primary() {
        if (!any) return null;
        return genericIsPrimary() ? Source.GENERIC : Source.SCI;
    }

    /** Active values that are only visited now and then because they live on the other line. */
    public synchronized Set<String> slowIds() {
        Set<String> ids = new LinkedHashSet<>();
        if (genericIsPrimary()) for (Param p : sciOnly) ids.add(p.id);
        return ids;
    }

    /** The line the dongle is on after the last step handed out (null before the first switch). */
    public synchronized Source currentBus() { return bus; }
}
