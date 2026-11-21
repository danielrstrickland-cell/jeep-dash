package com.strickland.jeepdash.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Decides what to ask the dongle next. SCI (the 5.9's own data) is the primary line: every active value
 * with an SCI id is read there in a weighted round-robin. Generic-only values are read in a short block
 * on generic OBD2 every {@code genericEveryMs}. If no active value has an SCI id, it stays on generic.
 */
public final class PollScheduler {

    public enum Kind { SWITCH_TO_SCI, SWITCH_TO_GENERIC, READ_SCI, READ_GENERIC, IDLE }

    public static final class Step {
        public final Kind kind;
        public final Param param;
        Step(Kind kind, Param param) { this.kind = kind; this.param = param; }
    }

    static final long MAX_GENERIC_INTERVAL_MS = 120000;

    private final long genericEveryMs;
    private List<Param> sciCycle = new ArrayList<>();
    private List<Param> genericOnly = new ArrayList<>();
    private List<Param> genericCycle = new ArrayList<>();
    private final Deque<Step> queue = new ArrayDeque<>();
    private Source bus;
    private long lastBlockMs = -1;
    private int sciIdx, genIdx;
    private int genericFails;

    public PollScheduler(long genericEveryMs) { this.genericEveryMs = genericEveryMs; }

    public synchronized void setActive(Collection<String> paramIds) {
        List<Param> sci = new ArrayList<>(), gen = new ArrayList<>();
        for (String id : new LinkedHashSet<>(paramIds)) {
            Param p = Catalog.byId(id);
            if (p == null) continue;
            if (p.slow()) gen.add(p); else sci.add(p);
        }
        sciCycle = weighted(sci);
        genericOnly = gen;
        genericCycle = weighted(gen);
        sciIdx = 0;
        genIdx = 0;
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

    public synchronized Step next(long nowMs) {
        Step queued = queue.poll();
        if (queued != null) {
            if (queued.kind == Kind.SWITCH_TO_SCI) bus = Source.SCI;
            return queued;
        }
        if (sciCycle.isEmpty() && genericCycle.isEmpty()) return new Step(Kind.IDLE, null);

        if (sciCycle.isEmpty()) {
            if (bus != Source.GENERIC) { bus = Source.GENERIC; return new Step(Kind.SWITCH_TO_GENERIC, null); }
            return new Step(Kind.READ_GENERIC, genericCycle.get(genIdx++ % genericCycle.size()));
        }

        if (bus != Source.SCI) {
            if (lastBlockMs < 0) lastBlockMs = nowMs;
            bus = Source.SCI;
            return new Step(Kind.SWITCH_TO_SCI, null);
        }
        if (!genericOnly.isEmpty() && nowMs - lastBlockMs >= genericIntervalMs()) {
            lastBlockMs = nowMs;
            for (Param p : genericOnly) queue.add(new Step(Kind.READ_GENERIC, p));
            queue.add(new Step(Kind.SWITCH_TO_SCI, null));
            bus = Source.GENERIC;
            return new Step(Kind.SWITCH_TO_GENERIC, null);
        }
        return new Step(Kind.READ_SCI, sciCycle.get(sciIdx++ % sciCycle.size()));
    }

    /** Forgets which line the dongle is on, so the next step sets the line up again. */
    public synchronized void resetBus() {
        bus = null;
        queue.clear();
    }

    public long genericEveryMs() { return genericEveryMs; }

    /** Doubles after every failed wake-up (up to 2 minutes) so a dead generic line can't stall the fast one. */
    public synchronized long genericIntervalMs() {
        return Math.min(MAX_GENERIC_INTERVAL_MS, genericEveryMs << Math.min(genericFails, 8));
    }

    /** The generic wake-up got no answer: drop the rest of the block and wait longer before the next try. */
    public synchronized void genericFailed(long nowMs) {
        genericFails++;
        lastBlockMs = nowMs;
        bus = null;
        queue.clear();
    }

    /** A generic reading came back: normal interval again. */
    public synchronized void genericWorked() { genericFails = 0; }

    /** The line the dongle is on after the last step handed out (null before the first switch). */
    public synchronized Source currentBus() { return bus; }
}
