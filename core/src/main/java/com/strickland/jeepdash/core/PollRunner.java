package com.strickland.jeepdash.core;

import java.io.IOException;
import java.util.Collection;
import java.util.function.LongSupplier;

/**
 * Carries out the scheduler's steps on the dongle: line setup when the scheduler switches lines, one read
 * per step, decoded readings into {@link LiveValues}. Read-only: it sends only what {@link Protocols} lists.
 * Throws IOException when the link is gone so the caller can reconnect.
 */
public final class PollRunner {
    /** Consecutive unanswered reads before the line is set up again (key was cycled, engine restarted). */
    static final int MISSES_BEFORE_RESETUP = 6;
    /** Consecutive frames the dongle itself ignores before the link counts as lost. */
    static final int UNANSWERED_BEFORE_LOST = 3;
    /** Longest a generic block may keep the fast values on screen without a fresh reading. */
    static final long MAX_BLOCK_MS = 20000;
    /** Slow values stay on screen for one block interval plus this, so they survive until the next block. */
    static final long SLOW_EXTRA_MS = 8000;

    private final DongleSession session;
    private final PollScheduler sched;
    private final LiveValues values;
    private final LongSupplier clock;
    private int ackMs = 1500, readMs = 2000, setupMs = 4000, wakeMs = 6000;
    private boolean mixed, anyFast;
    private Trace trace;
    private int misses, unanswered;
    private volatile int goodReads;
    private volatile boolean answering = true;

    public PollRunner(DongleSession session, PollScheduler sched, LiveValues values, LongSupplier clock) {
        this.session = session;
        this.sched = sched;
        this.values = values;
        this.clock = clock;
    }

    /** Test hook: shorter waits. */
    void setTimeouts(int ackMs, int readMs, int slowMs) {
        this.ackMs = ackMs; this.readMs = readMs; this.setupMs = slowMs; this.wakeMs = slowMs;
    }

    /** Records every exchange (optional). */
    public void setTrace(Trace t) { trace = t; }

    private void note(String line) {
        Trace t = trace;
        if (t != null) t.add(String.format(java.util.Locale.US, "%8.2f  %s", clock.getAsLong() / 1000.0, line));
    }

    /** Chooses which values are read. Slow (generic-only) values keep their last reading between blocks. */
    public synchronized void setActive(Collection<String> paramIds) {
        sched.setActive(paramIds);
        boolean anySlow = false;
        anyFast = false;
        for (String id : paramIds) {
            Param p = Catalog.byId(id);
            if (p == null) continue;
            if (p.slow()) anySlow = true; else anyFast = true;
        }
        mixed = anyFast && anySlow;
        values.clearStaleMs();
        if (mixed) {
            for (String id : paramIds) {
                Param p = Catalog.byId(id);
                if (p != null && p.slow()) values.setStaleMs(id, sched.genericEveryMs() + SLOW_EXTRA_MS);
            }
        }
    }

    /** Total good readings so far (the caller turns this into reads per second). */
    public int goodReads() { return goodReads; }

    /** False after three reads in a row got no answer from the engine computer (key off?). */
    public boolean vehicleAnswering() { return answering; }

    /** Runs the next step; returns what it was. IDLE means there is nothing to read right now. */
    public synchronized PollScheduler.Kind step() throws IOException {
        PollScheduler.Step s = sched.next(clock.getAsLong());
        switch (s.kind) {
            case SWITCH_TO_SCI:
                note("-- set up the Jeep line (SCI)");
                for (String c : Protocols.SCI_SETUP) setup(c);
                values.endHold(clock.getAsLong());
                break;
            case SWITCH_TO_GENERIC:
                if (mixed) { long t = clock.getAsLong(); values.hold(t, t + MAX_BLOCK_MS); }
                note("-- set up the generic line (ISO 9141-2)");
                for (String c : Protocols.GENERIC_SETUP_PRE) setup(c);
                DongleSession.Result wake = exchange(Hex.bytes(Protocols.GENERIC_INIT), true, wakeMs);
                if (wake.data == null) {
                    sched.genericFailed(clock.getAsLong());
                    note("-- generic wake-up got no answer; next try in " + sched.genericIntervalMs() / 1000 + " s");
                    break;
                }
                for (String c : Protocols.GENERIC_SETUP_POST) setup(c);
                break;
            case READ_SCI:
                exchange(Hex.bytes(Protocols.SCI_FAST), false, ackMs);
                read(s.param, Source.SCI, Protocols.sciRequest(s.param.sciIds));
                break;
            case READ_GENERIC:
                read(s.param, Source.GENERIC, Protocols.genericRequest(s.param.pid));
                break;
            default:
                break;
        }
        return s.kind;
    }

    private void setup(String hex) throws IOException {
        byte[] inner = Hex.bytes(hex);
        boolean reply = Protocols.wantsReply(inner);
        exchange(inner, reply, reply ? setupMs : ackMs);
    }

    private void read(Param p, Source src, byte[] request) throws IOException {
        DongleSession.Result r = exchange(request, true, readMs);
        Double v = p.decode(src, r.data);
        if (v != null) {
            values.put(p.id, v, clock.getAsLong());
            goodReads++;
            misses = 0;
            answering = true;
            if (src == Source.GENERIC) sched.genericWorked();
            return;
        }
        // The Jeep's own line is the judge of "is the engine computer there"; a generic miss is just a miss.
        if (src == Source.GENERIC && anyFast) return;
        misses++;
        if (misses >= 3) answering = false;
        if (misses >= MISSES_BEFORE_RESETUP) {
            misses = 0;
            sched.resetBus();
        }
    }

    private DongleSession.Result exchange(byte[] inner, boolean wantReply, int timeoutMs) throws IOException {
        long t0 = clock.getAsLong();
        DongleSession.Result r = session.send(inner, wantReply, timeoutMs);
        if (trace != null) {
            String out = r.closed ? "LINK CLOSED" : r.data != null ? "-> " + Hex.str(r.data)
                    : r.noResponse ? "-> (vehicle did not answer)" : r.timedOut ? (r.acked ? "-> acked, no reply" : "-> NO ANSWER FROM DONGLE")
                    : "-> ok";
            note(Hex.str(inner) + "  " + out + "  (" + (clock.getAsLong() - t0) + " ms)");
        }
        if (r.closed) throw new IOException("link closed");
        if (r.acked) {
            unanswered = 0;
        } else if (++unanswered >= UNANSWERED_BEFORE_LOST) {
            unanswered = 0;
            throw new IOException("dongle stopped answering");
        }
        return r;
    }
}
