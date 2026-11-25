package com.strickland.jeepdash.core;

/**
 * Heat protection for the Holo look (on unless the owner turns it off): Android's thermal status steps the Quality
 * mode down at once (moderate: Ultra to High; severe: any mode to Power saver; critical: the 3D view pauses and
 * the face shows plain 2D gauges), and the owner's mode comes back only after two minutes cool (none or light),
 * so it does not flap. Times in milliseconds.
 */
public final class HeatGuard {
    public static final int NONE = 0, LIGHT = 1, MODERATE = 2, SEVERE = 3, CRITICAL = 4;
    public static final String[] NAMES = {"None", "Light", "Moderate", "Severe", "Critical"};
    public static final int CAP_NONE = 0, CAP_HIGH = 1, CAP_SAVER = 2, CAP_2D = 3;
    public static final long COOL_MS = 120_000;

    private int cap;
    private long coolSince = -1;

    /** A new status (Android's; emergency and shutdown count as critical). +1 stepped down, -1 restored, 0 no change. */
    public synchronized int update(int status, long nowMs, boolean enabled) {
        if (!enabled) {
            boolean was = cap != CAP_NONE;
            cap = CAP_NONE;
            coolSince = -1;
            return was ? -1 : 0;
        }
        int s = Math.max(NONE, Math.min(CRITICAL, status));
        int want = s >= CRITICAL ? CAP_2D : s >= SEVERE ? CAP_SAVER : s >= MODERATE ? CAP_HIGH : CAP_NONE;
        if (want > cap) {
            cap = want;
            coolSince = -1;
            return 1;
        }
        if (s <= LIGHT) {
            if (coolSince < 0) coolSince = nowMs;
            if (cap != CAP_NONE && nowMs - coolSince >= COOL_MS) {
                cap = CAP_NONE;
                return -1;
            }
        } else {
            coolSince = -1;
        }
        return 0;
    }

    public synchronized int cap() { return cap; }

    public synchronized boolean paused3d() { return cap == CAP_2D; }

    /** The mode to use: the owner's choice, lowered by the heat cap. */
    public static String mode(String chosen, int cap) {
        String c = QualityMode.pick(chosen);
        if (cap >= CAP_SAVER) return QualityMode.SAVER;
        if (cap == CAP_HIGH && c.equals(QualityMode.ULTRA)) return QualityMode.HIGH;
        return c;
    }

    public static String name(int status) { return NAMES[Math.max(NONE, Math.min(CRITICAL, status))]; }
}
