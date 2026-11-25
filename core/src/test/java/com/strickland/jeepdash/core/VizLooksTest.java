package com.strickland.jeepdash.core;

/** Which visualizer looks can be chosen, and what an old saved choice becomes. */
public class VizLooksTest {

    public static void theBoomMetersAreNoLongerAChoice() {
        T.eq("[0, 1, 3, 4, 6, 7, 9]", java.util.Arrays.toString(VizTune.CHOICES), "no ferrofluid or lava lamp");
    }

    public static void aSavedBoomMeterOrNonsenseBecomesSmooth() {
        T.eq(VizTune.SMOOTH, VizTune.savedLook(2), "the boom meters");
        T.eq(VizTune.SMOOTH, VizTune.savedLook(12), "out of range");
        T.eq(VizTune.SMOOTH, VizTune.savedLook(-1), "below range");
        T.eq(4, VizTune.savedLook(4), "swirl stays swirl");
        T.eq(0, VizTune.savedLook(0), "blocks stay blocks");
    }

    public static void theLookButtonStepsThroughTheChoices() {
        T.eq(1, VizTune.nextLook(0), "blocks to smooth");
        T.eq(3, VizTune.nextLook(1), "smooth skips the old meters to the ring");
        T.eq(4, VizTune.nextLook(3), "ring to swirl");
        T.eq(6, VizTune.nextLook(4), "swirl to milk drop");
        T.eq(VizTune.SMOOTH, VizTune.savedLook(5), "a saved ferrofluid becomes smooth");
        
        T.eq(9, VizTune.nextLook(7), "road to Mandelbrot");
        T.eq(0, VizTune.nextLook(9), "Mandelbrot round to blocks");
        T.eq(3, VizTune.nextLook(2), "from the old meters, on to the ring");
    }
}
