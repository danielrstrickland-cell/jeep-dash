package com.strickland.jeepdash.core;

public final class GfxStatsTest {
    public static void averagesAndLows() {
        GfxStats s = new GfxStats();
        for (int i = 0; i < 99; i++) s.frame(1000 / 60.0, 0.8);
        s.frame(50, 0.8);                                          // one long hitch
        T.near(1000 * 100 / (99 * 1000 / 60.0 + 50), s.fps(), 0.01, "average fps");
        T.near(20, s.low1(), 0.5, "the 1% low is the hitch");
        T.near(0.8, s.detail(), 1e-9, "detail averaged");
        T.ok(s.line().contains("gpu n/a"), "no GPU numbers until frame metrics arrive");
        s.metrics(12, 3);
        s.metrics(14, 5);
        T.near(13, s.gpu(), 1e-9, "GPU time averaged");
        T.ok(s.line().contains("gpu 13.0 ms") && s.line().contains("cpu 4.0 ms"), s.line());
        s.clear();
        T.eq(0, s.frames(), "cleared");
    }
}
