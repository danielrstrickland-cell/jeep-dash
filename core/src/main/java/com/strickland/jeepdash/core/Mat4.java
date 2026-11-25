package com.strickland.jeepdash.core;

/** 4x4 matrices as 16 doubles in column order (as OpenGL and Filament keep them), for posing the 3D truck. */
public final class Mat4 {
    private Mat4() { }

    public static double[] identity() {
        double[] m = new double[16];
        m[0] = m[5] = m[10] = m[15] = 1;
        return m;
    }

    public static double[] translate(double x, double y, double z) {
        double[] m = identity();
        m[12] = x;
        m[13] = y;
        m[14] = z;
        return m;
    }

    public static double[] scale(double s) {
        double[] m = identity();
        m[0] = m[5] = m[10] = s;
        return m;
    }

    /** Turn by an angle about an axis through the origin (right-hand rule). */
    public static double[] rotate(double[] axis, double rad) {
        double l = Math.sqrt(dot(axis, axis));
        double x = axis[0] / l, y = axis[1] / l, z = axis[2] / l, c = Math.cos(rad), s = Math.sin(rad), t = 1 - c;
        double[] m = identity();
        m[0] = t * x * x + c;     m[4] = t * x * y - s * z; m[8] = t * x * z + s * y;
        m[1] = t * x * y + s * z; m[5] = t * y * y + c;     m[9] = t * y * z - s * x;
        m[2] = t * x * z - s * y; m[6] = t * y * z + s * x; m[10] = t * z * z + c;
        return m;
    }

    /** Turn about an axis through a point. */
    public static double[] rotateAbout(double[] point, double[] axis, double rad) {
        return mul(translate(point[0], point[1], point[2]), mul(rotate(axis, rad), translate(-point[0], -point[1], -point[2])));
    }

    public static double[] mul(double[] a, double[] b) {
        double[] m = new double[16];
        for (int c = 0; c < 4; c++)
            for (int r = 0; r < 4; r++) {
                double s = 0;
                for (int k = 0; k < 4; k++) s += a[k * 4 + r] * b[c * 4 + k];
                m[c * 4 + r] = s;
            }
        return m;
    }

    /** Inverse of an affine matrix (rotation, scale, translation; bottom row 0 0 0 1). */
    public static double[] invert(double[] m) {
        double a = m[0], b = m[4], c = m[8], d = m[1], e = m[5], f = m[9], g = m[2], h = m[6], i = m[10];
        double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
        double det = a * A + b * B + c * C;
        if (Math.abs(det) < 1e-12) return identity();
        double[] r = identity();
        r[0] = A / det;                 r[4] = -(b * i - c * h) / det; r[8] = (b * f - c * e) / det;
        r[1] = B / det;                 r[5] = (a * i - c * g) / det;  r[9] = -(a * f - c * d) / det;
        r[2] = C / det;                 r[6] = -(a * h - b * g) / det; r[10] = (a * e - b * d) / det;
        double tx = m[12], ty = m[13], tz = m[14];
        r[12] = -(r[0] * tx + r[4] * ty + r[8] * tz);
        r[13] = -(r[1] * tx + r[5] * ty + r[9] * tz);
        r[14] = -(r[2] * tx + r[6] * ty + r[10] * tz);
        return r;
    }

    public static double[] point(double[] m, double[] p) {
        return new double[]{
                m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12],
                m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13],
                m[2] * p[0] + m[6] * p[1] + m[10] * p[2] + m[14]};
    }

    public static double[] dir(double[] m, double[] v) {
        return new double[]{
                m[0] * v[0] + m[4] * v[1] + m[8] * v[2],
                m[1] * v[0] + m[5] * v[1] + m[9] * v[2],
                m[2] * v[0] + m[6] * v[1] + m[10] * v[2]};
    }

    public static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

    public static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    public static double[] normalize(double[] v) {
        double l = Math.sqrt(dot(v, v));
        return l < 1e-12 ? new double[]{0, 0, 0} : new double[]{v[0] / l, v[1] / l, v[2] / l};
    }

    public static float[] toFloat(double[] m) {
        float[] f = new float[m.length];
        for (int i = 0; i < m.length; i++) f[i] = (float) m[i];
        return f;
    }

    public static double[] fromFloat(float[] f) {
        double[] m = new double[f.length];
        for (int i = 0; i < f.length; i++) m[i] = f[i];
        return m;
    }
}
