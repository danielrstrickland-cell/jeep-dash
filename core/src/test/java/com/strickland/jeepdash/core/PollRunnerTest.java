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
        boolean engineAnswers = true;
        boolean dongleAnswers = true;
        int rpmRaw = 0x1680;          // 720 rpm over SCI (raw/8)

        @Override public void write(byte[] f) {
            int seq = ((f[2] & 0xff) << 8) | (f[3] & 0xff);
            int innerLen = ((f[9] & 0xff) << 8) | (f[10] & 0xff);
            byte[] inner = Arrays.copyOfRange(f, 11, 11 + innerLen);
            String hex = Hex.str(inner);
            sent.add(hex);
            if (!dongleAnswers) return;
            reply(seq, "00 00 02 00 00");
            if (!Protocols.wantsReply(inner)) return;
            if (!engineAnswers) { reply(seq, "01 ff ff"); return; }
            if (hex.startsWith("61 05")) reply(seq, "01 00 02 08 08");
            else if (hex.equals("61 04 00 03 f4 0a 0b 01"))
                reply(seq, String.format("01 00 03 %02x 0b %02x", rpmRaw >> 8, rpmRaw & 0xff));
            else if (hex.equals("61 04 00 02 f4 16 01")) reply(seq, "01 00 02 16 80");
            else if (hex.startsWith("61 04 00 06 68 6a f1 01 0d")) reply(seq, "01 00 07 48 6b 10 41 0d 28 39");
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
        final PollScheduler sched = new PollScheduler(10000);
        final LiveValues values = new LiveValues();
        final long[] now = {0};
        final PollRunner runner;
        Rig(String... active) {
            car.session = new DongleSession(car);
            runner = new PollRunner(car.session, sched, values, () -> now[0]);
            runner.setActive(Arrays.asList(active));
            runner.setTimeouts(30, 30, 30);
        }
    }

    public static void sciSwitchSendsSetupThenReadsRpm() throws Exception {
        Rig r = new Rig("rpm");
        T.eq(PollScheduler.Kind.SWITCH_TO_SCI, r.runner.step(), "first step switches to SCI");
        T.eq(Arrays.asList(Protocols.SCI_SETUP), r.car.sent, "SCI setup sequence, in order");
        r.car.sent.clear();
        T.eq(PollScheduler.Kind.READ_SCI, r.runner.step(), "then reads");
        T.eq(Arrays.asList(Protocols.SCI_FAST, "61 04 00 03 f4 0a 0b 01"), r.car.sent, "fast-baud command before every SCI read");
        T.near(720, r.values.get("rpm", 0), 0.001, "rpm decoded into live values");
        T.eq(1, r.runner.goodReads(), "one good read counted");
        T.ok(r.runner.vehicleAnswering(), "vehicle answering");
    }

    public static void genericSwitchSendsWakeUpAndReadsSpeed() throws Exception {
        Rig r = new Rig("speed");
        T.eq(PollScheduler.Kind.SWITCH_TO_GENERIC, r.runner.step(), "generic-only: switches to generic");
        List<String> want = new ArrayList<>(Arrays.asList(Protocols.GENERIC_SETUP_PRE));
        want.add(Protocols.GENERIC_INIT);
        want.addAll(Arrays.asList(Protocols.GENERIC_SETUP_POST));
        T.eq(want, r.car.sent, "generic setup, wake-up, post-setup in order");
        r.runner.step();
        T.near(40 * 0.621371, r.values.get("speed", 0), 0.001, "speed decoded");
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

    public static void fastValuesHeldThroughGenericBlockAndSlowOnesBetweenBlocks() throws Exception {
        Rig r = new Rig("rpm", "speed");
        r.runner.step();                                   // switch to SCI at t=0
        r.now[0] = 9000;
        r.runner.step();                                   // rpm read at t=9 s
        r.now[0] = 10000;
        T.eq(PollScheduler.Kind.SWITCH_TO_GENERIC, r.runner.step(), "generic block starts");
        r.now[0] = 14000;                                  // wake-up took 4 s
        T.near(720, r.values.get("rpm", 14000), 0.001, "rpm held (not blanked) during the generic block");
        T.eq(PollScheduler.Kind.READ_GENERIC, r.runner.step(), "speed read");
        T.eq(PollScheduler.Kind.SWITCH_TO_SCI, r.runner.step(), "back to SCI");
        T.near(24.85, r.values.get("speed", 23000), 0.01, "speed still shown 9 s later, before the next block");
        T.eq(null, r.values.get("rpm", 18000), "rpm goes stale 3 s after the block if SCI stops answering");
        T.eq(null, r.values.get("speed", 14000 + 18001), "speed goes stale if a whole block is missed");
    }

    public static void idleWhenNothingActive() throws Exception {
        Rig r = new Rig();
        T.eq(PollScheduler.Kind.IDLE, r.runner.step(), "idle");
        T.eq(0, r.car.sent.size(), "nothing sent");
    }
}
