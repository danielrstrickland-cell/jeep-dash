package com.strickland.jeepdash.core;

import java.util.Arrays;

public class PollSchedulerTest {
    static PollScheduler sched(String... ids) {
        PollScheduler s = new PollScheduler(10000);
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
    }

    public static void genericBlockEveryInterval() {
        PollScheduler s = sched("rpm", "speed");
        T.eq("SWITCH_TO_SCI", show(s.next(0)), "first step");
        for (long t = 1; t < 10000; t += 250) T.eq("READ_SCI rpm", show(s.next(t)), "SCI read at " + t);
        T.eq("SWITCH_TO_GENERIC", show(s.next(10000)), "block starts");
        T.eq(Source.GENERIC, s.currentBus(), "on generic");
        T.eq("READ_GENERIC speed", show(s.next(10001)), "generic-only read");
        T.eq("SWITCH_TO_SCI", show(s.next(10002)), "back to SCI");
        T.eq(Source.SCI, s.currentBus(), "on SCI again");
        T.eq("READ_SCI rpm", show(s.next(10003)), "SCI reads resume");
    }

    public static void allGenericStaysGeneric() {
        PollScheduler s = sched("speed", "throttle");
        T.eq("SWITCH_TO_GENERIC", show(s.next(0)), "first step");
        String prev = null;
        for (int i = 0; i < 30; i++) {
            PollScheduler.Step st = s.next(1000L * i + 1);
            T.eq(PollScheduler.Kind.READ_GENERIC, st.kind, "generic read " + i);
            T.ok(!st.param.id.equals(prev), "alternates at " + i);
            prev = st.param.id;
        }
    }

    public static void emptyIsIdle() {
        T.eq("IDLE", show(sched().next(0)), "nothing active");
    }
}
