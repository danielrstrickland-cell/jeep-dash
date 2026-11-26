package com.strickland.jeepdash.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The stereo probe: a fixed list of questions put to the KMM-BT728HD so its own replies show how each sound
 * setting is laid out, before this app ever writes one. It sends the session opener and then only read
 * requests — never a setting write — and acknowledges whatever the head unit sends, as the stock app does.
 * Pure: it hands out frames and turns replies into log lines; the screen owns the socket and the timing.
 */
public final class KenwoodProbe {

    /** One thing to send: a label for the log and the command it stands for. */
    public static final class Step {
        public final String label;
        public final int category, cmd;
        Step(String label, int category, int cmd) { this.label = label; this.category = category; this.cmd = cmd; }
    }

    static final int CAT_SESSION = 0x3B, OPEN = 1;

    private final List<Step> steps = new ArrayList<>();
    private int next;
    private int seq;

    public KenwoodProbe() {
        steps.add(new Step("open session", CAT_SESSION, OPEN));
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

    public int stepCount() { return steps.size(); }

    public boolean done() { return next >= steps.size(); }

    /** The step that {@link #nextFrame()} will send, or null when the probe has asked everything. */
    public Step peek() { return done() ? null : steps.get(next); }

    /** The next question as a frame to write, with its own sequence number; null when done. */
    public byte[] nextFrame() {
        if (done()) return null;
        Step s = steps.get(next++);
        seq = (seq + 1) & 0x7F;
        return KenwoodCommands.command(s.category, s.cmd, seq);
    }

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
