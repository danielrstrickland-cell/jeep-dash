package com.strickland.jeepdash.core;

import java.util.HashMap;
import java.util.Map;

/** The visualizer's TUNE settings. Every look keeps its own values, each inside a fixed range. */
public final class VizTune {
    /** One slider or on/off switch. */
    public static final class Knob {
        public final String id, label;
        public final float min, max, def;
        public final boolean toggle;

        Knob(String id, String label, float min, float max, float def) {
            this(id, label, min, max, def, false);
        }

        Knob(String id, String label, float min, float max, float def, boolean toggle) {
            this.id = id;
            this.label = label;
            this.min = min;
            this.max = max;
            this.def = def;
            this.toggle = toggle;
        }
    }

    private static Knob slider(String id, String label, float min, float max, float def) {
        return new Knob(id, label, min, max, def);
    }

    private static Knob toggle(String id, String label) { return new Knob(id, label, 0, 1, 1, true); }

    /**
     * Not a look: the settings every look shares, on the hidden menu's Effects tab. Kept under its own number
     * so it saves and loads with the looks' settings.
     */
    public static final int GLOBAL = 9;
    private static final Knob[] EFFECTS = {
            slider("bloom", "Glow (bloom)", 0, 1.5f, 0.7f),
            slider("crt", "CRT filter", 0, 1, 0),
            slider("blend", "Blend between looks", 0, 2, 0.6f),
    };

    private static final Knob GAIN = slider("gain", "Sensitivity", 0.5f, 2, 1);
    private static final Knob GLOW = slider("glow", "Background glow", 0, 1.5f, 1);
    private static final Knob FALL = slider("fall", "Fall speed", 0.4f, 3, 1);
    private static final Knob CAPS = toggle("caps", "Peak caps");
    /**
     * The looks that can be chosen, by number: blocks, smooth, bass ring, swirl. Number 2 was the boom meters,
     * taken out; the numbers stay as they were so every look keeps its saved settings.
     */
    public static final int[] CHOICES = {0, 1, 3, 4};
    public static final int SMOOTH = 1;

    /** A saved look, or Smooth when it was the boom meters or makes no sense. */
    public static int savedLook(int saved) {
        for (int c : CHOICES) if (c == saved) return saved;
        return SMOOTH;
    }

    /** The look after this one, for the LOOK button. */
    public static int nextLook(int look) {
        for (int c : CHOICES) if (c > look) return c;
        return CHOICES[0];
    }

    /** Indexed by look: blocks, smooth, (the boom meters' old place), bass ring, swirl. */
    private static final Knob[][] KNOBS = {
            {GAIN, GLOW, slider("width", "Bar width", 0.4f, 0.95f, 0.78f), FALL, CAPS},
            {GAIN, GLOW, slider("width", "Bar width", 0.4f, 0.95f, 0.72f), FALL, CAPS},
            {GAIN, GLOW},
            {GAIN, GLOW, slider("size", "Ring size", 0.6f, 1.4f, 1), slider("pulse", "Pulse", 0, 2, 1), CAPS},
            {GAIN, GLOW, slider("trail", "Trail length", 0.15f, 1.5f, 0.55f), slider("spin", "Spin", 0, 2, 1),
                    slider("zoom", "Zoom", 0, 2, 1), toggle("drops", "Droplets")},
    };
    private static final Knob[] SHARED = {GAIN, GLOW};

    private final Map<String, Float> values = new HashMap<>();

    public static Knob[] knobs(int look) {
        if (look == GLOBAL) return EFFECTS;
        return look >= 0 && look < KNOBS.length ? KNOBS[look] : SHARED;
    }

    /** True for knobs measured in seconds rather than percent. */
    public static boolean seconds(String id) { return id.equals("trail") || id.equals("blend"); }

    private static Knob knob(int look, String id) {
        for (Knob k : knobs(look)) if (k.id.equals(id)) return k;
        return null;
    }

    /** The value of a knob on a look; 1 for a knob that look does not have, so it changes nothing. */
    public float get(int look, String id) {
        Knob k = knob(look, id);
        if (k == null) return 1;
        Float v = values.get(look + "." + id);
        return v == null ? k.def : v;
    }

    public boolean on(int look, String id) { return get(look, id) >= 0.5f; }

    public void set(int look, String id, float v) {
        Knob k = knob(look, id);
        if (k == null || Float.isNaN(v)) return;
        if (k.toggle) v = v >= 0.5f ? 1 : 0;
        values.put(look + "." + id, Math.max(k.min, Math.min(k.max, v)));
    }

    /** Where the knob sits along its slider, 0..1. */
    public float at(int look, String id) {
        Knob k = knob(look, id);
        return k == null ? 0 : (get(look, id) - k.min) / (k.max - k.min);
    }

    public void setAt(int look, String id, float t) {
        Knob k = knob(look, id);
        if (k != null) set(look, id, k.min + (k.max - k.min) * Math.max(0, Math.min(1, t)));
    }

    public void reset(int look) { values.keySet().removeIf(key -> key.startsWith(look + ".")); }

    public String save() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Float> e : values.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append(';');
        return sb.toString();
    }

    /** Reads what save() wrote. Anything it does not understand is skipped. */
    public static VizTune load(String saved) {
        VizTune t = new VizTune();
        if (saved == null) return t;
        for (String part : saved.split(";")) {
            int eq = part.indexOf('='), dot = part.indexOf('.');
            if (dot <= 0 || eq <= dot + 1) continue;
            try {
                t.set(Integer.parseInt(part.substring(0, dot)), part.substring(dot + 1, eq), Float.parseFloat(part.substring(eq + 1)));
            } catch (NumberFormatException ignored) {
                // skip it
            }
        }
        return t;
    }
}
