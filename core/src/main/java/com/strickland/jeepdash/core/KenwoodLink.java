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

    /**
     * The ways an acknowledgement can be put together. Which one the stereo wants was not settled by reading
     * the stock app: on the owner's stereo an acknowledgement that echoed the message's id unchanged was
     * ignored, and the stereo repeated its opening message once a second, ten times, then hung up. So a
     * message the stereo repeats is acknowledged a different way each time, and when it moves on to something
     * new, the way last used is the one it accepts. The app remembers that for next time.
     */
    public static final int ACK_STYLES = 5;
    private static final String[] STYLE = {"addresses swapped, result 0", "addresses swapped, no result",
            "id as it came, no result", "answered with the same message", "id as it came, result 0"};

    private final Map<Integer, String> pending = new HashMap<>();
    private int seq;
    private int style;                 // the acknowledgement style to start a message with
    private int repeats;               // how many times the stereo has repeated the message in hand
    private byte[] lastId;             // the id of the stereo's last message
    private boolean confirmed;

    public KenwoodLink() { this(0); }

    /** Starts from the acknowledgement style that worked last time. */
    public KenwoodLink(int rememberedStyle) {
        style = rememberedStyle >= 0 && rememberedStyle < ACK_STYLES ? rememberedStyle : 0;
    }

    /** The acknowledgement style in use for the stereo's current message. */
    public synchronized int ackStyle() { return (style + repeats) % ACK_STYLES; }

    /** True once the stereo has moved on from a message, which means it accepted {@link #ackStyle()}. */
    public synchronized boolean ackStyleConfirmed() { return confirmed; }

    public static String styleName(int style) { return style >= 0 && style < ACK_STYLES ? STYLE[style] : "?"; }

    private static byte[] acknowledge(int style, byte[] id) {
        byte[] back = {id[1], id[0], id[2], id[3], id[4]};          // from the app to the stereo
        switch (style) {
            case 0: return KenwoodFrame.frame(back, KenwoodFrame.ACK, new byte[]{0});
            case 1: return KenwoodFrame.frame(back, KenwoodFrame.ACK, new byte[0]);
            case 2: return KenwoodFrame.frame(id, KenwoodFrame.ACK, new byte[0]);
            case 3: return KenwoodFrame.frame(back, KenwoodFrame.CMD, new byte[0]);
            default: return KenwoodFrame.frame(id, KenwoodFrame.ACK, new byte[]{0});
        }
    }

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
        String line = KenwoodProbe.describe(frame) + "  [" + KenwoodProbe.hex(frame) + "]";
        KenwoodFrame.Parsed p = KenwoodFrame.parse(frame);
        if (p == null) return new Event(line, null, null, 0);
        if (p.ptype != KenwoodFrame.ACK) {
            if (lastId != null && java.util.Arrays.equals(lastId, p.cmdId)) {
                if (!confirmed) repeats++;                         // it did not take the last one: try the next way
            } else {
                if (lastId != null) {                              // it moved on: the last way worked
                    style = (style + repeats) % ACK_STYLES;
                    confirmed = true;
                }
                lastId = p.cmdId.clone();
                repeats = 0;
            }
            int use = (style + repeats) % ACK_STYLES;
            return new Event(line + "  (acknowledged: " + STYLE[use] + ")", acknowledge(use, p.cmdId), null, 0);
        }
        int a = p.cmdId[0] & 0xFF, b = p.cmdId[1] & 0xFF;          // an answer may carry our id as sent, or turned round
        boolean ours = (a == KenwoodCommands.CORE && b == KenwoodCommands.MAIN)
                || (a == KenwoodCommands.MAIN && b == KenwoodCommands.CORE);
        String label = ours ? pending.remove(p.cmdId[4] & 0xFF) : null;
        int result = p.payload.length > 0 ? p.payload[0] & 0xFF : 0;
        return new Event(line, null, label, result);
    }
}
