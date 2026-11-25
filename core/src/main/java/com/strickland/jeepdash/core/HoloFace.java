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

    public static boolean isHolo(String look) { return "holo".equals(look) || "p_holo".equals(look); }

    public static float minSize(String look) { return isHolo(look) ? MIN_SIZE : Layout.MIN_SIZE; }

    /** The looks a car offers, in the order the switch button steps through them. The radio gets Holo only when asked. */
    public static String[] looks(boolean porsche, boolean holoOnRadio) {
        if (porsche) return holoOnRadio ? new String[]{"p_retro", "p_gt", "p_holo"} : new String[]{"p_retro", "p_gt"};
        return new String[]{"xp", "jeep", "retro", "holo"};
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
        pair("boost", "boost_set", "BOOST  /  TARGET", null);
        pair("trim_1", "trim_2", "FUEL TRIM  1 / 2", "FUEL TRIM  2 / 1");
        pair("cam_r", "cam_l", "CAMS  R / L", "CAMS  L / R");
        pair("lambda_r", "lambda_l", "LAMBDA  R / L", "LAMBDA  L / R");
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
