package com.strickland.jeepdash.core;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Holo look (a holographic dash with the 3D truck in the middle): which looks each car offers, the gauge
 * styles this look adds, the panels that show two readings, and the geometry of the wrap-around arcs, which the
 * look draws and the default layout lines up with the truck.
 */
public final class HoloFace {
    private HoloFace() { }

    /** Panels in this look are slim; other looks keep Layout.MIN_SIZE. */
    public static final float MIN_SIZE = 40;
    /** The arc's radius as a share of its box's height, and how far its centre sits outside the box (radii). */
    static final float ARC_RADIUS = 0.471f, ARC_OUTSIDE = 0.538f;

    public static boolean isHolo(String look) { return "holo".equals(look); }

    public static float minSize(String look) { return isHolo(look) ? MIN_SIZE : Layout.MIN_SIZE; }

    /** The looks a car offers, in the order the switch button steps through them. Holo (and its 3D truck) is the Jeep's. */
    public static String[] looks(boolean porsche) {
        return porsche ? new String[]{"p_retro", "p_gt"} : new String[]{"xp", "jeep", "retro", "holo"};
    }

    // ---------------- styles ----------------

    private static final GaugeType[] PLAIN = {GaugeType.DIAL, GaugeType.DIGITAL, GaugeType.BAR};
    private static final String[] PLAIN_NAMES = {"Dial", "Digital", "Bar"};
    private static final GaugeType[] HOLO = {GaugeType.DIGITAL, GaugeType.BAR, GaugeType.DIAL, GaugeType.HEX,
            GaugeType.ARC_LEFT, GaugeType.ARC_RIGHT};
    private static final String[] HOLO_NAMES = {"Panel", "Big readout", "Ring", "Hexagon", "Arc, left of the truck",
            "Arc, right of the truck"};

    /** The styles a reading's gauge can take in this look. The truck view is not one of them. */
    public static GaugeType[] styles(String look) { return (isHolo(look) ? HOLO : PLAIN).clone(); }

    public static String[] styleNames(String look) { return (isHolo(look) ? HOLO_NAMES : PLAIN_NAMES).clone(); }

    // ---------------- panels with two readings ----------------

    private static final Map<String, String> PARTNER = new HashMap<>();
    private static final Map<String, String> TITLE = new HashMap<>();

    static {
        pair("ltft", "stft", "FUEL TRIM  L / S", "FUEL TRIM  S / L");
        pair("spark", "timing_pulled", "TIMING  /  PULLED", null);
        pair("roll", "pitch", "BODY ROLL", "BODY PITCH");
        TITLE.put("map_kpa", "MANIFOLD");
        TITLE.put("mpg_avg", "MPG THIS DRIVE");
        TITLE.put("fuel_used", "FUEL USED");
        TITLE.put("normal", "NORMAL READINGS");
        TITLE.put("speedo_err", "SPEEDO VS GPS");
        TITLE.put("rpm", "RPM  x1000");
    }

    /** a shows b beside it; b, if it has a title, shows a. */
    private static void pair(String a, String b, String titleA, String titleB) {
        PARTNER.put(a, b);
        TITLE.put(a, titleA);
        if (titleB != null) {
            PARTNER.put(b, a);
            TITLE.put(b, titleB);
        }
    }

    /** The second reading a panel shows beside this one ("-12 / +1"); null for most. */
    public static String partner(String paramId) { return PARTNER.get(paramId); }

    /** The ids plus their partners, so a two-reading panel gets both read. The given set is not changed. */
    public static Set<String> withCompanions(Set<String> ids) {
        Set<String> out = new LinkedHashSet<>(ids);
        for (String id : ids) {
            String p = PARTNER.get(id);
            if (p != null) out.add(p);
        }
        return out;
    }

    /** A panel's title, as in the mockup where it has one. */
    public static String title(String paramId, String name) {
        String t = TITLE.get(paramId);
        return t != null ? t : name.toUpperCase(Locale.US);
    }

