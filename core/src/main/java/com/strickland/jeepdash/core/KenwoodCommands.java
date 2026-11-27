package com.strickland.jeepdash.core;

/**
 * The commands this app sends the KMM-BT728HD, as ready-to-write {@link KenwoodFrame}s. Every command is an
 * address, a category, a command byte and a payload, exactly as the stock Kenwood remote app queues them.
 *
 * Two groups, kept apart on purpose. CONFIRMED commands are ones the stock app itself sends, so the payload
 * is known: source, volume, mute and preset EQ. UNCONFIRMED ones are settings the protocol defines (custom
 * EQ, filters, fader, subwoofer, bass boost, loudness) but the stock app never sends — its "custom EQ" screen
 * only drives the phone's own equalizer — so their payloads are not known yet. Those are exposed only as
 * read requests and as a raw sender, for the Sound hub's probe to learn the layout from the head unit's own
 * replies before anything is written to it.
 */
public final class KenwoodCommands {
    public static final int CORE = 0xA4;        // OpenLink core, first id byte of everything this side sends
    public static final int MAIN = 0x50;        // the head unit's main address
    public static final int CAT_CONTROL = 0x11; // source, volume, mute, preset EQ
    public static final int CAT_SETTING = 0x3D; // sound settings: what the stock app's settings screens send (it answers in 5D)

    // CAT_CONTROL commands (confirmed)
    public static final int SOURCE = 1, VOLUME = 2, MUTE = 3, PRESET_EQ = 4;

    // CAT_SETTING commands. Only SET_PRESET_EQ is confirmed; the rest are read with the GET_ forms first.
    public static final int EQ_MENU = 1, GET_CUSTOM_EQ = 4, SET_CUSTOM_EQ = 5,
            HPF_LPF_MENU = 6, GET_HPF_LPF = 8, SET_HPF_LPF = 9,
            FAD_BAL_MENU = 10, GET_FAD_BAL = 11, SET_FAD_BAL = 12,
            GET_PRESET_EQ = 14, SET_PRESET_EQ = 15,
            GET_SUB_PREOUT = 67, SET_SUB_PREOUT = 68, GET_SUB_LEVEL = 78, SET_SUB_LEVEL = 79,
            GET_BASS_BOOST = 73, SET_BASS_BOOST = 74, GET_LOUDNESS = 76, SET_LOUDNESS = 77;

    private KenwoodCommands() { }

    /** Any command to the head unit's main address: the building block the named senders use. */
    public static byte[] command(int category, int cmd, int seq, byte... payload) {
        byte[] id = {(byte) CORE, (byte) MAIN, (byte) category, (byte) cmd, (byte) seq};
        return KenwoodFrame.frame(id, KenwoodFrame.CMD, payload);
    }

    /** The acknowledgement for a frame the head unit sent: its five id bytes echoed back with a result code. */
    public static byte[] ack(byte[] receivedId, int result) {
        return KenwoodFrame.frame(receivedId, KenwoodFrame.ACK, new byte[]{(byte) result});
    }

    public static byte[] volume(int seq, int level) {
        return command(CAT_CONTROL, VOLUME, seq, (byte) level);
    }

    public static byte[] mute(int seq, boolean on) {
        return command(CAT_CONTROL, MUTE, seq, (byte) (on ? 1 : 0));
    }

    /** Switches source; the two bytes are the source's address pair as the head unit lists them. */
    public static byte[] source(int seq, int hi, int lo) {
        return command(CAT_CONTROL, SOURCE, seq, (byte) hi, (byte) lo);
    }

    /** Picks one of the head unit's built-in EQ presets by its position in the preset list. */
    public static byte[] presetEq(int seq, int index) {
        return command(CAT_SETTING, SET_PRESET_EQ, seq, (byte) index);
    }

    /** Asks the head unit for a setting's current value: one of the GET_ commands, carrying one 0 as the stock app's do. */
    public static byte[] read(int seq, int getCmd) {
        return command(CAT_SETTING, getCmd, seq, (byte) 0);
    }

    /** True for the setting commands whose payload layout is still unconfirmed, so callers gate them. */
    public static boolean unconfirmed(int category, int cmd) {
        if (category != CAT_SETTING) return false;
        return cmd == SET_CUSTOM_EQ || cmd == SET_HPF_LPF || cmd == SET_FAD_BAL || cmd == SET_SUB_PREOUT
                || cmd == SET_SUB_LEVEL || cmd == SET_BASS_BOOST || cmd == SET_LOUDNESS;
    }
}
