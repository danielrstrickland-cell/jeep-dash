package com.strickland.jeepdash.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The stereo probe: a fixed list of questions put to the KMM-BT728HD so its own replies show how each sound
 * setting is laid out, before this app ever writes one. Only read requests, never a setting write. They go
 * out through {@link KenwoodLink}, which holds the session; this also turns frames into log lines.
 */
public final class KenwoodProbe {

    /** One thing to send: a label for the log and the command it stands for. */
    public static final class Step {
        public final String label;
        public final int category, cmd;
        Step(String label, int category, int cmd) { this.label = label; this.category = category; this.cmd = cmd; }
    }

    private final List<Step> steps = new ArrayList<>();

    public KenwoodProbe() {
        steps.add(new Step("EQ menu", KenwoodCommands.CAT_SETTING, KenwoodCommands.EQ_MENU));
        steps.add(new Step("read preset EQ", KenwoodCommands.CAT_SETTING, KenwoodCommands.GET_PRESET_EQ));
        steps.add(new Step("read custom EQ", KenwoodCommands.CAT_SETTING, KenwoodCommands.GET_CUSTOM_EQ));
        steps.add(new Step("filter menu", KenwoodCommands.CAT_SETTING, KenwoodCommands.HPF_LPF_MENU));
        steps.add(new Step("read HPF/LPF", KenwoodCommands.CAT_SETTING, KenwoodCommands.GET_HPF_LPF));
        steps.add(new Step("fader/balance menu", KenwoodCommands.CAT_SETTING, KenwoodCommands.FAD_BAL_MENU));
        steps.add(new Step("read fader/balance", KenwoodCommands.CAT_SETTING, KenwoodCommands.GET_FAD_BAL));
        steps.add(new Step("read sub pre-out", KenwoodCommands.CAT_SETTING, KenwoodCommands.GET_SUB_PREOUT));
        steps.add(new Step("read sub level", KenwoodCommands.CAT_SETTING, KenwoodCommands.GET_SUB_LEVEL));
        steps.add(new Step("read bass boost", KenwoodCommands.CAT_SETTING, KenwoodCommands.GET_BASS_BOOST));
        steps.add(new Step("read loudness", KenwoodCommands.CAT_SETTING, KenwoodCommands.GET_LOUDNESS));
    }

    /** The questions, in order. The session itself is {@link KenwoodLink}'s business. */
    public List<Step> steps() { return Collections.unmodifiableList(steps); }

    /** The acknowledgement to send back for a received frame, or null if it is itself an ACK or unreadable. */
    public static byte[] ackFor(byte[] frame) {
        KenwoodFrame.Parsed p = KenwoodFrame.parse(frame);
        if (p == null || p.ptype != KenwoodFrame.CMD) return null;
        return KenwoodCommands.ack(p.cmdId, 0);
    }

    /** One log line for a frame: who, category, command, sequence, type and the payload in hex. */
    public static String describe(byte[] frame) {
        KenwoodFrame.Parsed p = KenwoodFrame.parse(frame);
        if (p == null) return "unreadable frame (" + frame.length + " bytes): " + hex(frame);
        return String.format(Locale.US, "%s from %02X to %02X cat %02X cmd %02X seq %02X len %d: %s",
                p.ptype == KenwoodFrame.ACK ? "ACK" : "MSG", p.cmdId[0] & 0xFF, p.cmdId[1] & 0xFF,
                p.cmdId[2] & 0xFF, p.cmdId[3] & 0xFF, p.cmdId[4] & 0xFF, p.payload.length, hex(p.payload));
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 3);
        for (int i = 0; i < b.length; i++) {
            if (i > 0) s.append(' ');
            s.append(String.format(Locale.US, "%02X", b[i] & 0xFF));
        }
        return s.toString();
    }
}
