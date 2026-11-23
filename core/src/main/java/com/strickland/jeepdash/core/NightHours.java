package com.strickland.jeepdash.core;

/** When the XP look goes dark: always, never, or on Auto from 7 in the evening to 7 in the morning. */
public final class NightHours {
    private NightHours() { }

    public static final String LIGHT = "Light", DARK = "Dark", AUTO = "Auto";
    static final int DARK_FROM = 19, LIGHT_FROM = 7;

    /** @param hour the tablet clock's hour, 0-23 */
    public static boolean dark(String mode, int hour) {
        if (DARK.equals(mode)) return true;
        if (AUTO.equals(mode)) return hour >= DARK_FROM || hour < LIGHT_FROM;
        return false;
    }
}
