package com.strickland.jeepdash.core;

/**
 * Layout <-> text. First line "v1", then one gauge per line:
 * key|paramId|TYPE|x|y|w|h|warnOn|warnAbove|warnLimit, then |unit only for a gauge shown in another unit (so
 * layouts without one are written exactly as before). Anything unreadable falls back to the look's default.
 */
public final class LayoutCodec {
    private LayoutCodec() { }

    public static String encode(Layout l) {
        StringBuilder sb = new StringBuilder("v1\n");
        for (GaugeSpec g : l.gauges) {
            sb.append(g.key).append('|').append(g.paramId).append('|').append(g.type).append('|')
                    .append(g.x).append('|').append(g.y).append('|').append(g.w).append('|').append(g.h).append('|')
                    .append(g.warnOn).append('|').append(g.warnAbove).append('|').append(g.warnLimit);
            if (g.unit != null && !g.unit.isEmpty() && g.unit.indexOf('|') < 0 && g.unit.indexOf('\n') < 0)
                sb.append('|').append(g.unit);
            sb.append('\n');
        }
        return sb.toString();
    }

    public static Layout decode(String text, String look, float w, float h) {
        if (text == null) return defaultFor(look, w, h);
        String[] lines = text.split("\n");
        if (lines.length < 2 || !lines[0].trim().equals("v1")) return defaultFor(look, w, h);
        Layout l = new Layout(w, h);
        for (int i = 1; i < lines.length; i++) {
            String[] f = lines[i].trim().split("\\|");
            if (f.length != 10 && f.length != 11) continue;
            try {
                GaugeSpec g = new GaugeSpec(f[0], f[1], GaugeType.valueOf(f[2]),
                        Float.parseFloat(f[3]), Float.parseFloat(f[4]), Float.parseFloat(f[5]), Float.parseFloat(f[6]));
                g.warnOn = Boolean.parseBoolean(f[7]);
                g.warnAbove = Boolean.parseBoolean(f[8]);
                g.warnLimit = Double.parseDouble(f[9]);
                if (f.length == 11 && !f[10].isEmpty()) g.unit = f[10];
                if (l.find(g.key) == null) l.add(g);
            } catch (IllegalArgumentException e) {
                // skip an unreadable line
            }
        }
        if (l.gauges.isEmpty()) return defaultFor(look, w, h);
        l.clampAll();
        return l;
    }

    /** The approved mockup layouts at 1280x800 dp, scaled to the given screen. */
    public static Layout defaultFor(String look, float w, float h) {
        float sx = w / 1280f, sy = h / 800f;
        Layout l = new Layout(w, h);
        if ("retro".equals(look)) {
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
            put(l, sx, sy, "g3", "boost", GaugeType.BAR, 430, 574, 420, 142);
            String[] left = {"coolant", "oil_temp", "iat"}, right = {"boost_set", "cam_r", "cam_l"};
            for (int i = 0; i < 3; i++) {
                put(l, sx, sy, "g" + (4 + i), left[i], GaugeType.BAR, 40, 330 + i * 132, 370, 122);
                put(l, sx, sy, "g" + (7 + i), right[i], GaugeType.BAR, 870, 330 + i * 132, 370, 122);
            }
        } else if ("p_gt".equals(look)) {
            // three dials across the top (boost, engine speed, road speed), six readouts underneath
            put(l, sx, sy, "g1", "boost", GaugeType.DIAL, 24, 54, 380, 380);
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

    private static void put(Layout l, float sx, float sy, String key, String param, GaugeType t,
                            float x, float y, float w, float h) {
        l.add(new GaugeSpec(key, param, t, x * sx, y * sy, w * sx, h * sy));
    }
}
