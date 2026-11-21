package com.strickland.jeepdash.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PollRunnerTest {

    /** Fake dongle that understands the inner command: acks everything, answers queries from a script. */
    static final class Car implements ByteLink {
        DongleSession session;
        final List<String> sent = new ArrayList<>();
        boolean engineAnswers = true;     // the Jeep line (SCI)
        boolean dongleAnswers = true;
        boolean wakeAnswers = true;       // generic 5-baud wake-up
        int rpmRaw = 0x1680;              // 720 rpm over SCI (raw/8)
        boolean garblePairs;              // answer two-id reads with the wrong echo

        static int val(int id) { return id == 0x16 ? 0x80 : id == 0x17 ? 0x69 : 0x80; }

        @Override public void write(byte[] f) {
            int seq = ((f[2] & 0xff) << 8) | (f[3] & 0xff);
            int innerLen = ((f[9] & 0xff) << 8) | (f[10] & 0xff);
            byte[] inner = Arrays.copyOfRange(f, 11, 11 + innerLen);
            String hex = Hex.str(inner);
            sent.add(hex);
            if (!dongleAnswers) return;
            reply(seq, "00 00 02 00 00");
            if (!Protocols.wantsReply(inner)) return;
            if (hex.startsWith("61 05")) { reply(seq, wakeAnswers ? "02 00 02 08 08 00 01 cc" : "01 ff ff"); return; }
            if (hex.startsWith("61 04 00 06 68")) {              // generic request
                if (!wakeAnswers) reply(seq, "01 ff ff");
                else if (hex.contains("f1 01 00 c4")) reply(seq, "01 00 0a 48 6b d1 41 00 be 3e b8 14 8d");
                else if (hex.contains("f1 01 0c")) reply(seq, "01 00 08 48 6b d1 41 0c 0b 40 1c");
                else if (hex.contains("f1 01 0d")) reply(seq, "01 00 07 48 6b d1 41 0d 28 fa");
                else reply(seq, String.format("01 00 07 48 6b d1 41 %02x 50 00", inner[8] & 0xff));
                return;
            }
            if (!engineAnswers) { reply(seq, "01 ff ff"); return; }
            if (hex.equals("61 04 00 03 f4 0a 0b 01"))
                reply(seq, String.format("01 00 03 %02x 0b %02x", rpmRaw >> 8, rpmRaw & 0xff));
            else if (hex.equals("61 04 00 02 f4 16 01")) reply(seq, "01 00 02 16 80");
            else if (hex.equals("61 04 00 02 f4 17 01")) reply(seq, "01 00 02 17 69");
            else if (hex.startsWith("61 04 00 02 f4")) reply(seq, String.format("01 00 02 %02x 80", inner[5] & 0xff));
            else if (hex.startsWith("61 04 00 03 f4"))      // two ids: <first value> <second id> <second value>
                reply(seq, String.format("01 00 03 %02x %02x %02x", val(inner[5] & 0xff),
                        garblePairs ? 0xee : inner[6] & 0xff, val(inner[6] & 0xff)));
            else reply(seq, "01 00 01 00");
        }
        void reply(int seq, String payload) {
            byte[] r = DongleSessionTest.rx(seq, payload);
            session.onBytes(r, r.length);
        }
        @Override public void close() { }
    }

    static final class Rig {
        final Car car = new Car();
        final PollScheduler sched;
        final LiveValues values = new LiveValues();
        final long[] now = {0};
        final List<Long> sleeps = new ArrayList<>();
        final PollRunner runner;
        Rig(String... active) { this(TwoLine::byId, active); }
        /** With the real catalog (everything on the Jeep line). */
        static Rig real(String... active) { return new Rig(Catalog::byId, active); }
        Rig(java.util.function.Function<String, Param> lookup, String... active) {
            sched = new PollScheduler(60000, lookup);
            car.session = new DongleSession(car);
            runner = new PollRunner(car.session, sched, values, () -> now[0]);
            runner.setSleeper(sleeps::add);
            runner.setActive(Arrays.asList(active));
            runner.setTimeouts(30, 30, 30);
        }
        void steps(int n) throws IOException { for (int i = 0; i < n; i++) runner.step(); }
    }

    static List<String> plus(String first, String[] rest) {
        List<String> l = new ArrayList<>();
        l.add(first);
        l.addAll(Arrays.asList(rest));
        return l;
    }

    public static void sciSetupStopsAnyKeepAliveThenReadsRpm() throws Exception {
        Rig r = new Rig("rpm");
        T.eq(PollScheduler.Kind.SWITCH_TO_SCI, r.runner.step(), "first step switches to SCI");
        T.eq(plus(Protocols.STOP_KEEPALIVE, Protocols.SCI_SETUP), r.car.sent, "stop a leftover keep-alive, then SCI setup in order");
        r.car.sent.clear();
        T.eq(PollScheduler.Kind.READ_SCI, r.runner.step(), "then reads");
        T.eq(Arrays.asList(Protocols.SCI_FAST, "61 04 00 03 f4 0a 0b 01"), r.car.sent, "fast-baud command before every SCI read");
        T.near(720, r.values.get("rpm", 0), 0.001, "rpm decoded into live values");
        T.eq(1, r.runner.goodReads(), "one good read counted");
        T.ok(r.runner.vehicleAnswering(), "vehicle answering");
    }

    public static void genericSetupFollowsTheCapturedSequence() throws Exception {
        Rig r = new Rig("rpm", "speed");
        T.eq(PollScheduler.Kind.SWITCH_TO_GENERIC, r.runner.step(), "a generic-only value: everything on generic");
        List<String> want = plus(Protocols.STOP_KEEPALIVE, Protocols.GENERIC_SETUP_PRE);
        want.add(Protocols.GENERIC_INIT);
        want.add(Protocols.GENERIC_FIRST_QUERY);
        want.addAll(Arrays.asList(Protocols.GENERIC_SETUP_POST));
        T.eq(want, r.car.sent, "stop a leftover keep-alive, setup, wake-up, first query, keep-alive on, reply filter");
        T.eq(Arrays.asList(Protocols.GENERIC_PRE_WAIT_MS), r.sleeps, "waits 5 s before the wake-up, as the GearWrench app does");
        r.steps(2);
        T.near(720, r.values.get("rpm", 0), 0.001, "rpm read over generic (raw/4)");
        T.near(40 * 0.621371, r.values.get("speed", 0), 0.001, "speed decoded");
    }

    public static void leavingGenericStopsTheKeepAliveBeforeSciSetup() throws Exception {
        Rig r = new Rig("speed", "map_v");
        r.steps(4);                                        // SCI visit (map_v), then generic, one read
        r.now[0] = 60000;
        r.car.sent.clear();
        T.eq(PollScheduler.Kind.SWITCH_TO_SCI, r.runner.step(), "minute visit to the Jeep line");
        List<String> want = new ArrayList<>(Arrays.asList(Protocols.GENERIC_LEAVE));
        want.addAll(Arrays.asList(Protocols.SCI_SETUP));
        T.eq(want, r.car.sent, "keep-alive off (as the GearWrench app does) before SCI setup");
    }

    public static void valuesSurviveTheVisitToTheOtherLine() throws Exception {
        Rig r = new Rig("speed", "map_v");
        r.steps(3);                                        // t=0: SCI setup, map_v, generic setup
        r.now[0] = 59000;
        r.runner.step();                                   // speed at 59 s
        T.near(2.06, r.values.get("map_v", 59000), 0.01, "the Jeep-only reading is kept between its visits");
        r.now[0] = 60000;
        r.runner.step();                                   // switch to SCI
        r.now[0] = 61000;
        r.runner.step();                                   // map_v
        r.now[0] = 69000;                                  // generic wake-up took 8 s
        T.near(24.85, r.values.get("speed", 69000), 0.01, "speed held, not blanked, during the visit");
        r.runner.step();                                   // back on generic
        T.eq(null, r.values.get("speed", 73000), "but it blanks 3 s after the visit if generic stops answering");
    }

    public static void silentEngineReadsNothingAndReSetsUpAfterSixMisses() throws Exception {
        Rig r = new Rig("rpm");
        r.car.engineAnswers = false;
        r.runner.step();                                   // switch
        for (int i = 0; i < 6; i++) T.eq(PollScheduler.Kind.READ_SCI, r.runner.step(), "read " + i);
        T.eq(null, r.values.get("rpm", 0), "no value while the engine computer is silent");
        T.ok(!r.runner.vehicleAnswering(), "reports the vehicle as not answering");
        r.car.engineAnswers = true;                        // key turned on
        T.eq(PollScheduler.Kind.SWITCH_TO_SCI, r.runner.step(), "sets the line up again after six misses");
        r.runner.step();
        T.near(720, r.values.get("rpm", 0), 0.001, "reads again without touching the app");
        T.ok(r.runner.vehicleAnswering(), "answering again");
    }

    public static void deadDongleThrowsAfterThreeUnansweredFrames() throws Exception {
        Rig r = new Rig("rpm");
        r.car.dongleAnswers = false;
        boolean threw = false;
        try { r.runner.step(); } catch (IOException e) { threw = true; }
        T.ok(threw, "a dongle that stops answering is reported as a lost link");
        T.eq(3, r.car.sent.size(), "gives up after three unanswered frames");
    }

    public static void closedSessionThrows() throws Exception {
        Rig r = new Rig("rpm");
        r.car.session.close();
        boolean threw = false;
        try { r.runner.step(); } catch (IOException e) { threw = true; }
        T.ok(threw, "closed link throws so the service reconnects");
    }

    public static void deadGenericLineFallsBackToTheJeepLineAndComesBack() throws Exception {
        Rig r = new Rig("rpm", "speed");
        r.car.wakeAnswers = false;
        T.eq(PollScheduler.Kind.SWITCH_TO_GENERIC, r.runner.step(), "first try");
        T.ok(!r.car.sent.contains(Protocols.GENERIC_SETUP_POST[1]), "keep-alive is never started after a failed wake-up");
        T.eq(PollScheduler.Kind.SWITCH_TO_GENERIC, r.runner.step(), "second try");
        T.eq(PollScheduler.Kind.SWITCH_TO_SCI, r.runner.step(), "then the Jeep line");
        T.eq(PollScheduler.Kind.READ_SCI, r.runner.step(), "rpm from the Jeep line");
        T.near(720, r.values.get("rpm", 0), 0.001, "rpm shows even though generic is dead");
        T.ok(r.runner.vehicleAnswering(), "a dead generic line is not 'key off'");
        r.car.wakeAnswers = true;
        r.now[0] = 31000;
        T.eq(PollScheduler.Kind.SWITCH_TO_GENERIC, r.runner.step(), "retry after 30 s");
        r.steps(2);
        T.near(40 * 0.621371, r.values.get("speed", 31000), 0.001, "generic is back: speed shows");
    }

    public static void missesOnTheVisitedLineAreNotKeyOff() throws Exception {
        Rig r = new Rig("speed", "map_v");
        r.car.engineAnswers = false;                       // Jeep line silent, generic fine
        r.steps(4);
        for (int v = 1; v <= 4; v++) { r.now[0] = 60000L * v; r.steps(4); }
        T.ok(r.runner.vehicleAnswering(), "the working line answers; a silent visited line is just a missing value");
    }

    public static void idleWhenNothingActive() throws Exception {
        Rig r = new Rig();
        T.eq(PollScheduler.Kind.IDLE, r.runner.step(), "idle");
        T.eq(0, r.car.sent.size(), "nothing sent");
    }

    public static void exchangesAreTraced() throws Exception {
        Rig r = new Rig("rpm");
        Trace t = new Trace(100);
        r.runner.setTrace(t);
        r.runner.step();
        r.runner.step();
        String log = t.snapshot();
        T.ok(log.contains("60 05 19 01"), "setup commands are in the trace");
        T.ok(log.contains("f4 0a 0b") && log.contains("16 0b 80"), "reads are in the trace with their replies");
    }

    public static void traceKeepsOnlyTheLastLines() {
        Trace t = new Trace(3);
        for (int i = 1; i <= 5; i++) t.add("line " + i);
        T.eq("line 3\nline 4\nline 5\n", t.snapshot(), "oldest lines dropped");
    }

    public static void leavingTheJeepLineDropsItBackToSlowSpeedFirst() throws Exception {
        Rig r = new Rig("speed", "map_v");
        r.steps(2);                                        // SCI setup, map_v read (engine computer now in fast mode)
        r.car.sent.clear();
        T.eq(PollScheduler.Kind.SWITCH_TO_GENERIC, r.runner.step(), "on to generic");
        T.eq(Protocols.SCI_SLOW, r.car.sent.get(0), "first the app's own 'back to 7812 baud' command, so the line is quiet for the wake-up");
    }

    static final String[] DASH = {"speed", "rpm", "coolant", "iat", "spark", "map_kpa", "throttle"};

    public static void defaultGaugesNeverBlankOnTheJeepLineAtRealSpeed() throws Exception {
        Rig r = Rig.real(DASH);
        T.eq(PollScheduler.Kind.SWITCH_TO_SCI, r.runner.step(), "all seven live on the Jeep line");
        for (int i = 0; i < 200; i++) {
            r.now[0] += 260;
            T.eq(PollScheduler.Kind.READ_SCI, r.runner.step(), "only Jeep-line reads");
            if (i >= 15) for (String id : DASH) T.ok(r.values.get(id, r.now[0]) != null, id + " blank at read " + i);
        }
    }

    public static void genericGaugesNeverBlankAtRealSpeed() throws Exception {
        Rig r = new Rig(DASH);
        T.eq(PollScheduler.Kind.SWITCH_TO_GENERIC, r.runner.step(), "generic is the working line here");
        for (int i = 0; i < 200; i++) {
            r.now[0] += 330;
            r.runner.step();
            if (i >= 15) for (String id : DASH) T.ok(r.values.get(id, r.now[0]) != null, id + " blank at read " + i);
        }
    }

    public static void valuesBlankAsSoonAsTheEngineStopsAnswering() throws Exception {
        Rig r = new Rig("rpm", "coolant");
        r.steps(5);                                        // set-up, rpm three times, then coolant
        T.ok(r.values.get("coolant", 0) != null, "coolant was read");
        r.car.engineAnswers = false;
        r.steps(3);
        T.ok(!r.runner.vehicleAnswering(), "three misses: not answering");
        T.eq(null, r.values.get("coolant", 0), "no old reading stays up once the engine computer is silent");
    }

    public static void twoDeadWakeUpsWithNothingElseToReadMeansNotAnswering() throws Exception {
        Rig r = new Rig("speed");
        r.car.wakeAnswers = false;
        r.runner.step();
        T.ok(r.runner.vehicleAnswering(), "one failed wake-up is not yet 'key off' (the first try often fails)");
        r.runner.step();
        T.ok(!r.runner.vehicleAnswering(), "two in a row and nothing else to ask: say the key is off");
        r.car.wakeAnswers = true;
        r.now[0] = 31000;
        r.runner.step();
        T.ok(r.runner.vehicleAnswering(), "the wake-up answered: it is there");
    }

    public static void keyOnAfterALongKeyOffGetsTwoQuickGenericTries() throws Exception {
        Rig r = new Rig("rpm", "speed");
        r.car.wakeAnswers = false;
        r.car.engineAnswers = false;
        r.steps(2);
        for (int i = 1; i <= 6; i++) { r.now[0] = 130000L * i; r.steps(8); }     // key stays off; retries pile up
        T.ok(!r.runner.vehicleAnswering(), "key off");
        r.car.engineAnswers = true;                    // key on, but the first wake-up after it fails
        for (int i = 0; i < 20 && r.values.get("rpm", r.now[0]) == null; i++) r.runner.step();
        T.ok(r.runner.vehicleAnswering(), "key on is noticed on the Jeep line");
        T.eq(PollScheduler.Kind.SWITCH_TO_GENERIC, r.runner.step(), "generic is tried right away");
        r.car.wakeAnswers = true;
        T.eq(PollScheduler.Kind.SWITCH_TO_GENERIC, r.runner.step(), "and again, not after a two-minute back-off");
        r.steps(3);
        T.ok(r.values.get("speed", r.now[0]) != null, "speed is back");
    }

    public static void leavingStopsTheGenericKeepAlive() throws Exception {
        Rig r = new Rig("speed");
        r.steps(2);
        r.car.sent.clear();
        T.ok(r.runner.onGenericLine(), "on the generic line");
        r.runner.leave();
        T.ok(!r.runner.onGenericLine(), "and off it after leaving");
        T.eq(Arrays.asList(Protocols.GENERIC_LEAVE), r.car.sent, "orderly stop: the GearWrench app's own leave sequence");
        r.car.sent.clear();
        r.runner.leave();
        T.eq(new ArrayList<String>(), r.car.sent, "nothing left to stop");
    }

    public static void leavingTheJeepLineSendsNothing() throws Exception {
        Rig r = new Rig("rpm");
        r.steps(2);
        r.car.sent.clear();
        r.runner.leave();
        T.eq(new ArrayList<String>(), r.car.sent, "the Jeep line has no keep-alive to stop");
    }

    public static void everythingSentIsOnTheReadOnlyList() throws Exception {
        Rig two = new Rig("rpm", "speed", "map_v", "coolant");
        for (int i = 0; i < 400; i++) { two.now[0] += 300; two.runner.step(); }
        List<String> ids = new ArrayList<>();
        for (Param p : Catalog.all()) ids.add(p.id);
        Rig real = Rig.real(ids.toArray(new String[0]));
        real.steps(100);
        List<String> sent = new ArrayList<>(two.car.sent);
        sent.addAll(real.car.sent);
        T.ok(sent.size() > 400, "both rigs talked");
        for (String s : sent) T.ok(Protocols.allowed(Hex.bytes(s)), "sent something off the read-only list: " + s);
    }

    public static void twoOneByteValuesComeBackFromOneRead() throws Exception {
        Rig r = new Rig("coolant", "map_v");
        r.steps(2);                                        // set-up, then one read
        T.eq("61 04 00 03 f4 16 17 01", r.car.sent.get(r.car.sent.size() - 1), "both ids in one request");
        T.near(147.2, r.values.get("coolant", 0), 0.1, "first value");
        T.near(2.06, r.values.get("map_v", 0), 0.01, "second value");
        T.eq(2, r.runner.goodReads(), "two readings from one read");
    }

    public static void aPairWithTheWrongEchoFillsNothing() throws Exception {
        Rig r = new Rig("map_v", "spark");               // equal weights: every read is a pair
        r.car.garblePairs = true;
        r.steps(4);
        T.eq(null, r.values.get("map_v", 0), "a reply that does not name the second id is not trusted");
        T.eq(null, r.values.get("spark", 0), "for either value");
        T.ok(!r.runner.vehicleAnswering(), "three such replies count as no answer");
    }
}
