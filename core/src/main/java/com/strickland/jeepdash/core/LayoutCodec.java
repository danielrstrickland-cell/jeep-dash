package com.strickland.jeepdash.core;

/**
 * Layout <-> text. First line "v1", then one gauge per line:
 * key|paramId|TYPE|x|y|w|h|warnOn|warnAbove|warnLimit, then |unit only for a gauge shown in another unit, then
 * |depth only for a Holo panel floating in front of or behind the truck (the unit then empty if it has none),
 * then |min;max;red only for a gauge with its own range or red zone (each part empty when not set; unit and depth
 * then written too), so layouts without them are written exactly as before. Anything unreadable falls back to the look's default.
 */
public final class LayoutCodec {
    private LayoutCodec() { }

    public static String encode(Layout l) {
        StringBuilder sb = new StringBuilder("v1\n");
        for (GaugeSpec g : l.gauges) {
            sb.append(g.key).append('|').append(g.paramId).append('|').append(g.type).append('|')
                    .append(g.x).append('|').append(g.y).append('|').append(g.w).append('|').append(g.h).append('|')
                    .append(g.warnOn).append('|').append(g.warnAbove).append('|').append(g.warnLimit);
            boolean unit = g.unit != null && !g.unit.isEmpty() && g.unit.indexOf('|') < 0 && g.unit.indexOf('\n') < 0;
            boolean scale = g.rangeMin != null || g.rangeMax != null || g.redFrom != null;
            if (unit || g.depth != 0 || scale) sb.append('|').append(unit ? g.unit : "");
            if (g.depth != 0 || scale) sb.append('|').append(g.depth);
            if (scale) sb.append('|').append(num(g.rangeMin)).append(';').append(num(g.rangeMax)).append(';').append(num(g.redFrom));
            sb.append('\n');
        }
        return sb.toString();
    }

    public static Layout decode(String text, String look, float w, float h) {
        if (text == null) return defaultFor(look, w, h);
        String[] lines = text.split("\n");
        if (lines.length < 2 || !lines[0].trim().equals("v1")) return defaultFor(look, w, h);
        Layout l = new Layout(w, h, HoloFace.minSize(look));
        for (int i = 1; i < lines.length; i++) {
            String[] f = lines[i].trim().split("\\|", -1);
            if (f.length < 10 || f.length > 13) continue;
            try {
                GaugeSpec g = new GaugeSpec(f[0], f[1], GaugeType.valueOf(f[2]),
                        Float.parseFloat(f[3]), Float.parseFloat(f[4]), Float.parseFloat(f[5]), Float.parseFloat(f[6]));
                g.warnOn = Boolean.parseBoolean(f[7]);
                g.warnAbove = Boolean.parseBoolean(f[8]);
                g.warnLimit = Double.parseDouble(f[9]);
                if (f.length >= 11 && !f[10].isEmpty()) g.unit = f[10];
                if (f.length >= 12) g.depth = Math.max(-2, Math.min(2, Integer.parseInt(f[11].trim())));
                if (f.length == 13) {
                    String[] sc = f[12].split(";", -1);
                    if (sc.length == 3) {
                        g.rangeMin = num(sc[0]);
                        g.rangeMax = num(sc[1]);
                        g.redFrom = num(sc[2]);
                    }
                }
                if (l.find(g.key) == null) l.add(g);
            } catch (IllegalArgumentException e) {
                // skip an unreadable line
            }
        }
        if (l.gauges.isEmpty()) return defaultFor(look, w, h);
        l.clampAll();
        return l;
    }

    private static String num(Double v) { return v == null ? "" : String.valueOf(v); }

    private static Double num(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        double v = Double.parseDouble(s.trim());          // unreadable: the caller skips the line
        return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
    }

