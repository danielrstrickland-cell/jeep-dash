package com.strickland.jeepdash.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Reads the Porsche: wakes the engine computer's line the way the GearWrench app does, then asks for the
 * groups that hold the values on screen, the busiest gauges most often. Read-only: it sends only what
 * {@link Protocols} lists. Throws IOException when the dongle link is gone so the caller can reconnect.
 */
public final class KwpRunner implements Poller {
    public enum Kind { SETUP, READ, IDLE }

    /** Reads in a row with no answer before the key counts as off and the line is woken again. */
    static final int MISSES_BEFORE_KEY_OFF = 3;
    /** Frames in a row the dongle itself ignores before the link counts as lost. */
    static final int UNANSWERED_BEFORE_LOST = 3;
    /** Pause between wake-up attempts while the key is off. */
    static final long RETRY_WAKE_MS = 4000;
    /** What one group read costs, measured in the capture (a bit on the slow side). */
    static final long READ_MS = 220;

    private final DongleSession session;
    private final LiveValues values;
    private final LongSupplier clock;
    private int ackMs = 1500, readMs = 1000, wakeMs = 6000;
    private Trace trace;
    private List<PorscheCatalog.Value> active = new ArrayList<>();
    private int[] cycle = new int[0];      // group numbers in reading order
    private int next;
    private boolean up;                    // the line is awake
    private long nextWakeMs = Long.MIN_VALUE;
    private int misses, unanswered;
    private volatile int goodReads;
    private volatile boolean answering = true;

    public KwpRunner(DongleSession session, LiveValues values, LongSupplier clock) {
        this.session = session;
        this.values = values;
        this.clock = clock;
    }

    /** Test hook: shorter waits. */
    void setTimeouts(int ackMs, int readMs, int wakeMs) {
        this.ackMs = ackMs; this.readMs = readMs; this.wakeMs = wakeMs;
    }

    /** Records every exchange (optional). */
    public void setTrace(Trace t) { trace = t; }

    private void note(String line) {
        Trace t = trace;
        if (t != null) t.add(String.format(java.util.Locale.US, "%8.2f  %s", clock.getAsLong() / 1000.0, line));
    }

    /** Total readings so far (the caller turns this into reads per second). */
    public int goodReads() { return goodReads; }

    /** False once the engine computer has stopped answering (key off?). */
    public boolean vehicleAnswering() { return answering; }

    /** True while the dongle is sending its "still here" message to the Porsche. */
    public boolean keepAliveRunning() { return up; }

    /** Chooses which values are read. Unknown ids are ignored. */
    public synchronized void setActive(Collection<String> paramIds) {
        List<PorscheCatalog.Value> list = new ArrayList<>();
        Map<Integer, Integer> weight = new LinkedHashMap<>();
        for (String id : new LinkedHashSet<>(paramIds)) {
            PorscheCatalog.Value v = PorscheCatalog.value(id);
            if (v == null) continue;
            list.add(v);
            weight.merge(v.group, v.param.weight, Math::max);
        }
        active = list;
        cycle = spread(weight);
        next = 0;
        values.clearStaleMs();
        // a value read once a cycle waits the whole cycle for its turn: allow twice that before it goes blank
        long stale = Math.max(LiveValues.STALE_MS, 2 * cycle.length * READ_MS);
        for (PorscheCatalog.Value v : list) values.setStaleMs(v.param.id, stale);
    }

    /** Each group as many times as its weight, spaced as evenly as the weights allow. */
    private static int[] spread(Map<Integer, Integer> weight) {
        int total = 0;
        for (int w : weight.values()) total += w;
        int[] out = new int[total];
        Map<Integer, Double> credit = new LinkedHashMap<>();
        for (int g : weight.keySet()) credit.put(g, 0.0);
        int last = -1;
        for (int i = 0; i < total; i++) {
            int best = -1;
            for (Map.Entry<Integer, Double> e : credit.entrySet()) {
                e.setValue(e.getValue() + weight.get(e.getKey()));
                if (e.getKey() != last && (best < 0 || e.getValue() > credit.get(best))) best = e.getKey();
            }
            if (best < 0) best = last;                   // only one group on screen
            credit.put(best, credit.get(best) - total);
            out[i] = best;
            last = best;
        }
        return out;
    }

    @Override
    public boolean poll() throws IOException { return step() != Kind.IDLE; }

    /** Runs the next step; IDLE means there is nothing to do right now. */
    public synchronized Kind step() throws IOException {
        if (cycle.length == 0) return Kind.IDLE;
        long now = clock.getAsLong();
        if (!up) {
            if (now < nextWakeMs) return Kind.IDLE;
            wake();
            return Kind.SETUP;
        }
        int group = cycle[next];
        next = (next + 1) % cycle.length;
        KwpBlocks.Block block = KwpBlocks.parse(group, exchange(KwpBlocks.request(group), true, readMs).data);
        if (block == null) {
            if (++misses >= MISSES_BEFORE_KEY_OFF) {
                misses = 0;
                up = false;                              // wake the line again on the next step
                keyOff();
            }
            return Kind.READ;
        }
        misses = 0;
        answering = true;
        now = clock.getAsLong();
        for (PorscheCatalog.Value v : active) {
            if (v.group != group) continue;
            Double reading = v.read(block);
            if (reading == null) continue;
            values.put(v.param.id, reading, now);
            goodReads++;
        }
        return Kind.READ;
    }

    private void wake() throws IOException {
        note("-- wake the Porsche line (KWP2000)");
        send(Protocols.STOP_KEEPALIVE);                  // one left running would talk over the wake-up
        for (String c : Protocols.KWP_SETUP_PRE) send(c);
        if (exchange(Hex.bytes(Protocols.KWP_INIT), true, wakeMs).data == null) {
            note("-- the engine computer did not wake up");
            keyOff();
            nextWakeMs = clock.getAsLong() + RETRY_WAKE_MS;
            return;
        }
        for (String c : Protocols.KWP_SETUP_POST) send(c);
        up = true;
        misses = 0;
    }

    /** Orderly stop: turns the dongle's keep-alive off. Best effort. */
    public synchronized void leave() {
        up = false;
        try {
            send(Protocols.STOP_KEEPALIVE);
        } catch (IOException ignored) {
            // the link is going away anyway
        }
    }

    /** The engine computer is silent: say so, and take every old reading off the screen. */
    private void keyOff() {
        answering = false;
        values.clear();
        PorscheCatalog.newDrive();
    }

    private void send(String hex) throws IOException { exchange(Hex.bytes(hex), false, ackMs); }

    private DongleSession.Result exchange(byte[] inner, boolean wantReply, int timeoutMs) throws IOException {
        if (!Protocols.allowed(inner)) throw new IllegalStateException("not on the read-only list: " + Hex.str(inner));
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
