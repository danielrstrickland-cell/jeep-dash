package com.strickland.jeepdash.core;

/**
 * Opening the dash when the tablet connects to the Bluetooth device the owner chose (the stereo). Devices are
 * known here only by the key the Android side gives them; nothing about a real device is kept in the code.
 */
public final class AutoStart {
    private AutoStart() { }

    public enum Action { OPEN, NOTIFY, NOTHING }

    /**
     * What to do when a device connects.
     *
     * @param chosen        the device the owner picked, or null/empty when the feature is off
     * @param connected     the device that just connected
     * @param appInFront    a Jeep Dash screen is already on screen
     * @param overlayAllowed the app holds "display over other apps", without which Android will not let it
     *                      open a screen from the background; a notification to tap is offered instead
     */
    public static Action decide(String chosen, String connected, boolean appInFront, boolean overlayAllowed) {
        if (chosen == null || chosen.isEmpty() || connected == null || !chosen.equalsIgnoreCase(connected))
            return Action.NOTHING;
        if (appInFront) return Action.NOTHING;
        return overlayAllowed ? Action.OPEN : Action.NOTIFY;
    }

    /** True when a paired device may be offered as the trigger: never the dongle the link remembers. */
    public static boolean offer(String key, String dongleKey) {
        if (key == null || key.isEmpty()) return false;
        return dongleKey == null || !key.equalsIgnoreCase(dongleKey);
    }
}
