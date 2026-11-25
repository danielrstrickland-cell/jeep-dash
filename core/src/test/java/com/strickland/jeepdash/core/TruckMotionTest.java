package com.strickland.jeepdash.core;

/** The 3D truck's motion: spring-damper body, mount detection, GPS turn rate, and the blend between them. */
public final class TruckMotionTest {
    private TruckMotionTest() { }

    private static final double G = 9.80665;

    // ---------------- spring and damper ----------------

    public static void springSettlesOnItsTargetAfterALittleOvershoot() {
        SpringDamper s = new SpringDamper(1.3, 0.5);
        s.setTarget(10);
        double peak = 0;
        for (int i = 0; i < 300; i++) {           // 10 s at 30 frames a second
            s.step(1 / 30.0);
            peak = Math.max(peak, s.value());
        }
        T.ok(peak > 10.5 && peak < 13, "overshoots a little: " + peak);
        T.near(10, s.value(), 0.01, "settles on the target");
        T.ok(s.settled(0.05, 0.1), "and says so");
    }

    public static void springStaysStableThroughASlowFrame() {
        SpringDamper s = new SpringDamper(1.3, 0.5);
        s.setTarget(5);
        s.step(0.4);                               // a 400 ms hitch
        s.step(5);                                 // and a very long one
        T.ok(Math.abs(s.value()) < 20 && !Double.isNaN(s.value()), "no blow-up: " + s.value());
    }

    // ---------------- body roll and pitch ----------------

    public static void aLeftTurnLeansTheBodyRightAndBrakingDipsTheNose() {
        BodyMotion b = new BodyMotion(0);
        for (int i = 0; i < 300; i++) b.step(0.3, -0.4, 1 / 30.0);
        T.near(0.3 * BodyMotion.ROLL_REAL, b.rollDeg(), 0.05, "realistic roll, leaning right");
        T.near(-0.4 * BodyMotion.PITCH_REAL, b.pitchDeg(), 0.05, "nose down under braking");
    }

    public static void strengthGoesFromRealisticToExaggeratedAndIsCapped() {
        BodyMotion real = new BodyMotion(0), big = new BodyMotion(1);
        for (int i = 0; i < 300; i++) {
            real.step(0.3, 0.2, 1 / 30.0);
            big.step(0.3, 0.2, 1 / 30.0);
        }
        T.ok(big.rollDeg() > real.rollDeg() * 2.5, "exaggerated leans much further");
        T.ok(big.pitchDeg() > real.pitchDeg() * 2.5, "and pitches much further");
        BodyMotion wild = new BodyMotion(1);
        for (int i = 0; i < 300; i++) wild.step(3, -3, 1 / 30.0);
        T.ok(wild.rollDeg() <= BodyMotion.ROLL_LIMIT * 1.3, "roll is capped (overshoot aside)");
        T.ok(wild.pitchDeg() >= -BodyMotion.PITCH_LIMIT * 1.3, "pitch is capped (overshoot aside)");
    }

    // ---------------- mount detection ----------------

    /** Upright on the dash, screen to the driver: gravity along the screen's y axis. */
    private static void upright(MountDetector m, long fromMs, long toMs, double latG, double lonG) {
        for (long t = fromMs; t < toMs; t += 20) {
            double jitter = Math.sin(t * 0.37) * 0.15;               // engine buzz
            // left is the screen's -x, forward is the screen's -z (into the screen)
            m.sample(-latG * G + jitter, G + jitter, -lonG * G, t);
        }
    }

    public static void aSteadyUprightTabletCountsAsMountedAfterAFewSeconds() {
        MountDetector m = new MountDetector();
        upright(m, 0, 3000, 0, 0);
        T.ok(!m.mounted(), "not yet");
        upright(m, 3000, 7000, 0, 0);
        T.ok(m.mounted(), "mounted after a steady spell");
    }

    public static void mountedSensorGivesVehicleFrameG() {
        MountDetector m = new MountDetector();
        upright(m, 0, 7000, 0, 0);
        upright(m, 7000, 9000, 0.3, 0);
        T.ok(m.mounted(), "a 0.3 g turn does not unmount it");
        T.near(0.3, m.latG(), 0.03, "left turn reads positive lateral G");
        T.near(0, m.lonG(), 0.03, "no fore-aft");
        upright(m, 9000, 11000, 0, 0.25);
        T.near(0.25, m.lonG(), 0.03, "pulling away reads positive");
    }

