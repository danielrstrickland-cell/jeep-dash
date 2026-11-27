package com.strickland.jeepdash.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * What the KMM-BT728HD's owner's manual (B5A-3143-00a) says each sound setting can be. These are the stereo's
 * own menu ranges, used to label what the probe reads back. Which command number is which setting is still
 * being worked out from the probe log, so a match here is a hint ("fits"), never a certainty.
 */
public final class KenwoodManual {
    /** The 13 manual-EQ bands, in Hz, low to high. */
    public static final double[] EQ_HZ = {62.5, 100, 160, 250, 400, 630, 1000, 1600, 2500, 4000, 6300, 10000, 16000};
    public static final int EQ_MIN = -9, EQ_MAX = 9;
    public static final double[] Q_FACTORS = {1.35, 1.50, 2.00};
    public static final String[] PRESET_EQ = {"NATURAL", "ROCK", "POPS", "EASY", "TOP40", "JAZZ", "POWERFUL", "USER"};
    public static final int SUB_LEVEL_MIN = -50, SUB_LEVEL_MAX = 10;
    public static final int BASS_BOOST_LEVELS = 5, LOUDNESS_LEVELS = 2;
    public static final int FADER_MAX = 15, BALANCE_MAX = 15;
    public static final int VOLUME_OFFSET_MIN = -15, VOLUME_OFFSET_MAX = 6;
    /** Front/rear high-pass and sub low-pass choices, in Hz. THROUGH is the choice after the last. */
    public static final int[] XOVER_HZ = {30, 40, 50, 60, 70, 80, 90, 100, 120, 150, 180, 220, 250};
    public static final double[] TWEETER_KHZ = {1, 1.6, 2.5, 4, 5, 6.3, 8, 10, 12.5};
    public static final int[] SLOPES_DB = {-6, -12, -18, -24};
    public static final int XOVER_GAIN_MIN = -8, XOVER_GAIN_MAX = 0;
    public static final String[] DTA_POSITION = {"ALL", "FRONT RIGHT", "FRONT LEFT", "FRONT ALL"};
    public static final String[] DTA_SPEAKERS = {"FL", "FR", "RL", "RR", "SUB"};
    public static final double DTA_MAX_FEET = 20.01;
    public static final String[] CAR_TYPE = {"COMPACT", "FULL SIZE CAR", "WAGON", "MINIVAN", "SUV", "MINIVAN(LONG)", "OFF"};
    public static final String[] REAR_LOCATION = {"DOOR", "REAR DECK", "2ND ROW", "3RD ROW"};

    /** The name of EQ band i, such as "62.5 Hz" or "1.6 kHz"; "band N" past the 13. */
    public static String eqBand(int i) {
        if (i < 0 || i >= EQ_HZ.length) return "band " + i;
        double hz = EQ_HZ[i];
        if (hz < 1000) return (hz == Math.rint(hz) ? String.format(Locale.US, "%d", (int) hz) : String.valueOf(hz)) + " Hz";
        double k = hz / 1000.0;
        return (k == Math.rint(k) ? String.format(Locale.US, "%d", (int) k) : String.valueOf(k)) + " kHz";
    }

    /** True if every byte is a plausible EQ level read as a plain signed number (-9..+9). */
    static boolean allSigned(byte[] p, int from, int to, int min, int max) {
        for (int i = from; i < to; i++) if (p[i] < min || p[i] > max) return false;
        return true;
    }

    /** True if every byte is a plausible level read as a number with the low end stored as zero (0..max-min). */
    static boolean allOffset(byte[] p, int from, int to, int min, int max) {
        for (int i = from; i < to; i++) {
            int v = p[i] & 0xFF;
            if (v > max - min) return false;
        }
        return true;
    }

    /**
     * A manual-based reading of one setting's answer, or null if nothing in the manual fits. Always worded as
     * "fits", because the command numbers are still being matched to settings.
     */
    public static String hint(int cmd, byte[] p) {
        if (p == null || p.length == 0) return null;
        List<String> out = new ArrayList<>();
        int n = p.length;
        if (n == 1) {
            int v = p[0] & 0xFF;
            int s = KenwoodSettings.subLevel(p[0]);
            if (s >= SUB_LEVEL_MIN && s <= SUB_LEVEL_MAX && (p[0] & 0x80) != 0) out.add("fits the sub level (" + s + ", range -50..+10)");
            if (v < PRESET_EQ.length) out.add("fits a preset-EQ number (" + PRESET_EQ[v] + ")");
            if (v < Q_FACTORS.length) out.add("fits the Q factor (" + String.format(Locale.US, "%.2f", Q_FACTORS[v]) + ")");
            if (v <= FADER_MAX * 2) out.add("fits fader/balance if centre is " + FADER_MAX);
        } else if (n >= 13 && n <= 14) {
            String how = allSigned(p, 0, 13, EQ_MIN, EQ_MAX) ? "as plain -9..+9"
                    : allOffset(p, 0, 13, EQ_MIN, EQ_MAX) ? "as 0..18 with 9 = flat" : null;
            if (how != null) {
                StringBuilder b = new StringBuilder("fits the 13 EQ bands " + how + ": ");
                for (int i = 0; i < 13; i++) {
                    int v = how.startsWith("as plain") ? p[i] : (p[i] & 0xFF) - 9;
                    b.append(i == 0 ? "" : ", ").append(eqBand(i)).append(' ').append(v > 0 ? "+" : "").append(v);
                }
                out.add(b.toString());
            }
        }
        if (out.isEmpty()) return null;
        return String.join("; ", out) + " (manual)";
    }

    /** Every manual range as lines of text, for the log header, so the probe log and the manual sit together. */
    public static List<String> reference() {
        List<String> l = new ArrayList<>();
        l.add("EQ bands (-9..+9): " + eqNames());
        l.add("Q factor: 1.35, 1.50, 2.00 (one value for all bands)");
        l.add("Preset EQ: " + String.join(", ", PRESET_EQ));
        l.add("Sub level: -50..+10; Bass boost: OFF, LV1-LV5; Loudness: OFF, LV1, LV2");
        l.add("Fader: R15..F15; Balance: L15..R15; Volume offset: -15..+6");
        l.add("Crossover (front HPF, rear HPF, sub LPF) Hz: " + Arrays.toString(XOVER_HZ) + " or THROUGH; slope -6/-12/-18/-24 dB; gain -8..0; sub phase NORMAL/REVERSE");
        l.add("Time alignment: 0..20.01 ft and gain -8..0 for " + String.join(", ", DTA_SPEAKERS));
        return Collections.unmodifiableList(l);
    }

    private static String eqNames() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < EQ_HZ.length; i++) b.append(i == 0 ? "" : ", ").append(eqBand(i));
        return b.toString();
    }

    private KenwoodManual() { }
}