    /** Body roll for people: "L 2.1°" leaning left, "R 0.4°" leaning right. */
    public static String lean(double deg) {
        if (Math.abs(deg) < 0.05) return "0.0°";
        return (deg < 0 ? "L " : "R ") + String.format(Locale.US, "%.1f°", Math.abs(deg));
    }

    // ---------------- depth ----------------

    /** dp in front of the screen per step of depth; the viewer's distance from the screen (dp). */
    public static final float DEPTH_STEP = 90, VIEW_DIST = 900;
    /** Degrees the side panels turn in toward the viewer, and the top and bottom rows tip toward them. */
    public static final float TILT_Y = 16, TILT_X = 5;
    public static final String[] DEPTH_NAMES = {"Far behind the truck", "Behind the truck", "Level with the truck",
            "In front of the truck", "Close up"};

    /** Panels turn toward the viewer; the arcs, the hexagon and the truck stay square on (they ring the truck). */
    public static boolean tilts(GaugeType t) { return t == GaugeType.DIGITAL || t == GaugeType.BAR || t == GaugeType.DIAL; }

    /**
     * Where a gauge's corners land on screen (top left, top right, bottom right, bottom left; dp) when it floats
     * at its depth, turned in toward the viewer (tilt 0..1) and seen from a viewer moved by the parallax angles.
     * With no tilt and no parallax it lands exactly on its box, whatever its depth.
     */
    public static float[] project(GaugeSpec g, float screenW, float screenH, double tilt, double parallaxYawDeg, double parallaxPitchDeg) {
        double d = VIEW_DIST, z = g.depth * DEPTH_STEP, k = (d - z) / d;
        double cx = g.x + g.w / 2.0 - screenW / 2.0, cy = screenH / 2.0 - (g.y + g.h / 2.0);
        double t = tilts(g.type) ? tilt : 0;
        double side = Math.max(-1, Math.min(1, cx / (screenW / 2.0))), vert = Math.max(-1, Math.min(1, cy / (screenH / 2.0)));
        double ay = Math.toRadians(-TILT_Y * t * side), ax = Math.toRadians(TILT_X * t * vert);
        double ex = d * Math.tan(Math.toRadians(parallaxYawDeg)), ey = d * Math.tan(Math.toRadians(parallaxPitchDeg));
        double[][] corners = {{-g.w / 2.0, g.h / 2.0}, {g.w / 2.0, g.h / 2.0}, {g.w / 2.0, -g.h / 2.0}, {-g.w / 2.0, -g.h / 2.0}};
        float[] out = new float[8];
        for (int i = 0; i < 4; i++) {
            double lx = corners[i][0], ly = corners[i][1];
            double x1 = lx * Math.cos(ay), z1 = -lx * Math.sin(ay);
            double y2 = ly * Math.cos(ax), z2 = z1 + ly * Math.sin(ax);
            double px = (cx + x1) * k, py = (cy + y2) * k, pz = z + z2 * k;
            double s = d / Math.max(1, d - pz);
            out[i * 2] = (float) (ex + s * (px - ex) + screenW / 2.0);
            out[i * 2 + 1] = (float) (screenH / 2.0 - (ey + s * (py - ey)));
        }
        return out;
    }

    // ---------------- the arcs ----------------

    /** The radius of an arc gauge's ring (dp), from its box. */
    public static float arcRadius(GaugeSpec g) { return Math.min(ARC_RADIUS * g.h, 1.1f * g.w); }

    /** The centre of an arc gauge's ring: outside the box, toward the truck. */
    public static float[] arcCentre(GaugeSpec g) {
        float r = arcRadius(g);
        float x = g.type == GaugeType.ARC_RIGHT ? g.x - ARC_OUTSIDE * r : g.x + g.w + ARC_OUTSIDE * r;
        return new float[]{x, g.y + g.h / 2};
    }
}
