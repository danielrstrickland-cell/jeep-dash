package com.strickland.jeepdash.core;

import java.util.ArrayList;
import java.util.List;

/** Finding the hood, liftgate and wheels in a model, posing them, and framing the truck for the camera. */
public final class TruckRigTest {
    private TruckRigTest() { }

    private static TruckRig.Part part(List<TruckRig.Part> l, String name, int parent, double cx, double cy, double cz,
                                      double hx, double hy, double hz) {
        TruckRig.Part p = new TruckRig.Part(name, parent, new double[]{cx, cy, cz},
                new double[]{cx - hx, cy - hy, cz - hz}, new double[]{cx + hx, cy + hy, cz + hz});
        l.add(p);
        return p;
    }

    /** The owner's model as expected: glTF's way round (front +Z, left +X), named parts with their pivots. */
    private static List<TruckRig.Part> ownersModel() {
        List<TruckRig.Part> l = new ArrayList<>();
        part(l, "Body", -1, 0, 1.1, 0, 0.9, 0.7, 2.35);                               // 0
        TruckRig.Part hood = part(l, "Hood", 0, 0, 1.35, 1.25, 0.8, 0.08, 0.6);      // 1, child of the body
        hood.origin[2] = 0.7;                                                        // its hinge at the back edge
        part(l, "Liftgate_Glass", -1, 0, 1.6, -2.3, 0.6, 0.3, 0.05);                 // 2 (inside the gate)
        part(l, "Liftgate", -1, 0, 1.3, -2.3, 0.75, 0.6, 0.1);                       // 3
        part(l, "Wheel_FL", -1, 0.8, 0.39, 1.4, 0.15, 0.39, 0.39);                  // 4
        part(l, "Tire_FL", 4, 0.8, 0.39, 1.4, 0.15, 0.39, 0.39);                    // 5, under the wheel: moves with it
        part(l, "wheelFrontRight", -1, -0.8, 0.39, 1.4, 0.15, 0.39, 0.39);          // 6
        part(l, "LR_wheel", -1, 0.8, 0.39, -1.3, 0.15, 0.39, 0.39);                 // 7
        part(l, "WHEEL-RR.001", -1, -0.8, 0.39, -1.3, 0.15, 0.39, 0.39);            // 8
        part(l, "SteeringWheel", -1, 0.4, 1.2, 0.5, 0.2, 0.2, 0.05);                // 9: not a road wheel
        part(l, "SpareWheel", 3, 0, 1.2, -2.45, 0.39, 0.39, 0.15);                  // 10: rides on the gate
        return l;
    }

    /** The bundled stand-in: front +X, left -Z, wheel parts without corner names, hood decals and a gate handle. */
    private static List<TruckRig.Part> standIn() {
        List<TruckRig.Part> l = new ArrayList<>();
        part(l, "Body", -1, 0.1, 1.1, 0, 2.35, 0.7, 0.9);
        String[] bits = {"Tyre", "Tread", "Wheel", "Hub", "Rotor"};
        double[][] at = {{1.43, -0.8}, {1.43, 0.8}, {-1.26, -0.8}, {-1.26, 0.8}};
        for (int c = 0; c < 4; c++)
            for (String b : bits)
                part(l, c == 0 ? b : b + ".00" + c, -1, at[c][0], 0.394, at[c][1], 0.39, 0.39, 0.15);
        for (int i = 0; i < 6; i++) part(l, i == 0 ? "HoodPeel" : "HoodPeel.00" + i, -1, 1.6, 1.4, -0.3 + i * 0.1, 0.1, 0.01, 0.05);
        part(l, "GateHandle", -1, -2.4, 1.3, 0, 0.02, 0.03, 0.12);
        part(l, "HeadlightLens", -1, 2.4, 1.0, -0.6, 0.05, 0.1, 0.12);
        part(l, "HeadlightLens.001", -1, 2.4, 1.0, 0.6, 0.05, 0.1, 0.12);
        part(l, "Antenna", -1, 1.0, 2.2, 0.85, 0.01, 0.4, 0.01);
        return l;
    }

    private static void vec(double[] exp, double[] act, String msg) {
        for (int i = 0; i < 3; i++) T.near(exp[i], act[i], 1e-6, msg + "[" + i + "]");
    }

