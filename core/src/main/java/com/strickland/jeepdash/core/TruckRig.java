package com.strickland.jeepdash.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Finds the moving parts of a truck model and which way it faces. Parts are matched by name, any case:
 * "hood" (or "bonnet"); "liftgate" or "gate" (or "tailgate", "hatch"); "wheel" (or "tire", "tyre") with a corner
 * as FL/FR/RL/RR (either order) or front/rear plus left/right. A hood or gate must be at least 40% as wide as the
 * truck (so hood decals and a gate handle are left alone); smaller parts named the same that sit on it move with
 * it. Without four named wheels, wheel-like parts low down at the four corners are grouped by where they are.
 * Which way is front comes from the wheels' names, else from parts like headlights and the grille, else
 * glTF's own convention. Everything is in the model's own space (glTF: Y up).
 */
public final class TruckRig {
    public static final int FL = 0, FR = 1, RL = 2, RR = 3;
    static final double WIDE_ENOUGH = 0.4;

    /** One node of the model: its name, its parent (index, or -1), where its pivot is and what it spans. */
    public static final class Part {
        public final String name;
        public final int parent;
        public final double[] origin;
        public final double[] min, max;     // null when the node has no geometry under it

        public Part(String name, int parent, double[] origin, double[] min, double[] max) {
            this.name = name == null ? "" : name;
            this.parent = parent;
            this.origin = origin;
            this.min = min;
            this.max = max;
        }
    }

    /** The parts that turn together at one corner, and the centre they turn about. */
    public static final class Corner {
        public final int[] parts;
        public final double[] centre;

        Corner(int[] parts, double[] centre) { this.parts = parts; this.centre = centre; }
    }

    public final double[] up = {0, 1, 0}, forward, left;
    /** On the ground under the middle of the truck. */
    public final double[] ground;
    public final double length, width, height, wheelRadius;
    public final Corner[] wheels;
    public final int[] hood, gate;
    public final double[] hoodPivot, gatePivot;

    private TruckRig(double[] forward, double[] left, double[] ground, double length, double width, double height,
                     double wheelRadius, Corner[] wheels, int[] hood, int[] gate, double[] hoodPivot, double[] gatePivot) {
        this.forward = forward; this.left = left; this.ground = ground;
        this.length = length; this.width = width; this.height = height; this.wheelRadius = wheelRadius;
        this.wheels = wheels; this.hood = hood; this.gate = gate; this.hoodPivot = hoodPivot; this.gatePivot = gatePivot;
    }

    public boolean hasWheels() { return wheels[FL] != null; }

    /** Distance between the axles along forward; 0 without wheels. */
    public double wheelbase() {
        if (!hasWheels()) return 0;
        double[] f = mid(wheels[FL].centre, wheels[FR].centre), r = mid(wheels[RL].centre, wheels[RR].centre);
        return Math.abs(Mat4.dot(sub(f, r), forward));
    }

    /** "hood, liftgate, 4 wheels", for telling the owner what a loaded model can do. */
    public String summary() {
        List<String> s = new ArrayList<>();
        if (hood.length > 0) s.add("hood");
        if (gate.length > 0) s.add("liftgate");
        if (hasWheels()) s.add("4 wheels");
        return s.isEmpty() ? "no moving parts" : String.join(", ", s);
    }

    // ---------------- building ----------------

    public static TruckRig build(List<Part> parts) {
        double[] bmin = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE}, bmax = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (Part p : parts) {
            if (p.min == null) continue;
            for (int i = 0; i < 3; i++) {
                bmin[i] = Math.min(bmin[i], p.min[i]);
                bmax[i] = Math.max(bmax[i], p.max[i]);
            }
        }
        if (bmin[0] > bmax[0]) {
            bmin = new double[]{-1, 0, -2};
            bmax = new double[]{1, 2, 2};
        }
        double[] bc = mid(bmin, bmax);