    public static void aKnockIsIgnoredWhileMounted() {
        MountDetector m = new MountDetector();
        upright(m, 0, 7000, 0, 0);
        m.sample(30, -20, 25, 7000);                             // one hard knock
        T.ok(m.mounted(), "still mounted");
        T.near(0, m.latG(), 0.02, "the knock did not reach the lateral G");
        T.near(0, m.lonG(), 0.02, "nor the fore-aft");
    }

    public static void aTabletLyingFlatIsNotMounted() {
        MountDetector m = new MountDetector();
        for (long t = 0; t < 15000; t += 20) m.sample(0.05, 0.05, G, t);
        T.ok(!m.mounted(), "flat on a seat: which way is forward is unknown");
        T.eq(null, m.latG(), "no sensor G");
    }

    public static void aHandHeldTabletIsNotMounted() {
        MountDetector m = new MountDetector();
        for (long t = 0; t < 20000; t += 20) {
            double a = Math.toRadians(25 * Math.sin(t / 700.0) + 6 * Math.sin(t / 97.0));
            double shake = 1.5 * Math.sin(t / 31.0);
            m.sample(G * Math.sin(a) + shake, G * Math.cos(a), 2.5 * Math.sin(t / 300.0) + shake, t);
        }
        T.ok(!m.mounted(), "wobbling in a hand");
    }

    public static void pickingTheTabletUpUnmountsIt() {
        MountDetector m = new MountDetector();
        upright(m, 0, 7000, 0, 0);
        for (long t = 7000; t < 8000; t += 20) m.sample(0.2, 0.3, G, t);     // laid down flat
        T.ok(!m.mounted(), "turned flat: no longer mounted");
    }

    public static void screenAxesFollowTheDisplayRotation() {
        // a phone turned a quarter anticlockwise (rotation 1): its right edge points up
        double[] s = MountDetector.toScreen(1, G, 0, 0);
        T.near(0, s[0], 1e-9, "x");
        T.near(G, s[1], 1e-9, "up the screen");
        s = MountDetector.toScreen(1, 0, G, 0);                  // its top now points left
        T.near(-G, s[0], 1e-9, "to the screen's left");
        s = MountDetector.toScreen(2, 1, 2, 3);
        T.near(-1, s[0], 1e-9, "upside down x");
        T.near(-2, s[1], 1e-9, "upside down y");
        s = MountDetector.toScreen(3, 1, 2, 3);
        T.near(2, s[0], 1e-9, "rotation 3 x");
        T.near(-1, s[1], 1e-9, "rotation 3 y");
        T.near(3, s[2], 1e-9, "z never changes");
    }

    // ---------------- GPS ----------------

    public static void lateralGFromHeadingChangeTimesSpeed() {
        GpsTurn g = new GpsTurn();
        double heading = 90;
        for (long t = 0; t <= 5000; t += 1000) {
            g.fix(20, heading, t);
            heading -= 10;                                       // 10°/s to the left
        }
        T.near(Math.toRadians(10), g.yawRate(5000), 0.01, "turning left");
        T.near(20 * Math.toRadians(10) / G, g.latG(5000), 0.01, "v times turn rate");
    }

    public static void headingWrapsThroughNorth() {
        GpsTurn g = new GpsTurn();
        g.fix(15, 355, 0);
        g.fix(15, 5, 1000);
        g.fix(15, 15, 2000);
        T.ok(g.yawRate(2000) < 0, "355 to 5 is a right turn, not a spin");
        T.near(-Math.toRadians(10), g.yawRate(2000), 0.02, "10°/s right");
    }

    public static void slowOrStaleGpsGivesNoTurn() {
        GpsTurn g = new GpsTurn();
        g.fix(1, 0, 0);
        g.fix(1, 90, 1000);                                      // parking-lot crawl: heading is noise
        T.near(0, g.yawRate(1000), 1e-9, "ignored below walking pace");
        g.fix(20, 0, 2000);
        g.fix(20, 20, 3000);
        T.ok(g.yawRate(3000) != 0, "a real turn");
        T.near(0, g.yawRate(9000), 1e-9, "stale after a few seconds");
        T.eq(null, g.lonG(9000), "no fore-aft from old fixes");
    }

