package com.strickland.jeepdash.core;

/**
 * The matrices that pose a truck model (see TruckRig) in a scene. The scene's frame: the ground under the middle
 * of the truck at the origin, Y up, the truck facing +Z (its left along +X) before it is turned by a yaw.
 * All parts' "world0" matrices are where they sit in the model's own space as loaded.
 */
public final class TruckPose {
    private TruckPose() { }

    /** How far the hood and the liftgate open, degrees. */
    public static final double HOOD_OPEN_DEG = 55, GATE_OPEN_DEG = 75;
    /** Height of the roll centre over the ground, as a share of the truck's length (about 0.5 m on a ZJ). */
    static final double ROLL_CENTRE = 0.11;

    /** Model space to the scene: ground point to the origin, forward to +Z, scaled to the given length, then turned. */
    public static double[] placement(TruckRig r, double sceneLength, double yawRad) {
        double[] a = Mat4.identity();
        // rows: left, up, forward
        a[0] = r.left[0];    a[4] = r.left[1];    a[8] = r.left[2];
        a[1] = r.up[0];      a[5] = r.up[1];      a[9] = r.up[2];
        a[2] = r.forward[0]; a[6] = r.forward[1]; a[10] = r.forward[2];
        double s = r.length > 1e-9 ? sceneLength / r.length : 1;
        double[] m = Mat4.mul(Mat4.scale(s), Mat4.mul(a, Mat4.translate(-r.ground[0], -r.ground[1], -r.ground[2])));
        return Mat4.mul(Mat4.rotate(new double[]{0, 1, 0}, yawRad), m);
    }

    /** Body roll (positive leans right) and pitch (positive nose up) in model space, about the roll centre. */
    public static double[] body(TruckRig r, double rollDeg, double pitchDeg) {
        double h = ROLL_CENTRE * r.length;
        double[] c = {r.ground[0] + r.up[0] * h, r.ground[1] + r.up[1] * h, r.ground[2] + r.up[2] * h};
        double[] turn = Mat4.mul(Mat4.rotate(r.forward, Math.toRadians(rollDeg)), Mat4.rotate(r.left, -Math.toRadians(pitchDeg)));
        return Mat4.mul(Mat4.translate(c[0], c[1], c[2]), Mat4.mul(turn, Mat4.translate(-c[0], -c[1], -c[2])));
    }

    /** What the model's root gets: placed in the scene, body rolled and pitched. */
    public static double[] root(TruckRig r, double sceneLength, double yawRad, double rollDeg, double pitchDeg) {
        return Mat4.mul(placement(r, sceneLength, yawRad), body(r, rollDeg, pitchDeg));
    }

    /**
     * A wheel part's local matrix: it stays where it is while the body (rolled under the root) leans, steers
     * about the up axis (positive left) and spins about the axle (positive rolling forward), both through the
     * corner's centre.
     */
    public static double[] wheelLocal(TruckRig.Corner c, double[] partWorld0, double[] parentWorld0, double[] body,
                                      double steerRad, double spinRad, TruckRig r) {
        double[] turn = Mat4.mul(Mat4.rotateAbout(c.centre, r.up, steerRad), Mat4.rotateAbout(c.centre, r.left, spinRad));
        return Mat4.mul(Mat4.invert(Mat4.mul(body, parentWorld0)), Mat4.mul(turn, partWorld0));
    }

    /** A hinged part's local matrix, turned about an axis through its hinge. */
    public static double[] hinge(double[] partWorld0, double[] parentWorld0, double[] pivot, double[] axis, double rad) {
        return Mat4.mul(Mat4.invert(parentWorld0), Mat4.mul(Mat4.rotateAbout(pivot, axis, rad), partWorld0));
    }
}
