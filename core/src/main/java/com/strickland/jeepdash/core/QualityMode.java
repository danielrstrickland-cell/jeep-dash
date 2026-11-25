package com.strickland.jeepdash.core;

/**
 * The Holo look's Quality mode, the owner's one setting for how hard the tablet works:
 * Ultra (30 a second at the screen's resolution with MSAA, soft shadow, ambient occlusion, multi-pass bloom,
 * full-size textures; supersampling was dropped: twice the pixels for edges MSAA already smooths),
 * High (the default: 60 a second at the screen's own resolution, 2K textures, one bloom pass, 30 a second when
 * nothing moves) and Power saver (30 a second, 10-15 when still, under-sampled, no MSAA, bloom or colour fringe,
 * fewer scanlines, the lite truck when there is one). Never more than 60 frames a second.
 */
public final class QualityMode {
    public static final String ULTRA = "Ultra", HIGH = "High", SAVER = "Power saver";
    public static final String[] MODES = {ULTRA, HIGH, SAVER};
    /** Which truck file to show (see model). */
    public static final String FULL = "full", TWO_K = "2k", LITE = "lite", STAND_IN = "standin";

    public final String name;
    public final int maxFps, idleFps;
    public final float renderScale;
    public final boolean msaa, bloom, multiPassBloom, shadow, ssao, fringe, fullTextures, liteMesh;
    /** dp between scanlines (when the hologram effects are on). */
    public final float scanlineGap;

    private QualityMode(String name, int maxFps, int idleFps, float renderScale, boolean msaa, boolean bloom,
                        boolean multiPassBloom, boolean shadow, boolean ssao, boolean fringe, boolean fullTextures,
                        boolean liteMesh, float scanlineGap) {
        this.name = name; this.maxFps = maxFps; this.idleFps = idleFps; this.renderScale = renderScale; this.msaa = msaa;
        this.bloom = bloom; this.multiPassBloom = multiPassBloom; this.shadow = shadow; this.ssao = ssao; this.fringe = fringe;
        this.fullTextures = fullTextures; this.liteMesh = liteMesh; this.scanlineGap = scanlineGap;
    }

    /** The saved choice, or High. */
    public static String pick(String saved) {
        for (String m : MODES) if (m.equals(saved)) return m;
        return HIGH;
    }

    public static QualityMode of(String mode) {
        switch (pick(mode)) {
            case ULTRA: return new QualityMode(ULTRA, 30, 15, 1.0f, true, true, true, true, true, true, true, false, 3);
            case SAVER: return new QualityMode(SAVER, 30, 12, 0.75f, false, false, false, false, false, false, false, true, 6);
            default: return new QualityMode(HIGH, 60, 30, 1.0f, true, true, false, false, false, true, false, false, 3);
        }
    }

    /** Frames a second: the cap while anything moves or a value changes, less when all is still. */
    public int fps(boolean active) { return active ? maxFps : idleFps; }

    /**
     * Which truck file a mode shows, from what the owner imported: the full model, its 2K-texture copy (made when
     * its textures are bigger than 2K), the lite model. One file imported is used in every mode.
     */
    public static String model(String mode, boolean full, boolean twoK, boolean lite) {
        String[] order;
        switch (pick(mode)) {
            case ULTRA: order = new String[]{FULL, TWO_K, LITE}; break;
            case SAVER: order = new String[]{LITE, TWO_K, FULL}; break;
            default: order = new String[]{TWO_K, FULL, LITE}; break;
        }
        for (String o : order) {
            if (o.equals(FULL) && full || o.equals(TWO_K) && twoK || o.equals(LITE) && lite) return o;
        }
        return STAND_IN;
    }
}