    public static void fixesGiveForeAftFromSpeedChange() {
        GpsTurn g = new GpsTurn();
        g.fix(10, Double.NaN, 0);
        g.fix(12, Double.NaN, 1000);
        T.near(2 / G, g.lonG(1000), 0.02, "2 m/s per second");
    }

    // ---------------- the blend ----------------

    public static void blendMovesSmoothlyFromGpsToTheMountedSensor() {
        TruckMotion m = new TruckMotion(0);
        long t = 0;
        double prevW = 0, maxJump = 0;
        for (; t < 20000; t += 33) {
            double jitter = Math.sin(t * 0.37) * 0.15;
            m.mount.sample(jitter, G + jitter, 0, t);
            m.step(t, 0.033);
            maxJump = Math.max(maxJump, Math.abs(m.sensorWeight() - prevW));
            prevW = m.sensorWeight();
        }
        T.near(1, m.sensorWeight(), 1e-6, "all sensor once mounted");
        T.ok(maxJump < 0.05, "never a jump: " + maxJump);
    }

    public static void gpsDrivesTheTruckWhenTheTabletIsLoose() {
        TruckMotion m = new TruckMotion(0);
        double heading = 0;
        for (long t = 0; t <= 6000; t += 33) {
            if (t % 1000 < 33) {
                m.gps.fix(20, heading, t);
                heading -= 10;
            }
            m.road(20 / 0.44704, t);
            m.step(t, 0.033);
        }
        T.eq(0.0, m.sensorWeight(), "no mount: GPS only");
        T.ok(m.latG() > 0.25, "left turn from GPS: " + m.latG());
        T.ok(m.rollDeg() > 1, "body leans right");
        T.ok(m.steerDeg() > 0, "front wheels steer left");
    }

    public static void aWildInputIsClampedAndSlewLimited() {
        TruckMotion m = new TruckMotion(1);
        m.gps.fix(30, 0, 0);
        m.gps.fix(30, -60, 1000);                                // 60°/s at 30 m/s: an impossible 3 g
        m.step(1000, 0.033);
        T.ok(Math.abs(m.latG()) <= TruckMotion.G_SLEW * 0.033 + 1e-9, "one frame moves it only a little: " + m.latG());
        for (int i = 0; i < 60; i++) m.step(1000 + i * 33, 0.033);
        T.ok(Math.abs(m.latG()) <= TruckMotion.G_LIMIT + 1e-9, "never past the limit: " + m.latG());
    }

    public static void wheelsTurnAtRoadSpeed() {
        TruckMotion m = new TruckMotion(0);
        double mps = 20;
        for (int i = 0; i <= 30; i++) {
            m.road(mps / 0.44704, i * 33);
            m.step(i * 33, 1 / 30.0);
        }
        // 31 frames of 1/30 s, the first with no speed yet known? road() came first, so all count
        double expect = (mps / TruckMotion.TIRE_RADIUS_M) * 31 / 30.0;
        double got = m.wheelTurnRad();
        double diff = Math.IEEEremainder(expect - got, 2 * Math.PI);
        T.near(0, diff, 0.05, "the wheel angle follows v / r");
        T.ok(m.moving(), "rolling counts as moving");
    }

    public static void parkedAndSettledIsNotMoving() {
        TruckMotion m = new TruckMotion(0);
        for (int i = 0; i < 200; i++) {
            m.road(0, i * 33);
            m.step(i * 33, 1 / 30.0);
        }
        T.ok(!m.moving(), "nothing to animate");
        T.near(0, m.steerDeg(), 1e-6, "wheels straight");
    }

    public static void steerFollowsTurnRateLikeABicycle() {
        T.near(Math.toDegrees(Math.atan(TruckMotion.WHEELBASE_M * 0.2 / 10)), TruckMotion.steerFor(0.2, 10), 1e-9, "bicycle model");
        T.near(TruckMotion.MAX_STEER_DEG, TruckMotion.steerFor(5, 3), 1e-9, "capped");
        T.near(0, TruckMotion.steerFor(0.3, 0), 1e-9, "no steer when stopped");
    }
}
