package com.strickland.jeepdash.core;

public class VizTuneTest {
    static boolean has(int look, String id) {
        for (VizTune.Knob k : VizTune.knobs(look)) if (k.id.equals(id)) return true;
        return false;
    }

    public static void everyLookStartsAtItsOwnDefaults() {
        VizTune t = new VizTune();
        T.near(1, t.get(0, "gain"), 1e-6, "sensitivity");
        T.near(0.78, t.get(0, "width"), 1e-6, "blocks are wider");
        T.near(0.72, t.get(1, "width"), 1e-6, "than smooth bars");
        T.ok(t.on(0, "caps"), "peak caps start on");
    }

    public static void eachLookHasItsOwnKnobs() {
        T.ok(has(4, "trail") && !has(0, "trail"), "trail length belongs to the swirl");
        T.ok(has(3, "pulse"), "the ring has its own");
        T.ok(!has(2, "needle"), "the boom meters are gone: their place keeps only the shared knobs");
        for (int look = 0; look < 5; look++) T.ok(has(look, "gain") && has(look, "glow"), "shared knobs on look " + look);
    }

    public static void eachLookRemembersItsOwnSettings() {
        VizTune t = new VizTune();
        t.set(0, "gain", 1.5f);
        T.near(1.5, t.get(0, "gain"), 1e-6, "changed on blocks");
        T.near(1, t.get(1, "gain"), 1e-6, "untouched on smooth");
    }

    public static void valuesStayInRangeAndSwitchesSnap() {
        VizTune t = new VizTune();
        t.set(0, "gain", 99);
        T.near(2, t.get(0, "gain"), 1e-6, "clamped high");
        t.set(0, "gain", -5);
        T.near(0.5, t.get(0, "gain"), 1e-6, "clamped low");
        t.set(0, "caps", 0.2f);
        T.ok(!t.on(0, "caps"), "a switch is off");
        t.set(0, "caps", 0.7f);
        T.near(1, t.get(0, "caps"), 1e-6, "or fully on");
    }

    public static void aSliderPositionMapsToAValue() {
        VizTune t = new VizTune();
        t.setAt(0, "gain", 0.5f);
        T.near(1.25, t.get(0, "gain"), 1e-6, "half way between 0.5 and 2");
        T.near(0.5, t.at(0, "gain"), 1e-6, "and back");
    }

    public static void resetOnlyTouchesOneLook() {
        VizTune t = new VizTune();
        t.set(0, "gain", 1.5f);
        t.set(4, "spin", 2);
        t.reset(0);
        T.near(1, t.get(0, "gain"), 1e-6, "blocks back to default");
        T.near(2, t.get(4, "spin"), 1e-6, "swirl kept");
    }

    public static void settingsSurviveSavingAndLoading() {
        VizTune t = new VizTune();
        t.set(4, "trail", 1.2f);
        t.set(0, "caps", 0);
        VizTune u = VizTune.load(t.save());
        T.near(1.2, u.get(4, "trail"), 1e-6, "trail length");
        T.ok(!u.on(0, "caps"), "peak caps off");
        T.near(1, u.get(0, "gain"), 1e-6, "the rest at defaults");
    }

    public static void aDamagedSaveLoadsAsDefaults() {
        VizTune u = VizTune.load("junk;;4.trail=abc;9.gain=3;0.nothing=1;0.gain=77");
        T.near(0.55, u.get(4, "trail"), 1e-6, "bad number ignored");
        T.near(2, u.get(0, "gain"), 1e-6, "out of range clamped");
        T.near(1, VizTune.load(null).get(0, "gain"), 1e-6, "nothing saved yet");
    }
}
