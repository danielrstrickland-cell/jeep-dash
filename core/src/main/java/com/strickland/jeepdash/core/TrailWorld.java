package com.strickland.jeepdash.core;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The night trail behind the Holo truck (the owner's approved mockup): a dirt trail through pines on a dark forest
 * floor. Built here as one small GLB (made in code, no art files) that the 3D view loads like a truck model; then
 * every frame {@link #place} moves its trail pieces and trees: they slide toward the camera with the Jeep's speed
 * and the trail ahead bends the way the front wheels point. The truck stays put, facing away from the camera,
 * down -z; scene units are metres.
 */
public final class TrailWorld {
    public static final int SEGMENTS = 26, TREES = 64;
    /** Trail piece length, the distance the trees repeat over, how far behind the truck pieces start. */
    public static final double SEG_LEN = 5, LOOP = 130, BEHIND = 12;
    static final double TRAIL_WIDTH = 3.4, WHEELBASE_M = 2.7, MAX_CURVE = 1 / 25.0;

    private final double[] treeAt = new double[TREES], treeSide = new double[TREES], treeScale = new double[TREES];
    private double travelled, curve;

    public TrailWorld() {
        // the same forest every time: a fixed little generator, not the clock
        long seed = 0x5EEDL;
        for (int i = 0; i < TREES; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            double r1 = ((seed >>> 11) & 0xFFFFF) / (double) 0xFFFFF;
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            double r2 = ((seed >>> 11) & 0xFFFFF) / (double) 0xFFFFF;
            treeAt[i] = LOOP * i / TREES + r1 * LOOP / TREES;
            double off = TRAIL_WIDTH / 2 + 1.6 + r2 * 9;
            treeSide[i] = i % 2 == 0 ? -off : off;
            treeScale[i] = 0.75 + 0.5 * ((r1 + r2) / 2);
        }
    }

    /**
     * One frame: the Jeep moved speedMps for dt seconds with its front wheels at steerDeg (positive left). The
     * bend eases in so a twitch in the reading does not whip the trail.
     */
    public void step(double dtSec, double speedMps, double steerDeg) {
        if (!(dtSec > 0)) return;
        travelled = (travelled + Math.max(0, speedMps) * dtSec) % (LOOP * SEG_LEN);
        double want = Math.tan(Math.toRadians(steerDeg)) / WHEELBASE_M;
        want = Math.max(-MAX_CURVE, Math.min(MAX_CURVE, want));
        curve += (want - curve) * (1 - Math.exp(-dtSec / 0.6));
    }

    /** Sideways place of the trail's middle d metres ahead (left turn: to the left, -x). */
    public double sideAt(double d) { return -0.5 * curve * d * Math.max(0, d); }

    /** True while the trail is moving or bending. */
    public boolean moving(double speedMps) { return speedMps > 0.05 || Math.abs(curve) > 1e-5; }

    /** Column-major 4x4 transforms for the trail pieces (SEGMENTS) then the trees (TREES), written into out. */
    public void place(float[][] out) {
        double scroll = travelled % SEG_LEN;
        for (int i = 0; i < SEGMENTS; i++) {
            double d = i * SEG_LEN - scroll - BEHIND;
            double x = sideAt(d), heading = Math.atan(curve * Math.max(0, d));
            set(out[i], x, 0.01, -d, heading, 1);
        }
        for (int j = 0; j < TREES; j++) {
            double d = ((treeAt[j] - travelled) % LOOP + LOOP) % LOOP - BEHIND;
            set(out[SEGMENTS + j], sideAt(d) + treeSide[j], 0, -d, j * 0.7, treeScale[j]);
        }
    }

    /** Turn about y by yaw (radians), scale evenly, then move to x, y, z. */
    private static void set(float[] m, double x, double y, double z, double yaw, double s) {
        double c = Math.cos(yaw) * s, n = Math.sin(yaw) * s;
        m[0] = (float) c;  m[1] = 0; m[2] = (float) -n; m[3] = 0;
        m[4] = 0; m[5] = (float) s; m[6] = 0; m[7] = 0;
        m[8] = (float) n;  m[9] = 0; m[10] = (float) c; m[11] = 0;
        m[12] = (float) x; m[13] = (float) y; m[14] = (float) z; m[15] = 1;
    }

    // ---------------- the GLB ----------------

    /** Node names the 3D view looks up. */
    public static String segName(int i) { return "seg_" + i; }

    public static String treeName(int j) { return "tree_" + j; }

    private static final class Mesh {
        final List<float[]> pos = new ArrayList<>(), nrm = new ArrayList<>();

        /** One flat triangle (counter-clockwise seen from outside). */
        void tri(double[] a, double[] b, double[] c) {
            double[] u = {b[0] - a[0], b[1] - a[1], b[2] - a[2]}, v = {c[0] - a[0], c[1] - a[1], c[2] - a[2]};
            double[] n = {u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]};
            double l = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
            if (l < 1e-12) return;
            for (double[] p : new double[][]{a, b, c}) {
                pos.add(new float[]{(float) p[0], (float) p[1], (float) p[2]});
                nrm.add(new float[]{(float) (n[0] / l), (float) (n[1] / l), (float) (n[2] / l)});
            }
        }

        void quadUp(double x0, double x1, double z0, double z1, double y) {
            tri(new double[]{x0, y, z1}, new double[]{x1, y, z1}, new double[]{x1, y, z0});
            tri(new double[]{x0, y, z1}, new double[]{x1, y, z0}, new double[]{x0, y, z0});
        }

        /** A cone or tapered cylinder round the y axis from y0 (radius r0) to y1 (radius r1). */
        void round(int sides, double y0, double r0, double y1, double r1) {
            for (int k = 0; k < sides; k++) {
                double a = 2 * Math.PI * k / sides, b = 2 * Math.PI * (k + 1) / sides;
                double[] p0 = {Math.cos(a) * r0, y0, -Math.sin(a) * r0}, p1 = {Math.cos(b) * r0, y0, -Math.sin(b) * r0};
                double[] q0 = {Math.cos(a) * r1, y1, -Math.sin(a) * r1}, q1 = {Math.cos(b) * r1, y1, -Math.sin(b) * r1};
                tri(p0, p1, q1);
                if (r1 > 1e-9) tri(p0, q1, q0);
            }
        }
    }

    /** The trail's GLB: floor, trail piece and pine meshes, and one node per piece and tree. */
    public static byte[] glb() {
        Mesh floor = new Mesh(), seg = new Mesh(), crown = new Mesh(), trunk = new Mesh();
        floor.quadUp(-45, 45, -LOOP, 25, 0);
        double h = TRAIL_WIDTH / 2;
        seg.quadUp(-h, h, -SEG_LEN - 0.05, 0.05, 0);
        crown.round(9, 1.3, 1.6, 4.0, 0.9);
        crown.round(9, 3.2, 1.2, 6.4, 0);
        trunk.round(6, 0, 0.22, 1.6, 0.18);
        Mesh[] meshes = {floor, seg, crown, trunk};

        ByteArrayOutputStream bin = new ByteArrayOutputStream();
        StringBuilder views = new StringBuilder(), acc = new StringBuilder();
        int view = 0;
        for (Mesh m : meshes) {
            for (int part = 0; part < 2; part++) {
                List<float[]> data = part == 0 ? m.pos : m.nrm;
                ByteBuffer b = ByteBuffer.allocate(data.size() * 12).order(ByteOrder.LITTLE_ENDIAN);
                float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
                for (float[] v : data) {
                    for (int k = 0; k < 3; k++) {
                        b.putFloat(v[k]);
                        lo[k] = Math.min(lo[k], v[k]);
                        hi[k] = Math.max(hi[k], v[k]);
                    }
                }
                int offset = bin.size();
                bin.write(b.array(), 0, b.capacity());
                if (view > 0) { views.append(','); acc.append(','); }
                views.append("{\"buffer\":0,\"byteOffset\":").append(offset).append(",\"byteLength\":").append(b.capacity()).append('}');
                acc.append("{\"bufferView\":").append(view).append(",\"componentType\":5126,\"count\":").append(data.size())
                        .append(",\"type\":\"VEC3\"");
                if (part == 0) acc.append(",\"min\":[").append(lo[0]).append(',').append(lo[1]).append(',').append(lo[2])
                        .append("],\"max\":[").append(hi[0]).append(',').append(hi[1]).append(',').append(hi[2]).append(']');
                acc.append('}');
                view++;
            }
        }
        // dirt, forest floor, pine needles, bark: dark and rough, lit by the headlights and a little moon
        String mats = mat("floor", 0.035, 0.045, 0.035) + ',' + mat("dirt", 0.20, 0.16, 0.11) + ','
                + mat("pine", 0.035, 0.09, 0.05) + ',' + mat("bark", 0.10, 0.07, 0.045);
        String meshJson = "{\"name\":\"floor\",\"primitives\":[" + prim(0, 0) + "]},"
                + "{\"name\":\"trail\",\"primitives\":[" + prim(1, 1) + "]},"
                + "{\"name\":\"pine\",\"primitives\":[" + prim(2, 2) + ',' + prim(3, 3) + "]}";
        StringBuilder nodes = new StringBuilder("{\"name\":\"floor\",\"mesh\":0}");
        StringBuilder roots = new StringBuilder("0");
        int n = 1;
        for (int i = 0; i < SEGMENTS; i++, n++) {
            nodes.append(",{\"name\":\"").append(segName(i)).append("\",\"mesh\":1}");
            roots.append(',').append(n);
        }
        for (int j = 0; j < TREES; j++, n++) {
            nodes.append(",{\"name\":\"").append(treeName(j)).append("\",\"mesh\":2}");
            roots.append(',').append(n);
        }
        while (bin.size() % 4 != 0) bin.write(0);
        String json = "{\"asset\":{\"version\":\"2.0\",\"generator\":\"jeep-dash TrailWorld\"},\"scene\":0,"
                + "\"scenes\":[{\"nodes\":[" + roots + "]}],\"nodes\":[" + nodes + "],\"meshes\":[" + meshJson + "],"
                + "\"materials\":[" + mats + "],\"accessors\":[" + acc + "],\"bufferViews\":[" + views + "],"
                + "\"buffers\":[{\"byteLength\":" + bin.size() + "}]}";
        byte[] js = json.getBytes(StandardCharsets.UTF_8);
        int jsLen = (js.length + 3) & ~3;
        int total = 12 + 8 + jsLen + 8 + bin.size();
        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x46546C67).putInt(2).putInt(total);
        out.putInt(jsLen).putInt(0x4E4F534A).put(js);
        for (int i = js.length; i < jsLen; i++) out.put((byte) ' ');
        out.putInt(bin.size()).putInt(0x004E4942).put(bin.toByteArray());
        return out.array();
    }

    private static String prim(int mesh, int material) {
        return "{\"attributes\":{\"POSITION\":" + (mesh * 2) + ",\"NORMAL\":" + (mesh * 2 + 1) + "},\"material\":" + material + '}';
    }

    private static String mat(String name, double r, double g, double b) {
        return "{\"name\":\"" + name + "\",\"pbrMetallicRoughness\":{\"baseColorFactor\":[" + r + ',' + g + ',' + b
                + ",1],\"metallicFactor\":0,\"roughnessFactor\":0.95}}";
    }
}
