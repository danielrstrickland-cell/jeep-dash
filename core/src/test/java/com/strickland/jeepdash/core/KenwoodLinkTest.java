package com.strickland.jeepdash.core;

/** The Sound hub's conversation with the stereo: the confirmed commands, their answers, and the volume cap. */
public class KenwoodLinkTest {

    public static void opensTheSessionFirstThenCountsUp() {
        KenwoodLink k = new KenwoodLink();
        KenwoodLink.Sent open = k.open();
        KenwoodFrame.Parsed p = KenwoodFrame.parse(open.frame);
        T.eq(new byte[]{(byte) 0xA4, 0x50, 0x3B, 1, 1}, p.cmdId, "the same session opener the probe sends");
        T.eq(0, p.payload.length, "with no payload");
        T.eq(2, KenwoodFrame.parse(k.mute(true).frame).cmdId[4] & 0xFF, "the next command has the next number");
    }

    public static void sequenceWrapsBeforeTheTopBit() {
        KenwoodLink k = new KenwoodLink();
        int last = 0;
        for (int i = 0; i < 130; i++) last = k.mute(false).seq;
        T.eq(130 & 0x7F, last, "wraps at 0x7F like the probe");
    }

    public static void volumeStaysOnTheStereosOwnScale() {
        KenwoodLink k = new KenwoodLink();
        T.eq(35, KenwoodLink.VOLUME_MAX, "the KMM-BT728HD's knob runs 0 to 35");
        T.eq(new byte[]{9}, KenwoodFrame.parse(k.volume(9).frame).payload, "a level on the scale goes as asked");
        T.eq(new byte[]{35}, KenwoodFrame.parse(k.volume(35).frame).payload, "the top of the scale can be sent");
        T.eq(new byte[]{35}, KenwoodFrame.parse(k.volume(60).frame).payload, "past the top is sent as the top");
        T.eq(new byte[]{0}, KenwoodFrame.parse(k.volume(-4).frame).payload, "never below zero");
    }

    public static void presetAndMuteAreTheConfirmedCommands() {
        KenwoodLink k = new KenwoodLink();
        KenwoodFrame.Parsed preset = KenwoodFrame.parse(k.presetEq(3).frame);
        T.eq(KenwoodCommands.CAT_SETTING, preset.cmdId[2] & 0xFF, "preset EQ is a setting");
        T.eq(KenwoodCommands.SET_PRESET_EQ, preset.cmdId[3] & 0xFF, "the confirmed write");
        T.eq(new byte[]{3}, preset.payload, "by its place in the list");
        T.eq(new byte[]{0}, KenwoodFrame.parse(k.presetEq(-2).frame).payload, "a bad place is the first one");
        T.eq(new byte[]{1}, KenwoodFrame.parse(k.mute(true).frame).payload, "mute on");
    }

    public static void anAnswerIsMatchedToWhatWasAsked() {
        KenwoodLink k = new KenwoodLink();
        KenwoodLink.Sent mute = k.mute(true);
        T.ok(k.waiting(mute.seq), "waiting for the stereo's answer");
        KenwoodFrame.Parsed sent = KenwoodFrame.parse(mute.frame);
        KenwoodLink.Event e = k.onFrame(KenwoodCommands.ack(sent.cmdId, 0));
        T.eq("Mute on", e.answered, "the answer names the command");
        T.eq(0, e.result, "with the stereo's result code");
        T.ok(e.ack == null, "an answer is not answered back");
        T.ok(!k.waiting(mute.seq), "no longer waiting");
    }

    public static void aRefusalCarriesItsCode() {
        KenwoodLink k = new KenwoodLink();
        KenwoodFrame.Parsed sent = KenwoodFrame.parse(k.presetEq(2).frame);
        KenwoodLink.Event e = k.onFrame(KenwoodCommands.ack(sent.cmdId, 5));
        T.eq("Preset EQ 2", e.answered, "which command");
        T.eq(5, e.result, "the refusal code");
    }

    public static void whatTheStereoSaysIsAcknowledged() {
        KenwoodLink k = new KenwoodLink();
        byte[] id = {0x50, (byte) 0xA4, 0x11, 2, 9};
        KenwoodLink.Event e = k.onFrame(KenwoodFrame.frame(id, KenwoodFrame.CMD, new byte[]{12}));
        T.ok(e.answered == null, "not an answer to anything");
        KenwoodFrame.Parsed ack = KenwoodFrame.parse(e.ack);
        T.eq(KenwoodFrame.ACK, ack.ptype, "acknowledged, as the stock app does");
        T.eq(id, ack.cmdId, "with its own id");
        T.ok(e.line.contains("0C"), "and logged with its payload");
    }

    public static void junkAndStrangersAnswersAreOnlyLogged() {
        KenwoodLink k = new KenwoodLink();
        KenwoodLink.Event junk = k.onFrame(new byte[]{0x7E, 1, 2, 0x7C});
        T.ok(junk.ack == null && junk.answered == null, "junk gets nothing back");
        T.ok(junk.line.startsWith("unreadable"), "but is logged");
        byte[] id = {(byte) 0xA4, 0x50, 0x11, 3, 77};
        KenwoodLink.Event stray = k.onFrame(KenwoodCommands.ack(id, 0));
        T.ok(stray.answered == null, "an answer to nothing we asked is not matched");
    }

    public static void givingUpOnASilentCommand() {
        KenwoodLink k = new KenwoodLink();
        KenwoodLink.Sent v = k.volume(8);
        T.eq("Volume 8", k.giveUp(v.seq), "the command that went unanswered");
        T.ok(k.giveUp(v.seq) == null, "only reported once");
    }
}