    public static void namedPartsAreFoundWhicheverWayTheyAreSpelled() {
        TruckRig r = TruckRig.build(ownersModel());
        vec(new double[]{0, 0, 1}, r.forward, "forward from the wheels");
        vec(new double[]{1, 0, 0}, r.left, "left from the wheels");
        T.eq("[4]", java.util.Arrays.toString(r.wheels[TruckRig.FL].parts), "front left: the wheel, its tyre rides along");
        T.eq("[6]", java.util.Arrays.toString(r.wheels[TruckRig.FR].parts), "front right, camel case");
        T.eq("[7]", java.util.Arrays.toString(r.wheels[TruckRig.RL].parts), "rear left, LR first");
        T.eq("[8]", java.util.Arrays.toString(r.wheels[TruckRig.RR].parts), "rear right, capitals and a suffix");
        T.eq("[1]", java.util.Arrays.toString(r.hood), "the hood");
        T.eq("[2, 3]", java.util.Arrays.toString(r.gate), "the liftgate and its glass (the spare rides on it)");
        T.near(0.7, r.hoodPivot[2], 1e-6, "hood hinge from its own pivot");
        T.near(0.39, r.wheelRadius, 0.01, "wheel radius");
        T.near(0, r.ground[1], 1e-6, "ground under the wheels");
        T.eq("hood, liftgate, 4 wheels", r.summary(), "what was found");
    }

    public static void theStandInsWheelsAreFoundByWhereTheyAre() {
        TruckRig r = TruckRig.build(standIn());
        vec(new double[]{1, 0, 0}, r.forward, "the headlights are at the front");
        vec(new double[]{0, 0, -1}, r.left, "left follows");
        T.eq(5, r.wheels[TruckRig.FL].parts.length, "tyre, tread, wheel, hub and rotor turn together");
        T.near(1.43, r.wheels[TruckRig.FL].centre[0], 1e-6, "front left is at the front");
        T.near(-0.8, r.wheels[TruckRig.FL].centre[2], 1e-6, "and on the left");
        T.eq(0, r.hood.length, "hood decals are not a hood");
        T.eq(0, r.gate.length, "a gate handle is not a liftgate");
        T.eq("4 wheels", r.summary(), "what was found");
        T.near(2.69, r.wheelbase(), 0.01, "wheelbase");
    }

    public static void aModelWithNothingNamedStillStandsTheRightWayRound() {
        List<TruckRig.Part> l = new ArrayList<>();
        part(l, "Mesh", -1, 0, 0.9, 0.3, 0.9, 0.9, 2.3);
        TruckRig r = TruckRig.build(l);
        vec(new double[]{0, 0, 1}, r.forward, "glTF's front");
        T.eq(null, r.wheels[TruckRig.FL], "no wheels");
        T.eq("no moving parts", r.summary(), "nothing found");
        T.near(4.6, r.length, 1e-6, "length along forward");
        T.near(0.3, r.ground[2], 1e-6, "ground under the middle of the box");
    }

    // ---------------- posing ----------------

    public static void placementPutsTheTruckOnTheOriginFacingTheCamera() {
        TruckRig r = TruckRig.build(standIn());
        double[] m = TruckPose.placement(r, 4.0, 0);
        double[] g = Mat4.point(m, r.ground);
        vec(new double[]{0, 0, 0}, g, "ground under the middle at the origin");
        double[] f = Mat4.dir(m, r.forward);
        T.near(4.0 / r.length, f[2], 1e-6, "forward along +Z, scaled");
        T.near(0, f[0], 1e-6, "not sideways");
        double[] turned = Mat4.dir(TruckPose.placement(r, 4.0, Math.toRadians(-90)), r.forward);
        T.ok(turned[0] < -0.5, "a quarter turn the other way faces -X");
    }

    public static void wheelsStayLevelWhileTheBodyRolls() {
        TruckRig r = TruckRig.build(standIn());
        TruckRig.Corner c = r.wheels[TruckRig.FR];
        double[] w0 = Mat4.translate(c.centre[0], c.centre[1], c.centre[2]);
        double[] body = TruckPose.body(r, 6, -3);
        double[] local = TruckPose.wheelLocal(c, w0, Mat4.identity(), body, 0, 0, r);
        double[] world = Mat4.mul(body, local);                 // what the scene makes of it under the rolled root
        double[] p = Mat4.point(world, new double[]{0, 0, 0});
        vec(c.centre, p, "the wheel did not move with the body");
        double[] top = Mat4.point(body, new double[]{r.ground[0], 2, r.ground[2]});
        T.ok(Mat4.dot(top, r.left) < Mat4.dot(new double[]{r.ground[0], 2, r.ground[2]}, r.left), "positive roll leans the roof right");
    }

