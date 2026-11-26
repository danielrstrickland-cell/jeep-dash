package com.strickland.jeepdash.core;

/** The adaptive sound rules: scenes, the auto modes, noise compensation, the voltage-safe sub and port guard. */
public class SoundAdaptTest {

    public static void sceneFollowsSpeedWithoutFlapping() {
        SoundAdapt a = new SoundAdapt();
        T.eq(SoundAdapt.Scene.IDLE, a.scene(0), "stopped is idle");
        T.eq(SoundAdapt.Scene.IDLE, a.scene(4), "creeping is still idle");
        T.eq(SoundAdapt.Scene.CRUISE, a.scene(30), "moving is cruise");
        T.eq(SoundAdapt.Scene.HIGHWAY, a.scene(60), "fast is highway");
        T.eq(SoundAdapt.Scene.HIGHWAY, a.scene(50), "dipping to 50 stays highway");
        T.eq(SoundAdapt.Scene.CRUISE, a.scene(45), "under 47 is cruise again");
        T.eq(SoundAdapt.Scene.CRUISE, a.scene(3), "slowing to 3 stays cruise");
        T.eq(SoundAdapt.Scene.IDLE, a.scene(1), "under 2 is idle again");
    }

    public static void idleCutIsCentredOnTheBoom() {
        T.near(-3, SoundAdapt.sceneOffsetDb(SoundAdapt.Scene.IDLE, 50), 0.01, "full cut at 50 Hz");
        T.ok(SoundAdapt.sceneOffsetDb(SoundAdapt.Scene.IDLE, 1000) > -0.01, "nothing at 1 kHz");
        T.near(0, SoundAdapt.sceneOffsetDb(SoundAdapt.Scene.CRUISE, 50), 1e-9, "no cut when moving");
    }

    public static void autoModes() {
        T.ok(SoundAdapt.AutoMode.AUTO.mayAdjust(60), "auto adjusts while driving");
        T.ok(!SoundAdapt.AutoMode.PARKED_ONLY.mayAdjust(60), "parked-only does not");
        T.ok(SoundAdapt.AutoMode.PARKED_ONLY.mayAdjust(0), "parked-only adjusts when stopped");
        T.ok(SoundAdapt.AutoMode.AUTO_NOTE.showsNote(), "auto+note shows a note");
        T.eq(SoundAdapt.AutoMode.AUTO, SoundAdapt.AutoMode.of("nonsense"), "unknown name is auto");
    }

    public static void noiseBoostGrowsWithSpeedAndStaysBounded() {
        T.near(0, SoundAdapt.noiseBoostDb(100, 10, 800, 1), 1e-9, "nothing in town at idle rpm");
        double mid = SoundAdapt.noiseBoostDb(100, 50, 1500, 1), fast = SoundAdapt.noiseBoostDb(100, 80, 1500, 1);
        T.ok(mid > 0 && fast > mid, "more at speed");
        T.near(6, fast, 1e-9, "full boost in the bass at 80 mph");
        T.ok(SoundAdapt.noiseBoostDb(8000, 80, 1500, 1) < 1.0, "little in the treble");
        T.near(fast / 2, SoundAdapt.noiseBoostDb(100, 80, 1500, 0.5), 1e-9, "strength scales it");
        T.ok(SoundAdapt.noiseBoostDb(100, 200, 9000, 5) <= 6.0, "never past 6 dB");
        T.ok(SoundAdapt.noiseBoostDb(100, 0, 4000, 1) > 0, "a revved engine counts when stopped");
    }

    public static void voiceLiftSitsWhereSpeechIs() {
        T.near(4, SoundAdapt.voiceLiftDb(2500), 1e-9, "most at 2.5 kHz");
        T.near(SoundAdapt.voiceLiftDb(1250), SoundAdapt.voiceLiftDb(5000), 1e-9, "the same an octave either side");
        T.ok(SoundAdapt.voiceLiftDb(1250) > 1 && SoundAdapt.voiceLiftDb(1250) < 3, "easing off through the speech range");
        T.ok(SoundAdapt.voiceLiftDb(100) < 0.05 && SoundAdapt.voiceLiftDb(16000) < 0.2, "none in the bass or the very top");
    }

    public static void levellingBringsSpeechUpAndEffectsDown() {
        double speech = SoundAdapt.levelledDb(-40), bang = SoundAdapt.levelledDb(-12);
        T.near(-36, speech, 1e-9, "quiet speech comes up by the make-up gain");
        T.near(-18.8, bang, 1e-9, "a loud effect comes down");
        T.ok(bang - speech < 18, "so the gap between them shrinks from 28 dB to about 17");
        T.ok(SoundAdapt.levelledDb(-5) <= -5, "and nothing loud is made louder");
    }

    public static void engineNoiseOnItsOwn() {
        T.near(3, SoundAdapt.engineBoostDb(100, 4000, 1), 1e-9, "a fully revved engine is worth half the road's most");
        T.near(0, SoundAdapt.engineBoostDb(100, 1200, 1), 1e-9, "nothing at a normal idle");
        T.near(SoundAdapt.engineBoostDb(100, 4000, 1), SoundAdapt.noiseBoostDb(100, 0, 4000, 1), 1e-9,
                "stopped, the whole noise boost is the engine's");
    }

    public static void subTrimFollowsVoltage() {
        T.near(0, SoundAdapt.subTrimDb(14.1), 1e-9, "healthy rail, nothing off");
        T.near(0, SoundAdapt.subTrimDb(0), 1e-9, "unknown voltage, nothing off");
        T.near(-1.5, SoundAdapt.subTrimDb(12.5), 0.01, "half way down the slope");
        T.near(-3, SoundAdapt.subTrimDb(11.8), 1e-9, "3 dB off at 11.8 V");
        T.near(-6, SoundAdapt.subTrimDb(10.5), 1e-9, "6 dB off when it is really low");
    }

    public static void portGuardSitsUnderTuning() {
        T.near(28.9, SoundAdapt.subsonicHz(34), 0.01, "filter at 85 % of tuning");
        T.near(0, SoundAdapt.portGuardDb(40, 34), 1e-9, "nothing above the filter");
        T.near(-24, SoundAdapt.portGuardDb(14.45, 34), 0.01, "24 dB down an octave below");
        T.near(0, SoundAdapt.portGuardDb(20, 0), 1e-9, "no tuning known, no guard");
    }
}
