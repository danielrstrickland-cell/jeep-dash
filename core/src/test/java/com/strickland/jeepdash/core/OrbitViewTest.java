package com.strickland.jeepdash.core;

public final class OrbitViewTest {
    public static void dragSwingsAndStopsShortOfTheFloor() {
        OrbitView o = new OrbitView();
        o.drag(100, 0);
        T.near(-45, o.yawDeg(), 1e-9, "a drag right swings the view");
        o.drag(0, -1000);
        T.near(OrbitView.MIN_ELEVATION, HoloFraming.ELEVATION_DEG + o.pitchDeg(), 1e-9, "never under the floor");
        o.drag(0, 5000);
        T.near(OrbitView.MAX_ELEVATION, HoloFraming.ELEVATION_DEG + o.pitchDeg(), 1e-9, "never straight down");
        T.near(OrbitView.MAX_ELEVATION - HoloFraming.ELEVATION_DEG, o.pitchWith(10), 1e-9, "parallax cannot push past it");
    }

    public static void pinchZoomsWithinLimitsAndDoubleTapResets() {
        OrbitView o = new OrbitView();
        o.pinch(1.5);
        T.near(1.5, o.zoom(), 1e-9, "spread fingers move in");
        o.pinch(10);
        T.near(OrbitView.MAX_ZOOM, o.zoom(), 1e-9, "not inside the truck");
        o.pinch(0.01);
        T.near(OrbitView.MIN_ZOOM, o.zoom(), 1e-9, "not lost in the distance");
        o.pinch(Double.NaN);
        T.near(OrbitView.MIN_ZOOM, o.zoom(), 1e-9, "a bad reading changes nothing");
        o.drag(30, 30);
        o.reset();
        T.ok(o.yawDeg() == 0 && o.pitchDeg() == 0 && o.zoom() == 1, "double tap: back to the start");
    }

    public static void zoomBringsTheCameraIn() {
        HoloFraming a = HoloFraming.fit(1.5, 4.6, 1.8, 1.7, 0, 0, 1), b = HoloFraming.fit(1.5, 4.6, 1.8, 1.7, 0, 0, 2);
        T.ok(b.near < a.near, "closer at zoom 2");
    }
}
