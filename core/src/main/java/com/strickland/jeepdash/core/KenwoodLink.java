package com.strickland.jeepdash.core;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * The live conversation with the KMM-BT728HD, held to what Kenwood's own app (KENWOOD Remote 2.0.5) does on
 * the wire. Pure: the app owns the socket and the clock, writes what {@link #poll} hands out and passes in
 * what arrives.
 *
 * What the stock app does, and so this does:
 * - A plain message from the stereo is acknowledged with its five id bytes exactly as they came, type ACK and
 *   one result byte: 0, or 2 when it is the same message as the one before. Replies and notifications are
 *   not acknowledged.
 * - When the stereo says hello (category 3B, command 2) the app starts the session: 5B/02 with 00 00 01 01,
 *   then 3B/01. The start-up then goes on, each step set off by the stereo's last answer: 5B/01 is followed by
 *   3B/34 (features), 5B/34 by 3B/36 with a 0, 5B/36 (the stereo's remote-key version) by 3B/37, and 5B/37 by
 *   3B/12. (With its clock-sync setting on, the stock app sends the time in 3B/35 instead of 3B/37; this does
 *   not, because that would set the stereo's clock.)
 * - Besides the absolute volume command (11/02) the app has remote keys (11/05, one key code): volume up and
 *   down and ATT, with codes that depend on the stereo's remote-key version.
 * - One message is in the air at a time. Messages are numbered from 0; the number moves on only when the
 *   stereo acknowledges the message. One not acknowledged within a second is sent again, and after ten such
 *   seconds the link is given up.
 *
 * Commands from the screen (mute, volume, preset EQ, the probe's questions) wait until the session is open
 * and the start-up exchange has finished, or has been quiet for {@link #SETTLE_MS}.
 * A volume command is held to the stereo's own scale, 0 to {@link #VOLUME_MAX}.
 */
public final class KenwoodLink {
    /** The top of the stereo's volume scale: the KMM-BT728HD's knob runs 0 to 35. */
    public static final int VOLUME_MAX = 35;
    /** How many preset places the screen offers; the names come from the probe log. */
    public static final int PRESETS = 10;
    /** A message not acknowledged in this long is sent again; after this many such waits the link is given up. */
    public static final int RESEND_MS = 1000, TRIES = 10;
    /** How long a start-up exchange may sit with no next step before commands stop waiting for it. */
    public static final int SETTLE_MS = 3000;

    static final int CAT_SESSION = 0x3B, CAT_INFO = 0x5B;
    static final int HELLO = 2, OPEN = 1, INFO = 2, INFO_ANSWER = 1, FEATURES = 0x34, REMOTE_VERSION = 0x36,
            CLOCK = 0x35, START_37 = 0x37, SOURCES = 0x12;
    static final int KEY = 5;                                      // in the control category: one remote key
    private static final int TAKEN = 0, REPEAT = 2;
    private static final int PLAIN = KenwoodFrame.CMD, DEBUG = 0x3F;   // the two message types the stock app acknowledges

    /** Something to write to the socket now. */
    public static final class Out {
        public final byte[] frame;
        public final String label;
        public final int attempt;        // 1 the first time, 2 for the first repeat...
        Out(byte[] frame, String label, int attempt) { this.frame = frame; this.label = label; this.attempt = attempt; }
    }

    /** What a received frame means. */
    public static final class Event {
        public final String line;        // for the diagnostics log
        public final byte[] ack;         // to write back, or null
        public final String answered;    // the message of ours this acknowledges, or null
        public final int result;         // the stereo's result code when answered (0 = accepted)
        public final int cat, cmd;       // for a message the stereo sent of its own: its numbers; -1 otherwise
        public final byte[] payload;     // and what it carried, or null
        Event(String line, byte[] ack, String answered, int result) { this(line, ack, answered, result, -1, -1, null); }
        Event(String line, byte[] ack, String answered, int result, int cat, int cmd, byte[] payload) {
            this.line = line; this.ack = ack; this.answered = answered; this.result = result;
            this.cat = cat; this.cmd = cmd; this.payload = payload;
        }
    }

    private static final class Msg {
        final int category, cmd;
        final String label;
        final byte[] payload;
        Msg(int category, int cmd, String label, byte[] payload) {
            this.category = category; this.cmd = cmd; this.label = label; this.payload = payload;
        }
    }

    private final ArrayDeque<Msg> setUp = new ArrayDeque<>();     // session messages, ahead of everything else
    private final ArrayDeque<Msg> queue = new ArrayDeque<>();     // commands, once the session is open
    private Msg flying;                // the message in the air
    private byte[] flyingFrame;
    private long sentAt;
    private int waits;                 // seconds this message has gone unacknowledged
    private int seq;                   // the number the next new message carries
    private boolean begun, open, lost;
    private byte[] lastId;             // the id of the stereo's last plain message
    private boolean starting;          // the start-up exchange after the opener is under way
    private boolean touched;           // a start-up step happened since the last poll
    private long startAt;              // when the start-up last moved
    private int remoteVersion;         // the stereo's remote-key version, 0 until it says

    private void startSession() {
        begun = true;
        setUp.add(new Msg(CAT_INFO, INFO, "Session set-up", new byte[]{0, 0, 1, 1}));
        setUp.add(new Msg(CAT_SESSION, OPEN, "Open session", new byte[0]));
    }

    /** Starts the session from this side, for a stereo that has said nothing. Does nothing if it is started. */
    public synchronized void begin() {
        if (!begun) startSession();
    }

    /** Any command, by its numbers: the probe's questions come this way. */
    public synchronized void ask(String label, int category, int cmd, byte... payload) {
        queue.add(new Msg(category, cmd, label, payload));
    }

    public void mute(boolean on) {
        ask(on ? "Mute on" : "Mute off", KenwoodCommands.CAT_CONTROL, KenwoodCommands.MUTE, (byte) (on ? 1 : 0));
    }

    /** A volume level, held between 0 and the top of the stereo's scale. */
    public void volume(int level) {
        int v = Math.max(0, Math.min(VOLUME_MAX, level));
        ask("Volume " + v, KenwoodCommands.CAT_CONTROL, KenwoodCommands.VOLUME, (byte) v);
    }

    /** One of the stereo's built-in EQ presets, by its place in the stereo's list. */
    public void presetEq(int index) {
        int i = Math.max(0, Math.min(PRESETS - 1, index));
        ask("Preset EQ " + i, KenwoodCommands.CAT_SETTING, KenwoodCommands.SET_PRESET_EQ, (byte) i);
    }

    /** The stereo's remote-key version (2 or 4 in the stock app), or 0 if it has not said. */
    public synchronized int remoteVersion() { return remoteVersion; }

    private void key(String label, int code, int codeV4) {
        int v;
        synchronized (this) { v = remoteVersion; }
        ask(label, KenwoodCommands.CAT_CONTROL, KEY, (byte) (v == 4 ? codeV4 : code));
    }

    /** The volume-up key, as if pressed on a remote. */
    public void volumeUp() { key("Volume up key", 8, 1); }

    /** The volume-down key. */
    public void volumeDown() { key("Volume down key", 7, 2); }

    /** The ATT key, which dips or mutes the volume and gives it back. */
    public void att() { key("ATT key", 12, 4); }

    /** True once the stereo has answered the session opener. */
    public synchronized boolean open() { return open; }

    /** True when the stereo left a message unacknowledged for ten seconds: hang up, as the stock app does. */
    public synchronized boolean lost() { return lost; }

    /** True when nothing is in the air and nothing is waiting to go. */
    public synchronized boolean idle() { return flying == null && setUp.isEmpty() && queue.isEmpty(); }

    /** What to write now: a new message if none is in the air, the one in the air again if its second is up, or null. */
    public synchronized Out poll(long nowMs) {
        if (lost) return null;
        if (touched) {
            touched = false;
            startAt = nowMs;
        }
        if (starting && nowMs - startAt >= SETTLE_MS) starting = false;      // it stalled: stop holding commands
        if (flying != null) {
            if (nowMs - sentAt < RESEND_MS) return null;
            if (++waits >= TRIES) {
                lost = true;
                return null;
            }
            sentAt = nowMs;
            return new Out(flyingFrame, flying.label, waits + 1);
        }
        Msg next = setUp.poll();
        if (next == null && open && !starting) next = queue.poll();
        if (next == null) return null;
        flying = next;
        flyingFrame = KenwoodCommands.command(next.category, next.cmd, seq, next.payload);
        sentAt = nowMs;
        waits = 0;
        return new Out(flyingFrame, next.label, 1);
    }

    /** A frame arrived: log it, acknowledge the stereo's own messages, and match acknowledgements to ours. */
    public synchronized Event onFrame(byte[] frame) {
        String line = KenwoodProbe.describe(frame) + "  [" + KenwoodProbe.hex(frame) + "]";
        KenwoodFrame.Parsed p = KenwoodFrame.parse(frame);
        if (p == null) return new Event(line, null, null, 0);
        if (p.ptype == KenwoodFrame.ACK) return acknowledged(p, line);
        if (p.ptype == DEBUG) return new Event(line, KenwoodCommands.ack(p.cmdId, TAKEN), null, 0);
        if (p.ptype != PLAIN) return new Event(line, null, null, 0);
        if (lastId != null && Arrays.equals(lastId, p.cmdId))
            return new Event(line + "  (a repeat)", KenwoodCommands.ack(p.cmdId, REPEAT), null, 0);
        int cat0 = p.cmdId[2] & 0xFF, cmd0 = p.cmdId[3] & 0xFF;
        lastId = p.cmdId.clone();
        if ((p.cmdId[0] & 0xFF) == KenwoodCommands.MAIN && (p.cmdId[1] & 0xFF) == KenwoodCommands.CORE) {
            int cat = p.cmdId[2] & 0xFF, cmd = p.cmdId[3] & 0xFF;
            if (cat == CAT_SESSION && cmd == HELLO) {
                startSession();
                line += "  (the stereo's hello: starting the session)";
            } else if (cat == CAT_INFO) {
                line += startUp(cmd, p.payload);
            }
        }
        return new Event(line, KenwoodCommands.ack(p.cmdId, TAKEN), null, 0, cat0, cmd0, p.payload.clone());
    }

    /** The stereo's start-up answers, each followed by the stock app's next step. Returns a note for the log. */
    private String startUp(int cmd, byte[] payload) {
        Msg next;
        String note = "";
        if (cmd == INFO_ANSWER) {
            next = new Msg(CAT_SESSION, FEATURES, "Ask for features", new byte[0]);
        } else if (cmd == FEATURES) {
            next = new Msg(CAT_SESSION, REMOTE_VERSION, "Start-up 36", new byte[]{0});
        } else if (cmd == REMOTE_VERSION) {
            if (payload.length > 0) remoteVersion = payload[0] & 0xFF;
            note = "  (remote-key version " + remoteVersion + ")";
            next = new Msg(CAT_SESSION, START_37, "Start-up 37", new byte[0]);
        } else if (cmd == CLOCK) {
            next = new Msg(CAT_SESSION, START_37, "Start-up 37", new byte[0]);
        } else if (cmd == START_37) {
            next = new Msg(CAT_SESSION, SOURCES, "Ask for sources", new byte[0]);
        } else {
            return note;
        }
        setUp.add(next);
        starting = true;
        touched = true;
        return note;
    }

    private Event acknowledged(KenwoodFrame.Parsed p, String line) {
        byte[] id = p.cmdId;
        boolean mine = flying != null && (id[0] & 0xFF) == KenwoodCommands.CORE && (id[1] & 0xFF) == KenwoodCommands.MAIN
                && (id[2] & 0xFF) == flying.category && (id[3] & 0xFF) == flying.cmd && (id[4] & 0xFF) == seq;
        if (!mine) return new Event(line + "  (not for the message in the air)", null, null, 0);
        int result = p.payload.length > 0 ? p.payload[0] & 0xFF : TAKEN;
        if (result == REPEAT) result = TAKEN;                     // it had the first copy already
        String label = flying.label;
        if (flying.category == CAT_SESSION && flying.cmd == OPEN) open = true;
        if (flying.category == CAT_SESSION && flying.cmd == SOURCES) starting = false;      // the last start-up step
        else if (starting) touched = true;
        flying = null;
        flyingFrame = null;
        seq = (seq + 1) & 0xFF;
        if (seq == 0xFF) seq = 0;
        return new Event(line, null, label, result);
    }
}
