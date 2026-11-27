package com.strickland.jeepdash.core;

/** The Kenwood command builders: id bytes, payloads, acknowledgements, and the unconfirmed-command gate. */
public class KenwoodCommandsTest {

    public static void volumeCarriesCoreMainControlIds() {
        KenwoodFrame.Parsed p = KenwoodFrame.parse(KenwoodCommands.volume(7, 22));
        T.ok(p != null, "volume frame parses");
        T.eq(new byte[]{(byte) 0xA4, 0x50, 0x11, 2, 7}, p.cmdId, "core, main, control, volume, seq");
        T.eq(new byte[]{22}, p.payload, "one byte: the level");
        T.eq(KenwoodFrame.CMD, p.ptype, "a command, not an ack");
    }

    public static void muteAndSourcePayloads() {
        T.eq(new byte[]{1}, KenwoodFrame.parse(KenwoodCommands.mute(1, true)).payload, "mute on is 1");
        T.eq(new byte[]{0}, KenwoodFrame.parse(KenwoodCommands.mute(1, false)).payload, "mute off is 0");
        T.eq(new byte[]{0x57, 0x00}, KenwoodFrame.parse(KenwoodCommands.source(1, 0x57, 0)).payload,
                "source sends its two address bytes");
    }

    public static void presetEqIsASettingCommand() {
        KenwoodFrame.Parsed p = KenwoodFrame.parse(KenwoodCommands.presetEq(3, 5));
        T.eq(new byte[]{(byte) 0xA4, 0x50, 0x3D, 15, 3}, p.cmdId,
                "core, main, setting (3D, as the stock app's settings screens send), set preset EQ, seq");
        T.eq(new byte[]{5}, p.payload, "preset index");
    }

    public static void sequenceByteThatNeedsStuffingSurvives() {
        KenwoodFrame.Parsed p = KenwoodFrame.parse(KenwoodCommands.volume(0x7E, 0x7D));
        T.ok(p != null, "frame with flag bytes in id and payload parses");
        T.eq(0x7E, p.cmdId[4] & 0xFF, "sequence preserved");
        T.eq(0x7D, p.payload[0] & 0xFF, "payload preserved");
    }

    public static void ackEchoesTheReceivedId() {
        byte[] id = {0x50, (byte) 0xA4, 0x41, (byte) 0xE5, 9};
        KenwoodFrame.Parsed p = KenwoodFrame.parse(KenwoodCommands.ack(id, 0));
        T.eq(KenwoodFrame.ACK, p.ptype, "ack type");
        T.eq(id, p.cmdId, "id echoed");
        T.eq(new byte[]{0}, p.payload, "result code");
    }

    public static void readCarriesAZeroAndGateFlagsUnknownSets() {
        KenwoodFrame.Parsed r = KenwoodFrame.parse(KenwoodCommands.read(1, KenwoodCommands.GET_CUSTOM_EQ));
        T.eq(new byte[]{0}, r.payload, "a read carries one 0, as the stock app's settings screens send it");
        T.eq(0x3D, r.cmdId[2] & 0xFF, "in the settings category");
        T.ok(KenwoodCommands.unconfirmed(KenwoodCommands.CAT_SETTING, KenwoodCommands.SET_CUSTOM_EQ),
                "custom EQ write is unconfirmed");
        T.ok(!KenwoodCommands.unconfirmed(KenwoodCommands.CAT_SETTING, KenwoodCommands.SET_PRESET_EQ),
                "preset EQ write is confirmed");
        T.ok(!KenwoodCommands.unconfirmed(KenwoodCommands.CAT_CONTROL, KenwoodCommands.VOLUME),
                "volume is confirmed");
    }
}
