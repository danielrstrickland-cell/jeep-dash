package com.strickland.jeepdash.core;

public class PauseGateTest {
    public static void startsRunning() {
        long[] t = {0};
        PauseGate g = new PauseGate(() -> t[0]);
        T.ok(!g.isPaused(), "not paused before any broadcast");
    }

    public static void busyTrueThenFalse() {
        long[] t = {1000};
        PauseGate g = new PauseGate(() -> t[0]);
        g.onBusy(true);
        T.ok(g.isPaused(), "paused on busy=true");
        t[0] += 10000;
        T.ok(g.isPaused(), "still paused 10 s later");
        g.onBusy(false);
        T.ok(!g.isPaused(), "resumed on busy=false");
        T.ok(!g.endedByTimeout(), "not a timeout");
    }

    public static void heartbeatKeepsItPausedPastNinetySeconds() {
        long[] t = {0};
        PauseGate g = new PauseGate(() -> t[0]);
        g.onBusy(true);
        for (int i = 0; i < 6; i++) {         // 3 minutes of 30 s heartbeats
            t[0] += 30000;
            T.ok(g.isPaused(), "paused at heartbeat " + i);
            g.onBusy(true);
        }
        T.ok(g.isPaused(), "still paused");
    }

    public static void resumesWhenHeartbeatStops() {
        long[] t = {5000};
        PauseGate g = new PauseGate(() -> t[0]);
        g.onBusy(true);
        t[0] += 89999;
        T.ok(g.isPaused(), "paused at 89.999 s");
        t[0] += 1;
        T.ok(!g.isPaused(), "resumed at 90 s");
        T.ok(g.endedByTimeout(), "ended by the failsafe");
    }

    public static void newBusyAfterTimeoutPausesAgain() {
        long[] t = {0};
        PauseGate g = new PauseGate(() -> t[0]);
        g.onBusy(true);
        t[0] = 100000;
        T.ok(!g.isPaused(), "timed out");
        g.onBusy(true);
        T.ok(g.isPaused(), "paused again");
        T.ok(!g.endedByTimeout(), "flag cleared");
    }

    public static void busyFalseWithoutBusyTrueIsHarmless() {
        PauseGate g = new PauseGate(() -> 0);
        g.onBusy(false);
        T.ok(!g.isPaused(), "still running");
    }
}
