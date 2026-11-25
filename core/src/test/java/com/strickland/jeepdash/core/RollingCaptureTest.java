package com.strickland.jeepdash.core;

import java.util.Arrays;

public class RollingCaptureTest {
    private static RollingCapture.Capture run(RollingCapture rc, LiveValues v, long from, long to, long step) {
        RollingCapture.Capture done = null;
        for (long t = from; t <= to; t += step) {
            v.put("rpm", t / 10.0, t);
            RollingCapture.Capture c = rc.sample(t, v);
            if (c != null) done = c;
        }
        return done;
    }

    public static void keepsThirtySecondsBeforeAndAfter() {
        RollingCapture rc = new RollingCapture(Arrays.asList("rpm", "coolant"));
        LiveValues v = new LiveValues();
        T.ok(run(rc, v, 0, 100000, 1000) == null, "nothing finished without a trigger");
        T.ok(rc.trigger(100000, "P0303"), "trigger accepted");
        T.ok(rc.pending(), "pending");
        RollingCapture.Capture c = run(rc, v, 101000, 130000, 1000);
        T.ok(c != null, "finished 30 s after the trigger");
        T.ok(!rc.pending(), "no longer pending");
        T.eq(70000L, c.times.get(0), "first row is 30 s before");
        T.eq(130000L, c.times.get(c.times.size() - 1), "last row is 30 s after");
        T.eq(61, c.rows.size(), "31 before (inclusive) + 30 after");
        T.eq("P0303", c.reason, "reason");
    }

    public static void csvHasTriggerLineHeaderAndRelativeSeconds() {
        RollingCapture rc = new RollingCapture(Arrays.asList("rpm", "coolant"), 2000, 1000);
        LiveValues v = new LiveValues();
        run(rc, v, 0, 5000, 1000);
        rc.trigger(5000, "P0300");
        RollingCapture.Capture c = run(rc, v, 6000, 6000, 1000);
        T.eq("# trigger=P0300\ntime_ms,rel_s,rpm,coolant\n"
                + "3000,-2.0,300.0,\n4000,-1.0,400.0,\n5000,0.0,500.0,\n6000,1.0,600.0,\n", c.toCsv(), "csv");
    }

    public static void secondTriggerWhileRunningIsIgnored() {
        RollingCapture rc = new RollingCapture(Arrays.asList("rpm"), 1000, 1000);
        T.ok(rc.trigger(0, "a"), "first");
        T.ok(!rc.trigger(500, "b"), "second ignored");
    }

    public static void triggerWithEmptyBufferStillCaptures() {
        RollingCapture rc = new RollingCapture(Arrays.asList("rpm"), 1000, 1000);
        LiveValues v = new LiveValues();
        rc.trigger(0, "x");
        RollingCapture.Capture c = run(rc, v, 500, 1000, 500);
        T.eq(2, c.rows.size(), "only the after rows");
    }

    public static void finishGivesPartialCapture() {
        RollingCapture rc = new RollingCapture(Arrays.asList("rpm"), 5000, 5000);
        LiveValues v = new LiveValues();
        run(rc, v, 0, 3000, 1000);
        rc.trigger(3000, "x");
        run(rc, v, 4000, 5000, 1000);
        RollingCapture.Capture c = rc.finish();
        T.eq(6, c.rows.size(), "0..5 s");
        T.ok(rc.finish() == null, "nothing left to finish");
    }

    public static void oldRowsAreDropped() {
        RollingCapture rc = new RollingCapture(Arrays.asList("rpm"), 3000, 0);
        LiveValues v = new LiveValues();
        run(rc, v, 0, 100000, 1000);
        rc.trigger(100000, "x");
        T.eq(4, rc.finish().rows.size(), "only 3 s kept before the trigger");
    }
}
