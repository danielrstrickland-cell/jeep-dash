package com.strickland.jeepdash.core;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Carries out the scheduler's steps on the dongle: line setup when the scheduler switches lines, one read
 * per step, decoded readings into {@link LiveValues}. Live data is read-only: it sends only what
 * {@link Protocols#allowed} lists. Clearing stored codes is the one write, and has its own path here.
 * Throws IOException when the link is gone so the caller can reconnect.
 */
public final class PollRunner implements Poller {
    /** Consecutive unanswered reads before the line is set up again (key was cycled, engine restarted). */
    static final int MISSES_BEFORE_RESETUP = 6;
    /** Consecutive frames the dongle itself ignores before the link counts as lost. */
    static final int UNANSWERED_BEFORE_LOST = 3;
    /** Longest a visit to the other line may keep the working line's values on screen. */
    static final long MAX_BLOCK_MS = 20000;
    /** Visited values stay on screen for one visit interval plus this, so they survive until the next visit. */
    static final long SLOW_EXTRA_MS = 15000;
    /** What one read costs on each line, measured on the tablet (a bit on the slow side). */
    static final long SCI_READ_MS = 260, GENERIC_READ_MS = 330;

    private final DongleSession session;
    private final PollScheduler sched;
    private final LiveValues values;
    private final LongSupplier clock;
    private int ackMs = 1500, readMs = 1000, setupMs = 4000, wakeMs = 6000;   // real replies take 0.2-0.5 s
    private Trace trace;
    private volatile Source onLine;                 // what the dongle is set up for right now
    private LongConsumer sleeper = PollRunner::sleep;
    private int misses, unanswered;
    private volatile int goodReads;
    private volatile boolean answering = true;
    private int deadWakes;
    private volatile boolean fastWanted, fastGaveUp;
    private boolean fastOn;                         // the dongle has the shorter wait right now
    private int fastSuspect, fastReads, fastMisses, goodStreak;

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

    /** Test hook: no real waiting. */
    void setSleeper(LongConsumer s) { sleeper = s; }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Records every exchange (optional). */
    public void setTrace(Trace t) { trace = t; }

    private void note(String line) {
        Trace t = trace;
        if (t != null) t.add(String.format(java.util.Locale.US, "%8.2f  %s", clock.getAsLong() / 1000.0, line));
    }

    /** Fast refresh: not asked for, asked for, or asked for and dropped again because readings went missing. */
    public enum Fast { OFF, ON, BACKED_OFF }

    /** Good reads in a row at the captured wait before the shorter one is tried. */
    static final int FAST_AFTER_GOOD = 3;
    /** Misses in a row at the shorter wait before going back to the captured one. */
    static final int FAST_MISSES_IN_A_ROW = 2;
    /** Scattered misses at the shorter wait: this many, and at least one read in ten, ends the try. */
    static final int FAST_MISSES = 4, FAST_WINDOW = 100;

    /**
     * Asks for (or drops) the experimental fast refresh on the Jeep line. Asking again after it backed off
     * gives it another try.
     */
    public synchronized void setFastRefresh(boolean on) {
        fastWanted = on;
        fastGaveUp = false;
        fastSuspect = 0;
    }

    public Fast fastRefresh() { return !fastWanted ? Fast.OFF : fastGaveUp ? Fast.BACKED_OFF : Fast.ON; }

    /** Before a Jeep-line read: gives the dongle the wait it should have right now, if that changed. */
    private void syncFast() throws IOException {
        boolean may = fastWanted && !fastGaveUp && fastSuspect == 0;
        if (fastOn && !may) {
            normalWait();
        } else if (!fastOn && may && answering && goodStreak >= FAST_AFTER_GOOD) {
            note("-- fast refresh: shorter wait in the dongle");
            setup(Protocols.SCI_TIMING_FAST);
            fastOn = true;
            fastReads = fastMisses = 0;
        }
    }

    private void normalWait() throws IOException {
        if (!fastOn) return;
        setup(Protocols.SCI_TIMING);
        fastOn = false;
    }

    /** A Jeep-line read went unanswered. Decides whether the shorter wait is to blame. */
    private void fastMiss() throws IOException {
        if (fastOn) {
            fastMisses++;
            if (fastMisses >= FAST_MISSES && fastMisses * 10 >= fastReads) {
                note("-- fast refresh: too many readings missing, back to the normal wait for good");
                normalWait();
                fastGaveUp = true;
            } else if (misses >= FAST_MISSES_IN_A_ROW) {
                note("-- fast refresh: readings stopped, trying the normal wait");
                normalWait();
                fastSuspect = 1;
            }
        } else if (fastSuspect > 0 && ++fastSuspect > 3) {
            fastSuspect = 0;        // still silent at the normal wait: the key is off, the setting is not to blame
        }
    }


    /** Chooses which values are read. */
    public synchronized void setActive(Collection<String> paramIds) {
        sched.setActive(paramIds);
        applyStaleTimes();
    }

    /** Values on the visited line keep their last reading from one visit to the next. */
    private void applyStaleTimes() {
        values.clearStaleMs();
        // A value read once per cycle of fifteen waits four seconds or more for its turn: give every value
        // twice its own wait before it counts as missing, never less than the normal three seconds.
        long per = sched.primary() == Source.GENERIC ? GENERIC_READ_MS : SCI_READ_MS;
        for (Map.Entry<String, Integer> e : sched.readGaps().entrySet())
            values.setStaleMs(e.getKey(), Math.max(LiveValues.STALE_MS, 2 * e.getValue() * per));
        for (String id : sched.slowIds()) values.setStaleMs(id, sched.otherEveryMs() + SLOW_EXTRA_MS);
    }

    /**
     * Orderly stop: turns the dongle's generic keep-alive off, the way the GearWrench app does when it
     * leaves live data. Best effort; the Jeep line has nothing to stop.
     */
    public synchronized void leave() {
        if (onLine != Source.GENERIC) return;
        onLine = null;
        try {
            for (String c : Protocols.GENERIC_LEAVE) setup(c);
        } catch (IOException ignored) {
            // the link is going away anyway
        }
    }

    /** True while the dongle is set up for the generic line (its keep-alive is running). */
    public boolean onGenericLine() { return onLine == Source.GENERIC; }

    @Override
    public boolean keepAliveRunning() { return onGenericLine(); }

    @Override
    public boolean poll() throws IOException { return step() != PollScheduler.Kind.IDLE; }

    /** Total good readings so far (the caller turns this into reads per second). */
    public int goodReads() { return goodReads; }

    /** False after three reads in a row got no answer from the engine computer (key off?). */
    public boolean vehicleAnswering() { return answering; }

    /** Runs the next step; returns what it was. IDLE means there is nothing to read right now. */
    public synchronized PollScheduler.Kind step() throws IOException {
        PollScheduler.Step s = sched.next(clock.getAsLong());
        switch (s.kind) {
            case SWITCH_TO_SCI:
                if (sched.primary() != Source.SCI) holdWorkingLine();
                note("-- set up the Jeep line (SCI)");
                if (onLine == Source.GENERIC) for (String c : Protocols.GENERIC_LEAVE) setup(c);
                else setup(Protocols.STOP_KEEPALIVE);
                onLine = null;
                for (String c : Protocols.SCI_SETUP) setup(c);
                fastOn = false;                     // the line setup carries the captured wait
                goodStreak = 0;
                onLine = Source.SCI;
                if (sched.primary() == Source.SCI) values.endHold(clock.getAsLong());
                applyStaleTimes();
                break;
            case SWITCH_TO_GENERIC:
                if (sched.primary() != Source.GENERIC) holdWorkingLine();
                note("-- set up the generic line (ISO 9141-2)");
                if (onLine == Source.SCI) setup(Protocols.SCI_SLOW);   // leave the Jeep line's fast mode cleanly
                setup(Protocols.STOP_KEEPALIVE);       // a keep-alive left running would talk over the wake-up
                onLine = null;
                if (wakeGeneric()) {
                    for (String c : Protocols.GENERIC_SETUP_POST) setup(c);
                    onLine = Source.GENERIC;
                    sched.genericWorked();
                    deadWakes = 0;
                    answering = true;
                    values.endHold(clock.getAsLong());
                } else {
                    sched.genericFailed(clock.getAsLong());
                    note("-- generic line did not wake up");
                    // With nothing to ask on the Jeep line, two dead wake-ups in a row are the only sign
                    // that the key is off.
                    if (!sched.hasJeepLineValues() && ++deadWakes >= 2) keyOff();
                }
                applyStaleTimes();
                break;
            case READ_SCI:
                syncFast();
                if (fastOn && ++fastReads > FAST_WINDOW) fastReads = fastMisses = 0;
                exchange(Hex.bytes(Protocols.SCI_FAST), false, ackMs);
                if (s.second == null) read(s.param, Source.SCI, Protocols.sciRequest(s.param.sciIds));
                else readPair(s.param, s.second);
                break;
            case READ_GENERIC:
                read(s.param, Source.GENERIC, Protocols.genericRequest(s.param.pid));
                break;
            default:
                break;
        }
        return s.kind;
    }

    /**
     * Reads the stored-code list over the Jeep line at its slow speed, the way the GearWrench app does.
     * Null when the engine computer gave no sound answer. When live data is on the generic line, the Jeep
     * line is set up for the read and live data sets its own line up again afterwards.
     */
    @Override
    public synchronized List<Integer> readCodes() throws IOException {
        onJeepLine("to read codes");
        normalWait();                               // codes are read and cleared exactly as captured
        setup(Protocols.SCI_SLOW);
        return SciCodes.parse(exchange(Hex.bytes(SciCodes.READ), true, setupMs).data);
    }

    /**
     * Puts the dongle on the Jeep line for a read outside live data, if it is not there already; live data
     * then sets its own line up again.
     */
    private void onJeepLine(String why) throws IOException {
        if (onLine == Source.SCI) return;
        note("-- set up the Jeep line (SCI) " + why);
        if (onLine == Source.GENERIC) for (String c : Protocols.GENERIC_LEAVE) setup(c);
        else setup(Protocols.STOP_KEEPALIVE);
        onLine = null;
        for (String c : Protocols.SCI_SETUP) setup(c);
        fastOn = false;
        sched.resetBus();
    }

    /**
     * One read for "Scan all values" (see ValueScan): the same fast-speed switch and one-id F4 read live data
     * sends, with the id from 00 to ef and nothing else, at the captured timing and the same timeout. The raw
     * answer, or null when the engine computer gave none; a silent id is not taken as the key going off.
     * The caller stops live data while it scans.
     */
    public synchronized byte[] scanRead(int id) throws IOException {
        byte[] request = Protocols.scanRequest(id);
        if (!Protocols.allowedScan(request)) throw new IllegalStateException("not a scan read: " + Hex.str(request));
        onJeepLine("for the value scan");
        normalWait();
        exchange(Hex.bytes(Protocols.SCI_FAST), false, ackMs);
        byte[] answer = exchange(request, true, readMs).data;
        if (answer != null) goodReads++;
        return answer;
    }

    /**
     * Clears the stored codes: the dash's one write to the engine computer. Sent only after the engine
     * speed has been read as zero, as the two frames the GearWrench app sends, and followed by a fresh read
     * of the list.
     */
    @Override
    public synchronized SciCodes.Cleared clearCodes() throws IOException {
        List<Integer> before = readCodes();                 // also puts the dongle on the Jeep line
        if (before == null) return new SciCodes.Cleared(SciCodes.Outcome.NOT_ANSWERING, null);
        Param rpm = Catalog.jeep("rpm");
        exchange(Hex.bytes(Protocols.SCI_FAST), false, ackMs);
        Double turning = rpm.decode(Source.SCI, exchange(Protocols.sciRequest(rpm.sciIds), true, readMs).data);
        if (turning == null) return new SciCodes.Cleared(SciCodes.Outcome.NOT_ANSWERING, before);
        if (turning > 0) return new SciCodes.Cleared(SciCodes.Outcome.ENGINE_RUNNING, before);
        note("-- clear stored codes (asked for on the Codes screen)");
        setup(Protocols.SCI_SLOW);
        byte[] answer = write(SciCodes.CLEAR);
        SciCodes.Outcome outcome = SciCodes.erased(answer) ? SciCodes.Outcome.CLEARED
                : SciCodes.refused(answer) ? SciCodes.Outcome.DENIED : SciCodes.Outcome.FAILED;
        if (outcome == SciCodes.Outcome.CLEARED) {
            setup(Protocols.SCI_SLOW);
            write(SciCodes.CLEAR_ALL);
        }
        return new SciCodes.Cleared(outcome, readCodes());
    }

    /** Sends one of the two clear frames; anything else is refused here, whoever asks. */
    private byte[] write(String hex) throws IOException {
        byte[] inner = Hex.bytes(hex);
        if (!Protocols.allowedWrite(inner)) throw new IllegalStateException("not a permitted write: " + hex);
        return transact(inner, true, setupMs).data;
    }

    private void holdWorkingLine() {
        long t = clock.getAsLong();
        values.hold(t, t + MAX_BLOCK_MS);
    }

    /** Line setup, the 5-second wait, the 5-baud wake-up, then the first request. True when the car answered. */
    private boolean wakeGeneric() throws IOException {
        for (String c : Protocols.GENERIC_SETUP_PRE) setup(c);
        sleeper.accept(Protocols.GENERIC_PRE_WAIT_MS);
        DongleSession.Result wake = exchange(Hex.bytes(Protocols.GENERIC_INIT), true, wakeMs);
        if (wake.data == null) return false;
        DongleSession.Result first = exchange(Hex.bytes(Protocols.GENERIC_FIRST_QUERY), true, setupMs);
        return first.data != null;
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
            good(1);
        } else {
            miss(src);
        }
    }

    /** Two one-byte values in one request; the answer is <first value> <second id> <second value>. */
    private void readPair(Param a, Param b) throws IOException {
        int ia = a.sciIds[0], ib = b.sciIds[0];
        byte[] d = exchange(Protocols.sciRequest(ia, ib), true, readMs).data;
        boolean named = d != null && d.length >= 3 && (d[1] & 0xff) == ib;
        Double va = named ? a.decode(Source.SCI, new byte[]{(byte) ia, d[0]}) : null;
        Double vb = named ? b.decode(Source.SCI, new byte[]{(byte) ib, d[2]}) : null;
        if (va == null || vb == null) {
            miss(Source.SCI);
            return;
        }
        long now = clock.getAsLong();
        values.put(a.id, va, now);
        values.put(b.id, vb, now);
        good(2);
    }

    private void good(int readings) {
        goodReads += readings;
        misses = 0;
        goodStreak++;
        if (fastSuspect > 0) {      // silent at the shorter wait, answering at the normal one: the setting did it
            fastSuspect = 0;
            fastGaveUp = true;
            note("-- fast refresh: readings came back at the normal wait, leaving it off");
        }
        if (!answering) {
            answering = true;
            sched.engineAnswering();       // key just came on: generic gets a fresh start
            applyStaleTimes();
        }
    }

    private void miss(Source src) throws IOException {
        // Only the working line says whether the engine computer is there; a miss on a visit is just a miss.
        if (src != sched.primary()) return;
        misses++;
        goodStreak = 0;
        if (src == Source.SCI) fastMiss();
        if (misses >= 3) keyOff();
        if (misses >= MISSES_BEFORE_RESETUP) {
            misses = 0;
            sched.resetBus();
        }
    }

    /** The engine computer is silent: say so, and take every old reading off the screen. */
    private void keyOff() {
        answering = false;
        values.clear();
    }

    private DongleSession.Result exchange(byte[] inner, boolean wantReply, int timeoutMs) throws IOException {
        if (!Protocols.allowed(inner)) throw new IllegalStateException("not on the read-only list: " + Hex.str(inner));
        return transact(inner, wantReply, timeoutMs);
    }

    /** The exchange itself. Callers check what they send first: {@link #exchange} or {@link #write}. */
    private DongleSession.Result transact(byte[] inner, boolean wantReply, int timeoutMs) throws IOException {
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
