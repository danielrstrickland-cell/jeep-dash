package com.strickland.jeepdash.core;

/**
 * When the XP look goes dark: always, never, or on Auto. Auto is decided by AutoNight (light sensor, else sunset
 * and sunrise); the old fixed 7 pm to 7 am rule below is no longer used by the dash.
 */
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

    /** What the setting is called on screen. */
    public static String label(String mode) {
        if (DARK.equals(mode)) return "Always dark";
        if (AUTO.equals(mode)) return "Auto";
        return "Always light";
    }

    /** The setting for an on-screen label, or null. */
    public static String fromLabel(String label) {
        for (String m : new String[]{LIGHT, DARK, AUTO}) if (label(m).equals(label)) return m;
        return null;
    }
}
