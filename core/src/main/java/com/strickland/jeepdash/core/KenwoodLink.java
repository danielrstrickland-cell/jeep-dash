package com.strickland.jeepdash.core;

import java.util.HashMap;
import java.util.Map;

/**
 * The Sound hub's live conversation with the KMM-BT728HD: it hands out the frames for the commands whose
 * payloads are confirmed (session open, mute, volume, preset EQ), numbers them, matches the stereo's answers
 * to what was asked, and acknowledges whatever the stereo sends on its own. Pure: the app owns the socket.
 *
 * A volume command is held to the stereo's own scale, 0 to {@link #VOLUME_MAX} (the owner's figure for the
 * knob). That the command's level is the knob's number is still to be seen on the stereo itself. Source
 * switching is not here: it needs the source list the stereo itself reports, which the probe log will show.
 */
public final class KenwoodLink {
    /** The top of the stereo's volume scale: the KMM-BT728HD's knob runs 0 to 35. */
    public static final int VOLUME_MAX = 35;
    /** How many preset places the screen offers; the names come from the probe log. */
    public static final int PRESETS = 10;

    /** A command to write to the socket, its number and what to call it in the log. */
    public static final class Sent {
        public final byte[] frame;
        public final int seq;
        public final String label;
        Sent(byte[] frame, int seq, String label) { this.frame = frame; this.seq = seq; this.label = label; }
    }

    /** What a received frame means. */
    public static final class Event {
        public final String line;        // for the diagnostics log
        public final byte[] ack;         // to write back, or null
        public final String answered;    // the command this answers, or null
        public final int result;         // the stereo's result code when answered (0 = accepted)
        Event(String line, byte[] ack, String answered, int result) {
            this.line = line; this.ack = ack; this.answered = answered; this.result = result;
        }
    }

    private final Map<Integer, String> pending = new HashMap<>();
    private int seq;

    private synchronized Sent send(int category, int cmd, String label, byte... payload) {
        seq = (seq + 1) & 0x7F;
        pending.put(seq, label);
        return new Sent(KenwoodCommands.command(category, cmd, seq, payload), seq, label);
    }

    /** The session opener, sent once after connecting, as the stock app and the probe do. */
    public Sent open() { return send(KenwoodProbe.CAT_SESSION, KenwoodProbe.OPEN, "Open session"); }

    public Sent mute(boolean on) {
        return send(KenwoodCommands.CAT_CONTROL, KenwoodCommands.MUTE, on ? "Mute on" : "Mute off", (byte) (on ? 1 : 0));
    }

    /** A volume level, held between 0 and the top of the stereo's scale. */
    public Sent volume(int level) {
        int v = Math.max(0, Math.min(VOLUME_MAX, level));
        return send(KenwoodCommands.CAT_CONTROL, KenwoodCommands.VOLUME, "Volume " + v, (byte) v);
    }

    /** One of the stereo's built-in EQ presets, by its place in the stereo's list. */
    public Sent presetEq(int index) {
        int i = Math.max(0, Math.min(PRESETS - 1, index));
        return send(KenwoodCommands.CAT_SETTING, KenwoodCommands.SET_PRESET_EQ, "Preset EQ " + i, (byte) i);
    }

    /** True while a command has had no answer yet. */
    public synchronized boolean waiting(int seq) { return pending.containsKey(seq); }

    /** Stops waiting for a command's answer; returns its label the first time, null if it was answered. */
    public synchronized String giveUp(int seq) { return pending.remove(seq); }

    /** A frame arrived: log it, acknowledge the stereo's own messages, and match answers to commands. */
    public synchronized Event onFrame(byte[] frame) {
        String line = KenwoodProbe.describe(frame);
        KenwoodFrame.Parsed p = KenwoodFrame.parse(frame);
        if (p == null) return new Event(line, null, null, 0);
        if (p.ptype != KenwoodFrame.ACK) return new Event(line, KenwoodCommands.ack(p.cmdId, 0), null, 0);
        boolean ours = (p.cmdId[0] & 0xFF) == KenwoodCommands.CORE && (p.cmdId[1] & 0xFF) == KenwoodCommands.MAIN;
        String label = ours ? pending.remove(p.cmdId[4] & 0xFF) : null;
        int result = p.payload.length > 0 ? p.payload[0] & 0xFF : 0;
        return new Event(line, null, label, result);
    }
}
