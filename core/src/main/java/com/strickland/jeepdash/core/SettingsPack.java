package com.strickland.jeepdash.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Packs a set of saved settings into one line of text and back, keeping each one's type (switch, whole
 * number, decimal or text). It is how a sound profile is put away while another is in use. A value may hold
 * anything, the separators included; a bad entry is skipped without losing the rest.
 */
public final class SettingsPack {
    private SettingsPack() { }

    private static String escape(String s) {
        return s.replace("%", "%25").replace(";", "%3B").replace("=", "%3D");
    }

    private static String unescape(String s) {
        return s.replace("%3D", "=").replace("%3B", ";").replace("%25", "%");
    }

    /** Booleans, Integers, Floats and Strings; anything else is stored as its text. */
    public static String pack(Map<String, ?> values) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, ?> e : values.entrySet()) {
            Object v = e.getValue();
            if (v == null) continue;
            char type = v instanceof Boolean ? 'b' : v instanceof Integer ? 'i' : v instanceof Float ? 'f' : 's';
            if (b.length() > 0) b.append(';');
            b.append(escape(e.getKey())).append('=').append(type).append(escape(String.valueOf(v)));
        }
        return b.toString();
    }

    public static Map<String, Object> unpack(String text) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (text == null || text.isEmpty()) return out;
        for (String part : text.split(";")) {
            int eq = part.indexOf('=');
            if (eq < 1 || eq + 1 >= part.length()) continue;
            String key = unescape(part.substring(0, eq)), v = unescape(part.substring(eq + 2));
            try {
                switch (part.charAt(eq + 1)) {
                    case 'b': out.put(key, Boolean.parseBoolean(v)); break;
                    case 'i': out.put(key, Integer.parseInt(v)); break;
                    case 'f': out.put(key, Float.parseFloat(v)); break;
                    case 's': out.put(key, v); break;
                    default: break;
                }
            } catch (NumberFormatException ignored) {
                // skip this one
            }
        }
        return out;
    }
}