    public static void spinRollsTheTopOfTheWheelForwardAndSteerTurnsItLeft() {
        TruckRig r = TruckRig.build(ownersModel());
        TruckRig.Corner c = r.wheels[TruckRig.FL];
        double[] w0 = Mat4.translate(c.centre[0], c.centre[1], c.centre[2]);
        double[] spun = TruckPose.wheelLocal(c, w0, Mat4.identity(), Mat4.identity(), 0, 0.1, r);
        double[] topBefore = {0, r.wheelRadius, 0};
        double[] topAfter = Mat4.point(spun, topBefore);
        T.ok(topAfter[2] - c.centre[2] > 0.01, "top of the wheel moves forward (+Z here)");
        double[] steered = TruckPose.wheelLocal(c, w0, Mat4.identity(), Mat4.identity(), Math.toRadians(20), 0, r);
        double[] front = Mat4.point(steered, new double[]{0, 0, r.wheelRadius});
        T.ok(front[0] - c.centre[0] > 0.05, "front of the tyre points left (+X here)");
    }

    public static void theHoodOpensUpAndTheLiftgateSwingsUpAndBack() {
        TruckRig r = TruckRig.build(ownersModel());
        double[] hood0 = Mat4.identity();
        double[] open = TruckPose.hinge(hood0, Mat4.identity(), r.hoodPivot, r.left, -Math.toRadians(50));
        double[] frontEdge = Mat4.point(open, new double[]{0, 1.35, 1.85});
        T.ok(frontEdge[1] > 1.8, "front edge rises: " + frontEdge[1]);
        T.near(TruckPose.HOOD_OPEN_DEG, TruckPose.HOOD_OPEN_DEG, 0, "named constant");
        double[] gate = TruckPose.hinge(Mat4.identity(), Mat4.identity(), r.gatePivot, r.left, Math.toRadians(70));
        double[] bottom = Mat4.point(gate, new double[]{0, 0.75, -2.35});
        T.ok(bottom[1] > 1.2 && bottom[2] < -2.6, "gate bottom swings up and back: " + bottom[1] + "," + bottom[2]);
    }

    public static void inverseUndoesAnAffineMatrix() {
        double[] m = Mat4.mul(Mat4.translate(1, 2, 3), Mat4.mul(Mat4.rotate(new double[]{0.3, 1, 0.2}, 0.7), Mat4.scale(1.7)));
        double[] p = Mat4.point(Mat4.mul(Mat4.invert(m), m), new double[]{4, -5, 6});
        vec(new double[]{4, -5, 6}, p, "inverse times matrix is identity");
    }

    // ---------------- framing ----------------

    public static void theTruckFitsTheViewAtAnyTurn() {
        for (double aspect : new double[]{1.0, 1.6, 0.7}) {
            HoloFraming f = HoloFraming.fit(aspect, 4.0, 1.6, 1.8);
            for (int k = 0; k < 12; k++) {
                double a = Math.toRadians(k * 30);
                for (int i = 0; i < 8; i++) {
                    double x = ((i & 1) == 0 ? -0.8 : 0.8), y = ((i & 2) == 0 ? 0 : 1.8), z = ((i & 4) == 0 ? -2 : 2);
                    double rx = x * Math.cos(a) + z * Math.sin(a), rz = -x * Math.sin(a) + z * Math.cos(a);
                    double[] s = f.project(rx, y, rz);
                    T.ok(s[0] > 0 && s[0] < 1 && s[1] > 0 && s[1] < 1, "corner on screen at aspect " + aspect + ": " + s[0] + "," + s[1]);
                }
            }
        }
    }

    public static void platformRingsSitUnderTheTruckInPerspective() {
        HoloFraming f = HoloFraming.fit(1.0, 4.0, 1.6, 1.8);
        double[] g = f.project(0, 0, 0);
        T.ok(g[1] > 0.55, "ground point below the middle: " + g[1]);
        T.near(0.5, g[0], 1e-6, "and centred");
        double[] ring = f.ring(3.0);
        T.near(g[0], ring[0], 1e-6, "ring centred on the truck");
        double down = Math.atan2(f.eye[1], Math.hypot(f.eye[0], f.eye[2]));       // how steeply the camera sees the ground
        T.near(Math.sin(down), ring[3] / ring[2], 0.03, "squashed by the camera's height");
        T.ok(down > Math.toRadians(HoloFraming.ELEVATION_DEG), "looking down a little more than the camera's own tilt");
    }
}
