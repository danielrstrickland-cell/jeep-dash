package com.strickland.jeepdash.core;

/**
 * Where the camera sits for a truck standing on the origin (see TruckPose), so that it fits its box at any turn,
 * and where points of the ground land in that box: the Holo face draws its platform rings from this, so they
 * sit under the 3D truck exactly. Screen positions are shares of the box, 0..1 from the top left.
 */
public final class HoloFraming {
    public static final double ELEVATION_DEG = 12, FOV_DEG = 26, MARGIN = 1.04;

    public final double[] eye, target;
    public final double fovDeg, aspect, near, far;
    private final double[] right, camUp, look;
    private final double tanV, tanH;

    private HoloFraming(double[] eye, double[] target, double aspect, double radius) {
        this.eye = eye;
        this.target = target;
        this.fovDeg = FOV_DEG;
        this.aspect = aspect;
        double d = Math.sqrt(Mat4.dot(TruckRig.sub(target, eye), TruckRig.sub(target, eye)));
        this.near = Math.max(0.05, d - radius * 3);
        this.far = d + radius * 3;
        look = Mat4.normalize(TruckRig.sub(target, eye));
        right = Mat4.normalize(Mat4.cross(look, new double[]{0, 1, 0}));
        camUp = Mat4.cross(right, look);
        tanV = Math.tan(Math.toRadians(FOV_DEG / 2));
        tanH = tanV * aspect;
    }

    /** For a box of this width over height and a truck of this size (scene units, standing on the origin). */
    public static HoloFraming fit(double aspect, double length, double width, double height) {
        double radius = 0.5 * Math.sqrt(length * length + width * width + height * height);
        double half = Math.toRadians(FOV_DEG / 2), halfH = Math.atan(Math.tan(half) * aspect);
        double dist = MARGIN * radius / Math.sin(Math.min(half, halfH));
        double e = Math.toRadians(ELEVATION_DEG);
        double[] target = {0, height / 2, 0};
        double[] eye = {0, target[1] + dist * Math.sin(e), dist * Math.cos(e)};
        return new HoloFraming(eye, target, aspect, radius);
    }

    /** A scene point's place in the box: {x, y} shares, y downward. */
    public double[] project(double x, double y, double z) {
        double[] d = {x - eye[0], y - eye[1], z - eye[2]};
        double cz = Mat4.dot(d, look);
        if (cz < 1e-6) cz = 1e-6;
        return new double[]{0.5 + Mat4.dot(d, right) / (cz * tanH) / 2, 0.5 - Mat4.dot(d, camUp) / (cz * tanV) / 2};
    }

    /** A circle on the ground round the truck: {centre x, centre y, half width, half height} as shares of the box. */
    public double[] ring(double radius) {
        double[] l = project(-radius, 0, 0), r = project(radius, 0, 0), n = project(0, 0, radius), f = project(0, 0, -radius);
        return new double[]{(l[0] + r[0]) / 2, (n[1] + f[1]) / 2, Math.abs(r[0] - l[0]) / 2, Math.abs(n[1] - f[1]) / 2};
    }
}
