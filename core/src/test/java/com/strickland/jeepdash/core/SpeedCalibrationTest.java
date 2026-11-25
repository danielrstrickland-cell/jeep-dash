package com.strickland.jeepdash.core;

public class SpeedCalibrationTest {
    /** n steady 1 Hz pairs from t0, car reading pcm and GPS reading pcm * ratio. */
    private static long feed(SpeedCalibration c, int n, double pcm, double ratio, double acc, long t0) {
        long t = t0;
        for (int i = 0; i < n; i++, t += 1000) c.add(pcm, pcm * ratio, acc, t);
        return t;
    }

    public static void steadyCruiseGivesTheRatio() {
        SpeedCalibration c = new SpeedCalibration();
        feed(c, 20, 55, 1.036, 4, 0);
        SpeedCalibration.Result r = c.result();
        T.ok(r != null, "result");
        T.near(1.036, r.ratio, 1e-9, "ratio");
        T.eq(17, r.samples, "the first three seconds fill the window and do not count: " + r.samples);
        T.near(0, r.spread, 1e-9, "no spread");
        T.near(3.6, r.pctLow(), 1e-6, "3.6% low");
    }

    public static void suggestedConstantIsStockTimesRatio() {
        SpeedCalibration c = new SpeedCalibration();
        feed(c, 20, 60, 1.0, 3, 0);
        SpeedCalibration.Result r = c.result();
        T.eq(28_800_000L, r.suggestedConstant(), "ratio 1 keeps the stock constant");
        T.eq("0x01B77400", r.constantHex(), "stock constant in hex");
        SpeedCalibration d = new SpeedCalibration();
        feed(d, 20, 60, 1.0364, 3, 0);
        T.eq(Math.round(28_800_000 * 1.0364), d.result().suggestedConstant(), "scaled");
        T.eq("0x" + String.format("%08X", Math.round(28_800_000 * 1.0364)), d.result().constantHex(), "hex");
    }

    public static void poorGpsAccuracyIsIgnored() {
        SpeedCalibration c = new SpeedCalibration();
        feed(c, 30, 55, 1.03, 10.5, 0);
        T.eq(0, c.samples(), "11 m is too loose");
        T.ok(c.result() == null, "no result");
        feed(c, 30, 55, 1.03, Double.NaN, 100_000);
        T.eq(0, c.samples(), "unknown accuracy is too loose");
        feed(c, 30, 55, 1.03, 10.0, 200_000);
        T.ok(c.samples() > 0, "exactly 10 m is fine");
    }

    public static void slowSpeedsAreIgnored() {
        SpeedCalibration c = new SpeedCalibration();
        feed(c, 30, 24.9, 1.03, 3, 0);
        T.eq(0, c.samples(), "car under 25 mph");
        feed(c, 30, 26, 0.95, 3, 100_000);      // gps 24.7
        T.eq(0, c.samples(), "gps under 25 mph");
        feed(c, 30, 25, 1.0, 3, 200_000);
        T.ok(c.samples() > 0, "25 mph counts");
    }

    public static void changingSpeedIsIgnored() {
        SpeedCalibration c = new SpeedCalibration();
        for (int i = 0; i < 30; i++) c.add(40 + i, (40 + i) * 1.03, 3, i * 1000L);      // 1 mph a second
        T.eq(0, c.samples(), "accelerating");
        SpeedCalibration d = new SpeedCalibration();
        for (int i = 0; i < 30; i++) d.add(50 + (i % 2) * 0.4, 52 + (i % 2) * 0.4, 3, i * 1000L);   // wobble under 1 mph
        T.ok(d.samples() > 20, "small wobble is steady: " + d.samples());
        SpeedCalibration e = new SpeedCalibration();
        for (int i = 0; i < 30; i++) e.add(50, 51.5 + (i < 15 ? 0 : 1.2), 3, i * 1000L);       // GPS stepped 1.2
        T.ok(e.samples() < 27 && e.samples() > 0, "only the settled stretches count: " + e.samples());
    }

    public static void aShortWindowIsNotEnough() {
        SpeedCalibration c = new SpeedCalibration();
        c.add(50, 51, 3, 0);
        c.add(50, 51, 3, 1000);
        T.eq(0, c.samples(), "under 2.5 s of steady speed");
        c.add(50, 51, 3, 3000);
        T.eq(1, c.samples(), "3 s steady");
    }

    public static void medianIgnoresOddOnes() {
        SpeedCalibration c = new SpeedCalibration();
        feed(c, 30, 55, 1.02, 3, 0);
        long t = 30_000;
        for (int i = 0; i < 9; i++, t += 1000) c.add(55, 55 * 1.3, 3, t);      // a steady stretch of bad GPS (5 count)
        SpeedCalibration.Result r = c.result();
        T.near(1.02, r.ratio, 1e-9, "median unmoved by a few odd ones");
        T.ok(r.spread > 0.01, "but the spread shows them");
    }

    public static void tooFewSamplesGiveNoResult() {
        SpeedCalibration c = new SpeedCalibration();
        feed(c, 10, 55, 1.03, 3, 0);      // 7 count
        T.ok(c.samples() < SpeedCalibration.MIN_SAMPLES, "under the minimum");
        T.ok(c.result() == null, "null");
    }

    public static void summaryTextSaysLowOrHigh() {
        SpeedCalibration c = new SpeedCalibration();
        feed(c, 20, 55, 1.036, 3, 0);
        T.eq("Speedometer reads 3.6% low · 17 samples · suggested constant " + c.result().constantHex(), c.result().summary(), "low");
        SpeedCalibration d = new SpeedCalibration();
        feed(d, 20, 55, 0.97, 3, 0);
        T.ok(d.result().summary().startsWith("Speedometer reads 3.0% high · 17 samples"), d.result().summary());
        SpeedCalibration e = new SpeedCalibration();
        feed(e, 20, 55, 1.0, 3, 0);
        T.ok(e.result().summary().startsWith("Speedometer matches GPS"), e.result().summary());
    }
}