        // wheels by name, then by where they are
        List<List<Integer>> named = new ArrayList<>();
        for (int c = 0; c < 4; c++) named.add(new ArrayList<>());
        List<Integer> wheelish = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            Part p = parts.get(i);
            if (p.min == null || !wheelPart(p.name)) continue;
            wheelish.add(i);
            int c = corner(p.name);
            if (c >= 0) named.get(c).add(i);
        }
        double[] forward = null, left = null;
        List<List<Integer>> groups = null;
        boolean allNamed = true;
        for (List<Integer> g : named) allNamed &= !g.isEmpty();
        if (allNamed) {
            groups = named;
            double[] f = sub(mid(centre(parts, named.get(FL)), centre(parts, named.get(FR))),
                    mid(centre(parts, named.get(RL)), centre(parts, named.get(RR))));
            forward = level(f);
            left = Mat4.cross(new double[]{0, 1, 0}, forward);
        } else {
            groups = byPosition(parts, wheelish, bmin, bmax, bc);
            if (groups != null) {
                // the long way between the groups is the wheelbase; which end is front comes from the names
                double[] a = sub(mid(centre(parts, groups.get(0)), centre(parts, groups.get(1))),
                        mid(centre(parts, groups.get(2)), centre(parts, groups.get(3))));
                double[] b = sub(mid(centre(parts, groups.get(0)), centre(parts, groups.get(2))),
                        mid(centre(parts, groups.get(1)), centre(parts, groups.get(3))));
                double[] along = Math.abs(a[0]) + Math.abs(a[2]) > Math.abs(b[0]) + Math.abs(b[2]) ? a : b;
                forward = level(along);
                if (frontScore(parts, forward) < 0) forward = scale(forward, -1);
                left = Mat4.cross(new double[]{0, 1, 0}, forward);
                groups = assign(parts, groups, forward, left);
            }
        }
        if (forward == null) {
            boolean alongZ = bmax[2] - bmin[2] >= bmax[0] - bmin[0];
            forward = alongZ ? new double[]{0, 0, 1} : new double[]{1, 0, 0};
            if (frontScore(parts, forward) < 0) forward = scale(forward, -1);
            left = Mat4.cross(new double[]{0, 1, 0}, forward);
        }

        double length = extent(bmin, bmax, forward), width = extent(bmin, bmax, left), height = bmax[1] - bmin[1];
        Corner[] wheels = new Corner[4];
        double radius = 0;
        double[] ground;
        if (groups != null) {
            double[] sum = new double[3];
            for (int c = 0; c < 4; c++) {
                int[] top = topmost(parts, groups.get(c));
                double[] centre = centre(parts, groups.get(c));
                wheels[c] = new Corner(top, centre);
                radius += halfHeight(parts, groups.get(c)) / 4;
                for (int i = 0; i < 3; i++) sum[i] += centre[i] / 4;
            }
            ground = new double[]{sum[0], bmin[1], sum[2]};
        } else {
            radius = 0.084 * length;
            ground = new double[]{bc[0], bmin[1], bc[2]};
        }

        int[] hood = panel(parts, new String[]{"hood", "bonnet"}, forward, left, width, ground, true);
        int[] gate = panel(parts, new String[]{"liftgate", "tailgate", "gate", "hatch"}, forward, left, width, ground, false);
        double[] hoodPivot = hood.length == 0 ? null : hinge(parts, hood, forward, true);
        double[] gatePivot = gate.length == 0 ? null : hinge(parts, gate, forward, false);
        return new TruckRig(forward, left, ground, length, width, height, radius, wheels, hood, gate, hoodPivot, gatePivot);
    }

    // ---------------- names ----------------

    private static final List<String> NOT_ROAD_WHEELS = Arrays.asList("steering", "spare", "arch", "well", "liner",
            "flare", "housing", "opening");

    /** Lower-case words of a name: "HoodPeel.001" -> hood, peel; "Wheel_FL" -> wheel, fl. */
    static List<String> words(String name) {
        String n = name.replaceAll("\\.\\d+$", "")
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2");
        List<String> out = new ArrayList<>();
        for (String w : n.split("[^A-Za-z0-9]+")) if (!w.isEmpty()) out.add(w.toLowerCase(Locale.US));
        return out;
    }

    static boolean wheelish(String name) {
        String n = name.toLowerCase(Locale.US);
        if (!(n.contains("wheel") || n.contains("tire") || n.contains("tyre"))) return false;
        for (String w : words(name)) for (String bad : NOT_ROAD_WHEELS) if (w.contains(bad)) return false;
        return true;
    }

    /** A wheel, or a part that turns with one (hub, rim, tread, brake rotor). */
    static boolean wheelPart(String name) {
        if (wheelish(name)) return true;
        String n = name.toLowerCase(Locale.US);
        if (n.contains("steering") || n.contains("spare")) return false;
        for (String w : words(name))
            if (w.equals("hub") || w.equals("rim") || w.startsWith("tread") || w.equals("rotor")) return true;
        return false;
    }

    /** FL, FR, RL or RR from a wheel's name; -1 when it does not say. */
    static int corner(String name) {
        List<String> ws = words(name);
        String rest = String.join(" ", ws);
        for (String part : new String[]{"wheel", "tire", "tyre", "hub", "rim", "tread", "rotor"}) rest = rest.replace(part, " ");
        for (String w : rest.trim().split("\\s+")) {
            switch (w) {
                case "fl": case "lf": return FL;
                case "fr": case "rf": return FR;
                case "rl": case "lr": return RL;
                case "rr": return RR;
                default: break;
            }
        }
        String joined = rest.replace(" ", "");
        boolean front = joined.contains("front"), rear = joined.contains("rear") || joined.contains("back");
        boolean l = joined.contains("left"), r = joined.contains("right");
        if (front == rear || l == r) return -1;
        return front ? (l ? FL : FR) : (l ? RL : RR);
    }

    private static final String[] FRONT_WORDS = {"headlight", "headlamp", "grille", "grill", "hood", "bonnet",
            "windshield", "windscreen", "radiator", "front"};
    private static final String[] REAR_WORDS = {"tail", "gate", "trunk", "exhaust", "hitch", "receiver", "rear", "spare"};

    /** Positive when parts named like the front of a truck sit further along this direction than rear ones. */
    private static double frontScore(List<Part> parts, double[] dir) {
        double f = 0, r = 0;
        int nf = 0, nr = 0;
        for (Part p : parts) {
            if (p.min == null) continue;
            String n = p.name.toLowerCase(Locale.US);
            double at = Mat4.dot(mid(p.min, p.max), dir);
            for (String w : FRONT_WORDS) if (n.contains(w)) { f += at; nf++; break; }
            for (String w : REAR_WORDS) if (n.contains(w)) { r += at; nr++; break; }
        }
        if (nf > 0 && nr > 0) return f / nf - r / nr;
        if (nf > 0) return 1;
        if (nr > 0) return -1;
        return 1;
    }

    // ---------------- wheels by position ----------------

    /** Wheel-like parts low down, one group per quarter of the footprint; null unless all four have some. */
    private static List<List<Integer>> byPosition(List<Part> parts, List<Integer> candidates, double[] bmin, double[] bmax, double[] bc) {
        List<Integer> pool = candidates;
        List<List<Integer>> q = new ArrayList<>();
        for (int c = 0; c < 4; c++) q.add(new ArrayList<>());
        double low = bmin[1] + 0.45 * (bmax[1] - bmin[1]);
        for (int i : pool) {
            Part p = parts.get(i);
            double[] m = mid(p.min, p.max);
            if (m[1] > low) continue;
            int c = (m[0] >= bc[0] ? 0 : 2) + (m[2] >= bc[2] ? 0 : 1);
            q.get(c).add(i);
        }
        for (List<Integer> g : q) if (g.isEmpty()) return null;
        return q;
    }

    /** Puts four position groups in FL, FR, RL, RR order for this forward and left. */
    private static List<List<Integer>> assign(List<Part> parts, List<List<Integer>> groups, double[] forward, double[] left) {
        List<List<Integer>> out = new ArrayList<>();
        for (int c = 0; c < 4; c++) out.add(null);
        double[] all = new double[3];
        for (List<Integer> g : groups) {
            double[] c = centre(parts, g);
            for (int i = 0; i < 3; i++) all[i] += c[i] / 4;
        }
        for (List<Integer> g : groups) {
            double[] d = sub(centre(parts, g), all);
            boolean front = Mat4.dot(d, forward) >= 0, onLeft = Mat4.dot(d, left) >= 0;
            int c = front ? (onLeft ? FL : FR) : (onLeft ? RL : RR);
            if (out.get(c) != null) return null;
            out.set(c, g);
        }
        return out;
    }

    // ---------------- hood and liftgate ----------------

    private static int[] panel(List<Part> parts, String[] names, double[] forward, double[] left, double truckWidth,
                               double[] ground, boolean front) {
        List<Integer> cand = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            Part p = parts.get(i);
            if (p.min == null) continue;
            String n = p.name.toLowerCase(Locale.US);
            boolean hit = false;
            for (String w : names) hit |= n.contains(w);
            double along = Mat4.dot(sub(mid(p.min, p.max), ground), forward);
            if (hit && (front ? along > 0 : along < 0)) cand.add(i);
        }
        int main = -1;
        double best = 0;
        for (int i : cand) {
            Part p = parts.get(i);
            if (extent(p.min, p.max, left) < WIDE_ENOUGH * truckWidth) continue;
            double d = Math.sqrt(Mat4.dot(sub(p.max, p.min), sub(p.max, p.min)));
            if (d > best) { best = d; main = i; }
        }
        if (main < 0) return new int[0];
        Part m = parts.get(main);
        double[] pad = scale(sub(m.max, m.min), 0.1);
        List<Integer> group = new ArrayList<>();
        for (int i : cand) {
            Part p = parts.get(i);
            boolean inside = true;
            for (int k = 0; k < 3; k++) inside &= p.min[k] >= m.min[k] - pad[k] - 1e-6 && p.max[k] <= m.max[k] + pad[k] + 1e-6;
            if (i == main || inside) group.add(i);
        }
        return topmost(parts, group);
    }

    /** The hinge: the part's own pivot when it sits near the right edge, else that edge worked out from its size. */
    private static double[] hinge(List<Part> parts, int[] group, double[] forward, boolean hood) {
        Part m = null;
        double best = -1;
        for (int i : group) {
            Part p = parts.get(i);
            double d = Mat4.dot(sub(p.max, p.min), sub(p.max, p.min));
            if (d > best) { best = d; m = p; }
        }
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            double[] c = {(i & 1) == 0 ? m.min[0] : m.max[0], (i & 2) == 0 ? m.min[1] : m.max[1], (i & 4) == 0 ? m.min[2] : m.max[2]};
            double a = Mat4.dot(c, forward);
            lo = Math.min(lo, a);
            hi = Math.max(hi, a);
        }
        double len = hi - lo, own = Mat4.dot(m.origin, forward);
        // a hood hinges at its back edge (by the windscreen); a liftgate at its top, which is its front-most edge
        double edge = hood ? lo : hi;
        boolean ownIsHinge = hood ? Math.abs(own - edge) <= 0.15 * len
                : Math.abs(own - edge) <= 0.25 * len && m.origin[1] >= m.max[1] - 0.15 * (m.max[1] - m.min[1]);
        if (ownIsHinge) return m.origin.clone();
        double[] c = mid(m.min, m.max);
        double[] p = add(c, scale(forward, edge - Mat4.dot(c, forward)));
        p[1] = hood ? (m.min[1] + m.max[1]) / 2 : m.max[1];
        return p;
    }

    // ---------------- little helpers ----------------

    /** Drops parts whose parent (or grandparent...) is in the same list: they move with it already. */
    private static int[] topmost(List<Part> parts, List<Integer> group) {
        List<Integer> out = new ArrayList<>();
        for (int i : group) {
            boolean under = false;
            for (int p = parts.get(i).parent; p >= 0 && p < parts.size() && !under; p = parts.get(p).parent)
                under = group.contains(p);
            if (!under) out.add(i);
        }
        int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        Arrays.sort(a);
        return a;
    }

    private static double[] centre(List<Part> parts, List<Integer> group) {
        double[] lo = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE}, hi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int i : group) {
            Part p = parts.get(i);
            for (int k = 0; k < 3; k++) {
                lo[k] = Math.min(lo[k], p.min[k]);
                hi[k] = Math.max(hi[k], p.max[k]);
            }
        }
        return mid(lo, hi);
    }

    private static double halfHeight(List<Part> parts, List<Integer> group) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int i : group) {
            lo = Math.min(lo, parts.get(i).min[1]);
            hi = Math.max(hi, parts.get(i).max[1]);
        }
        return (hi - lo) / 2;
    }

    /** How far a box reaches along a unit direction. */
    private static double extent(double[] min, double[] max, double[] dir) {
        return Math.abs(dir[0]) * (max[0] - min[0]) + Math.abs(dir[1]) * (max[1] - min[1]) + Math.abs(dir[2]) * (max[2] - min[2]);
    }

    private static double[] level(double[] v) { return Mat4.normalize(new double[]{v[0], 0, v[2]}); }

    static double[] mid(double[] a, double[] b) { return new double[]{(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2}; }

    static double[] sub(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }

    static double[] add(double[] a, double[] b) { return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]}; }

    static double[] scale(double[] a, double s) { return new double[]{a[0] * s, a[1] * s, a[2] * s}; }
}
