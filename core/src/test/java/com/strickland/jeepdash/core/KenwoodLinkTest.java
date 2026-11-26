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

    public static void anAnswerAddressedBackToTheAppIsMatchedToo() {
        KenwoodLink k = new KenwoodLink();
        KenwoodLink.Sent mute = k.mute(true);
        byte[] back = {0x50, (byte) 0xA4, 0x11, 3, (byte) mute.seq};          // from the stereo to the app
        KenwoodLink.Event e = k.onFrame(KenwoodFrame.frame(back, KenwoodFrame.ACK, new byte[]{0}));
        T.eq("Mute on", e.answered, "an answer with the addresses turned round is still the answer");
    }

    public static void aRefusalCarriesItsCode() {
        KenwoodLink k = new KenwoodLink();
        KenwoodFrame.Parsed sent = KenwoodFrame.parse(k.presetEq(2).frame);
        KenwoodLink.Event e = k.onFrame(KenwoodCommands.ack(sent.cmdId, 5));
        T.eq("Preset EQ 2", e.answered, "which command");
        T.eq(5, e.result, "the refusal code");
    }

    /** The message the owner's KMM-BT728HD opens with, once a second, until it is happy or gives up after ten. */
    private static byte[] hello() {
        return KenwoodFrame.frame(new byte[]{0x50, (byte) 0xA4, 0x3B, 2, 0}, KenwoodFrame.CMD, new byte[0]);
    }

    public static void theFirstAcknowledgementIsAddressedBackToTheStereo() {
        KenwoodLink k = new KenwoodLink();
        KenwoodLink.Event e = k.onFrame(hello());
        T.ok(e.answered == null, "not an answer to anything");
        KenwoodFrame.Parsed ack = KenwoodFrame.parse(e.ack);
        T.eq(KenwoodFrame.ACK, ack.ptype, "an acknowledgement");
        T.eq(new byte[]{(byte) 0xA4, 0x50, 0x3B, 2, 0}, ack.cmdId, "from the app to the stereo: its two addresses swapped");
        T.eq(new byte[]{0}, ack.payload, "result 0");
        T.ok(e.line.contains("7E") && e.line.contains("7C"), "the log line carries the frame's own bytes: " + e.line);
    }

    public static void aMessageTheStereoRepeatsGetsADifferentAcknowledgementEachTime() {
        KenwoodLink k = new KenwoodLink();
        k.onFrame(hello());
        KenwoodFrame.Parsed second = KenwoodFrame.parse(k.onFrame(hello()).ack);
        T.eq(new byte[]{(byte) 0xA4, 0x50, 0x3B, 2, 0}, second.cmdId, "second try: still swapped");
        T.eq(0, second.payload.length, "but with no result byte");
        KenwoodFrame.Parsed third = KenwoodFrame.parse(k.onFrame(hello()).ack);
        T.eq(new byte[]{0x50, (byte) 0xA4, 0x3B, 2, 0}, third.cmdId, "third: its id as it came");
        T.eq(0, third.payload.length, "no result byte");
        KenwoodFrame.Parsed fourth = KenwoodFrame.parse(k.onFrame(hello()).ack);
        T.eq(KenwoodFrame.CMD, fourth.ptype, "fourth: answered with the same message, not an acknowledgement");
        T.eq(new byte[]{(byte) 0xA4, 0x50, 0x3B, 2, 0}, fourth.cmdId, "from the app");
        KenwoodFrame.Parsed fifth = KenwoodFrame.parse(k.onFrame(hello()).ack);
        T.eq(new byte[]{0x50, (byte) 0xA4, 0x3B, 2, 0}, fifth.cmdId, "fifth: what builds 84 to 88 sent");
        T.eq(new byte[]{0}, fifth.payload, "id as it came, result 0");
        KenwoodFrame.Parsed sixth = KenwoodFrame.parse(k.onFrame(hello()).ack);
        T.eq(new byte[]{(byte) 0xA4, 0x50, 0x3B, 2, 0}, sixth.cmdId, "then round again from the first: swapped");
        T.eq(new byte[]{0}, sixth.payload, "with result 0");
        T.ok(!k.ackStyleConfirmed(), "nothing has been accepted yet");
    }

    public static void aNewMessageMeansTheLastAcknowledgementWorked() {
        KenwoodLink k = new KenwoodLink();
        k.onFrame(hello());
        k.onFrame(hello());
        k.onFrame(hello());                                               // style 2 went out last
        byte[] next = KenwoodFrame.frame(new byte[]{0x50, (byte) 0xA4, 0x11, 2, 1}, KenwoodFrame.CMD, new byte[]{12});
        KenwoodFrame.Parsed ack = KenwoodFrame.parse(k.onFrame(next).ack);
        T.ok(k.ackStyleConfirmed(), "the stereo moved on, so it took the last one");
        T.eq(2, k.ackStyle(), "style 2 is the one");
        T.eq(new byte[]{0x50, (byte) 0xA4, 0x11, 2, 1}, ack.cmdId, "and the new message is acknowledged the same way");
        T.eq(0, ack.payload.length, "no result byte");
        k.onFrame(next);
        T.eq(2, k.ackStyle(), "once it is known, a repeat does not change it");
    }

    public static void aRememberedStyleIsTriedFirst() {
        KenwoodLink k = new KenwoodLink(3);
        T.eq(KenwoodFrame.CMD, KenwoodFrame.parse(k.onFrame(hello()).ack).ptype, "starts with the style that worked last time");
        T.eq(0, new KenwoodLink(99).ackStyle(), "a bad remembered value starts from the first");
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
