package com.strickland.jeepdash.core;

/** The cabin's own measured noise, by road speed, and the boost worked out from it. */
public class NoiseProfileTest {

    private static double[] flat(double db) {
        double[] b = new double[31];
        java.util.Arrays.fill(b, db);
        return b;
    }

    private static void hear(NoiseProfile p, double mph, double[] bands) {
        for (int i = 0; i < NoiseProfile.MIN_SAMPLES; i++) p.add(mph, bands);
    }

    public static void speedsAreGroupedByTens() {
        T.eq(0, NoiseProfile.bucket(0), "stopped");
        T.eq(0, NoiseProfile.bucket(9.9), "creeping counts as stopped");
        T.eq(1, NoiseProfile.bucket(10), "10 mph starts the next group");
        T.eq(4, NoiseProfile.bucket(47), "47 is in the forties");
        T.eq(9, NoiseProfile.bucket(200), "everything fast shares the last group");
        T.eq(0, NoiseProfile.bucket(-3), "a bad reading is stopped");
    }

    public static void notReadyUntilStoppedAndMovingAreBothHeard() {
        NoiseProfile p = new NoiseProfile();
        T.ok(!p.ready(), "empty");
        hear(p, 0, flat(-60));
        T.ok(!p.ready(), "stopped alone is not enough");
        p.add(45, flat(-48));
        T.ok(!p.ready(), "one moment at speed is not enough");
        hear(p, 45, flat(-48));
        T.ok(p.ready(), "stopped plus one speed is a profile");
        T.eq("stopped, 40 mph", p.summary(), "what has been learned, in words");
    }

    public static void riseIsMeasuredAgainstStopped() {
        NoiseProfile p = new NoiseProfile();
        hear(p, 0, flat(-60));
        hear(p, 45, flat(-48));
        T.near(12, p.riseBands(45)[10], 1e-6, "12 dB louder than stopped at 45 mph");
        T.near(0, p.riseBands(8)[10], 1e-6, "no rise while stopped");
        T.near(6, p.riseBands(27.5)[10], 1e-6, "half way up between stopped and the first speed heard");
        T.near(12, p.riseBands(80)[10], 1e-6, "held, not guessed, past the fastest speed heard");
    }

    public static void betweenTwoSpeedsItSlides() {
        NoiseProfile p = new NoiseProfile();
        hear(p, 0, flat(-60));
        hear(p, 35, flat(-54));
        hear(p, 65, flat(-44));
        T.near(11, p.riseBands(50)[3], 1e-6, "half way between 6 dB at 35 and 16 dB at 65");
    }

    public static void quieterThanStoppedIsNoRise() {
        NoiseProfile p = new NoiseProfile();
        hear(p, 0, flat(-50));
        hear(p, 30, flat(-55));
        T.near(0, p.riseBands(35)[10], 1e-6, "never a negative rise");
    }

    public static void boostIsHalfTheRiseScaledByStrengthAndCapped() {
        NoiseProfile p = new NoiseProfile();
        hear(p, 0, flat(-60));
        hear(p, 35, flat(-52));
        hear(p, 75, flat(-30));
        T.near(4, p.boostBands(35, 1)[10], 1e-6, "8 dB of noise earns 4 dB of boost");
        T.near(2, p.boostBands(35, 0.5)[10], 1e-6, "the strength slider scales it");
        T.near(6, p.boostBands(75, 1)[10], 1e-6, "never past 6 dB");
        T.near(0, p.boostBands(35, 0)[10], 1e-6, "strength 0 is off");
    }

    public static void theBoostFollowsTheMeasuredShape() {
        NoiseProfile p = new NoiseProfile();
        hear(p, 0, flat(-60));
        double[] road = flat(-58);
        for (int i = 0; i < 12; i++) road[i] = -50;          // road rumble: the bass comes up, the treble barely
        hear(p, 55, road);
        double[] b = p.boostBands(55, 1);
        T.near(5, b[4], 1e-6, "bass gets the boost");
        T.near(1, b[25], 1e-6, "treble gets little");
        T.ok(b[11] < 5 && b[11] > 1, "and the step between them is eased, not a cliff");
    }

    public static void textRoundTrip() {
        NoiseProfile p = new NoiseProfile();
        hear(p, 0, flat(-60.25));
        hear(p, 45, flat(-48));
        NoiseProfile back = NoiseProfile.fromText(p.toText());
        T.ok(back != null && back.ready(), "read back and still ready");
        T.near(12.25, back.riseBands(45)[7], 0.06, "levels survive to a tenth of a dB");
        T.eq(NoiseProfile.MIN_SAMPLES, back.samples(4), "sample counts survive");
        back.add(45, flat(-48));
        T.eq(NoiseProfile.MIN_SAMPLES + 1, back.samples(4), "and learning can carry on from it");
        T.ok(NoiseProfile.fromText(null) == null, "nothing stored");
        T.ok(NoiseProfile.fromText("junk") == null, "junk is nothing");
    }
}