    /** The approved mockup layouts at 1280x800 dp, scaled to the given screen. */
    public static Layout defaultFor(String look, float w, float h) {
        float sx = w / 1280f, sy = h / 800f;
        Layout l = new Layout(w, h, HoloFace.minSize(look));
        if (HoloFace.isHolo(look)) {
            holo(l, sx, sy);
        } else if ("retro".equals(look)) {
            // the Jeep in 1980s digital: tachometer band across the top, big speed in the middle, bars either side
            put(l, sx, sy, "g1", "rpm", GaugeType.DIAL, 40, 20, 1200, 296);
            put(l, sx, sy, "g2", "speed", GaugeType.DIGITAL, 430, 330, 420, 270);
            put(l, sx, sy, "g3", "coolant", GaugeType.BAR, 40, 330, 370, 186);
            put(l, sx, sy, "g4", "iat", GaugeType.BAR, 40, 530, 370, 186);
            put(l, sx, sy, "g5", "map_kpa", GaugeType.BAR, 870, 330, 370, 186);
            put(l, sx, sy, "g6", "throttle", GaugeType.BAR, 870, 530, 370, 186);
            put(l, sx, sy, "g7", "spark", GaugeType.DIGITAL, 430, 614, 420, 102);
        } else if ("p_retro".equals(look)) {
            // the Cayenne in 1980s digital: the same plan with its own nine values
            put(l, sx, sy, "g1", "rpm", GaugeType.DIAL, 40, 20, 1200, 296);
            put(l, sx, sy, "g2", "speed", GaugeType.DIGITAL, 430, 330, 420, 232);
            put(l, sx, sy, "g3", "boost_c", GaugeType.BAR, 430, 574, 420, 142);
            String[] left = {"coolant", "oil_temp", "iat"}, right = {"boost_set", "cam_r", "cam_l"};
            for (int i = 0; i < 3; i++) {
                put(l, sx, sy, "g" + (4 + i), left[i], GaugeType.BAR, 40, 330 + i * 132, 370, 122);
                put(l, sx, sy, "g" + (7 + i), right[i], GaugeType.BAR, 870, 330 + i * 132, 370, 122);
            }
        } else if ("p_gt".equals(look)) {
            // three dials across the top (boost, engine speed, road speed), six readouts underneath
            put(l, sx, sy, "g1", "boost_c", GaugeType.DIAL, 24, 54, 380, 380);
            put(l, sx, sy, "g2", "rpm", GaugeType.DIAL, 420, 20, 440, 448);
            put(l, sx, sy, "g3", "speed", GaugeType.DIAL, 876, 54, 380, 380);
            String[] row = {"coolant", "oil_temp", "iat", "cam_r", "cam_l", "boost_set"};
            float colW = (1280 - 2 * 24 - 5 * 12) / 6f;
            for (int i = 0; i < row.length; i++)
                put(l, sx, sy, "g" + (4 + i), row[i], GaugeType.DIGITAL, 24 + i * (colW + 12), 484, colW, 236);
        } else if ("jeep".equals(look)) {
            // cluster panel is 40..1240 x 40..600; big dials centred in it, four small ones between them
            put(l, sx, sy, "g1", "speed", GaugeType.DIAL, 80, 130, 380, 380);
            put(l, sx, sy, "g2", "rpm", GaugeType.DIAL, 820, 130, 380, 380);
            put(l, sx, sy, "g3", "coolant", GaugeType.DIAL, 476, 111, 150, 200);
            put(l, sx, sy, "g4", "iat", GaugeType.DIAL, 654, 111, 150, 200);
            put(l, sx, sy, "g5", "spark", GaugeType.DIAL, 476, 329, 150, 200);
            put(l, sx, sy, "g6", "map_kpa", GaugeType.DIAL, 654, 329, 150, 200);
            put(l, sx, sy, "g7", "throttle", GaugeType.DIGITAL, 330, 628, 620, 78);
        } else {
            put(l, sx, sy, "g1", "rpm", GaugeType.DIAL, 16, 16, 744, 420);
            put(l, sx, sy, "g2", "speed", GaugeType.DIGITAL, 776, 16, 488, 420);
            String[] bottom = {"coolant", "iat", "spark", "throttle", "map_kpa"};
            float colW = (1280 - 2 * 16 - 4 * 16) / 5f;
            for (int i = 0; i < bottom.length; i++)
                put(l, sx, sy, "g" + (3 + i), bottom[i], GaugeType.BAR, 16 + i * (colW + 16), 452, colW, 290);
        }
        return l;
    }

    /**
     * The approved Holo mockup (1600 x 1000, so scaled by 0.8): rpm and speed arcs wrapping round the truck,
     * the gear hexagon under its platform, five panels down each side.
     */
    private static void holo(Layout l, float sx, float sy) {
        float m = 0.8f;
        put(l, sx, sy, "g1", "rpm", GaugeType.ARC_LEFT, 220 * m, 60 * m, 380 * m, 790 * m).depth = -1;
        put(l, sx, sy, "g2", "speed", GaugeType.ARC_RIGHT, 1000 * m, 60 * m, 380 * m, 790 * m).depth = -1;
        put(l, sx, sy, "g3", "speed", GaugeType.TRUCK, 470 * m, 125 * m, 660 * m, 660 * m);
        put(l, sx, sy, "g4", "gear", GaugeType.HEX, 735 * m, 686 * m, 130 * m, 126 * m).depth = 1;
        String[] left = {"coolant", "map_kpa", "ltft", "batt_v", "spark"};
        String[] right = {"mpg_avg", "fuel_used", "speedo_err", "roll", "normal"};
        float[] rows = {120, 200, 280, 600, 680};
        int[] depth = {-1, -1, 0, 1, 1};            // the top panels float behind the truck, the bottom ones in front
        for (int i = 0; i < 5; i++) {
            put(l, sx, sy, "g" + (5 + i), left[i], GaugeType.DIGITAL, 28 * m, rows[i] * m, 230 * m, 62 * m).depth = depth[i];
            put(l, sx, sy, "g" + (10 + i), right[i], GaugeType.DIGITAL, 1342 * m, rows[i] * m, 230 * m, 62 * m).depth = depth[i];
        }
    }

    private static GaugeSpec put(Layout l, float sx, float sy, String key, String param, GaugeType t,
                                 float x, float y, float w, float h) {
        GaugeSpec g = new GaugeSpec(key, param, t, x * sx, y * sy, w * sx, h * sy);
        l.add(g);
        return g;
    }
}
