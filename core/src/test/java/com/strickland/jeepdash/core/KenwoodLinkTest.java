package com.strickland.jeepdash.core;

/**
 * The conversation with the stereo, held to what Kenwood's own app does on the wire: how it acknowledges,
 * how it starts a session when the stereo says hello, and how it sends one numbered message at a time.
 */
public class KenwoodLinkTest {
    /** The stereo's hello exactly as it came off the owner's KMM-BT728HD. */
    static final String HELLO = "7E 0B 01 50 A4 3B 02 00 00 01 00 00 88 59 7C";

    static byte[] hex(String s) {
        String[] parts = s.trim().split(" ");
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = (byte) Integer.parseInt(parts[i], 16);
        return out;
    }

    /** The stereo's acknowledgement of a message this side sent: its id echoed, with a result. */
    static byte[] taken(KenwoodLink.Out o, int result) {
        return KenwoodFrame.frame(KenwoodFrame.parse(o.frame).cmdId, KenwoodFrame.ACK, new byte[]{(byte) result});
    }

    /** A link whose session is open: hello heard, both set-up messages taken. */
    static KenwoodLink opened() {
        KenwoodLink k = new KenwoodLink();
        k.onFrame(hex(HELLO));
        k.onFrame(taken(k.poll(0), 0));
        k.onFrame(taken(k.poll(0), 0));
        return k;
    }

    public static void theHelloIsAcknowledgedByteForByteAsTheStockAppDoes() {
        KenwoodLink.Event e = new KenwoodLink().onFrame(hex(HELLO));
        T.eq(hex("7E 0B 01 50 A4 3B 02 00 01 01 00 01 00 6A 74 7C"), e.ack, "its id as it came, type ACK, result 0");
    }

    public static void theHelloStartsTheSessionTheStockWay() {
        KenwoodLink k = new KenwoodLink();
        T.ok(k.poll(0) == null, "nothing is sent before the stereo speaks");
        k.onFrame(hex(HELLO));
        KenwoodLink.Out info = k.poll(0);
        T.eq(hex("7E 0B 01 A4 50 5B 02 00 00 01 00 04 00 00 01 01 9C A1 7C"), info.frame, "first 5B/02 with 00 00 01 01, numbered 0");
        T.ok(k.poll(10) == null, "the next waits until this one is taken");
        k.onFrame(taken(info, 0));
        KenwoodLink.Out open = k.poll(20);
        T.eq(hex("7E 0B 01 A4 50 3B 01 01 00 01 00 00 08 5C 7C"), open.frame, "then 3B/01, numbered 1");
        T.ok(!k.open(), "not open until the stereo takes it");
        k.onFrame(taken(open, 0));
        T.ok(k.open(), "open once it has");
        T.ok(k.poll(30) == null && k.idle(), "and nothing more to send");
    }

    public static void aRepeatedHelloIsAcknowledgedAsARepeatAndStartsNothingNew() {
        KenwoodLink k = new KenwoodLink();
        k.onFrame(hex(HELLO));
        KenwoodLink.Event again = k.onFrame(hex(HELLO));
        T.eq(new byte[]{2}, KenwoodFrame.parse(again.ack).payload, "the same message again gets result 2");
        k.onFrame(taken(k.poll(0), 0));
        k.onFrame(taken(k.poll(0), 0));
        T.ok(k.idle(), "the session was started once, not twice");
    }

    public static void commandsWaitUntilTheSessionIsOpen() {
        KenwoodLink k = new KenwoodLink();
        k.volume(12);
        T.ok(k.poll(0) == null, "held while there is no session");
        k.onFrame(hex(HELLO));
        k.onFrame(taken(k.poll(0), 0));
        k.onFrame(taken(k.poll(0), 0));
        KenwoodFrame.Parsed p = KenwoodFrame.parse(k.poll(0).frame);
        T.eq(new byte[]{(byte) 0xA4, 0x50, 0x11, 2, 2}, p.cmdId, "then the volume command, with the next number");
        T.eq(new byte[]{12}, p.payload, "and its level");
    }

    public static void theNumberMovesOnlyWhenAMessageIsTaken() {
        KenwoodLink k = opened();
        k.mute(true);
        k.mute(false);
        KenwoodLink.Out a = k.poll(0);
        T.eq(2, KenwoodFrame.parse(a.frame).cmdId[4] & 0xFF, "after the two set-up messages");
        T.ok(k.poll(500) == null, "one message in the air at a time");
        k.onFrame(taken(a, 0));
        T.eq(3, KenwoodFrame.parse(k.poll(600).frame).cmdId[4] & 0xFF, "the next number once it is taken");
    }

    public static void anUntakenMessageIsSentAgainEachSecondThenTheLinkIsGivenUp() {
        KenwoodLink k = opened();
        k.mute(true);
        KenwoodLink.Out first = k.poll(0);
        T.ok(k.poll(999) == null, "not before its second is up");
        for (int i = 1; i < KenwoodLink.TRIES; i++) {
            KenwoodLink.Out again = k.poll(i * 1000L);
            T.eq(first.frame, again.frame, "the very same message again, try " + (i + 1));
            T.eq(i + 1, again.attempt, "counted");
        }
        T.ok(!k.lost(), "still trying after nine repeats");
        T.ok(k.poll(KenwoodLink.TRIES * 1000L) == null, "the tenth second sends nothing");
        T.ok(k.lost(), "and the link is given up, as the stock app does");
    }

    public static void volumeStaysOnTheStereosOwnScale() {
        KenwoodLink k = opened();
        k.volume(99);
        KenwoodLink.Out top = k.poll(0);
        T.eq(new byte[]{(byte) KenwoodLink.VOLUME_MAX}, KenwoodFrame.parse(top.frame).payload, "held to the top of the knob");
        k.onFrame(taken(top, 0));
        k.volume(-3);
        T.eq(new byte[]{0}, KenwoodFrame.parse(k.poll(0).frame).payload, "and to zero");
    }

    public static void presetAndMuteAreTheConfirmedCommands() {
        KenwoodLink k = opened();
        k.presetEq(4);
        KenwoodLink.Out preset = k.poll(0);
        KenwoodFrame.Parsed p = KenwoodFrame.parse(preset.frame);
        T.eq(KenwoodCommands.CAT_SETTING, p.cmdId[2] & 0xFF, "preset EQ is a sound setting");
        T.eq(new byte[]{4}, p.payload, "by its place in the list");
        k.onFrame(taken(preset, 0));
        k.mute(true);
        KenwoodFrame.Parsed m = KenwoodFrame.parse(k.poll(0).frame);
        T.eq(KenwoodCommands.MUTE, m.cmdId[3] & 0xFF, "mute");
        T.eq(new byte[]{1}, m.payload, "on");
    }

    public static void anAnswerIsMatchedToWhatWasAsked() {
        KenwoodLink k = opened();
        k.volume(10);
        KenwoodLink.Event e = k.onFrame(taken(k.poll(0), 0));
        T.eq("Volume 10", e.answered, "the label of the command it answers");
        T.eq(0, e.result, "accepted");
    }

    public static void aRefusalCarriesItsCodeAndTheLinkMovesOn() {
        KenwoodLink k = opened();
        k.presetEq(9);
        k.mute(true);
        KenwoodLink.Event e = k.onFrame(taken(k.poll(0), 7));
        T.eq("Preset EQ 9", e.answered, "the refused command");
        T.eq(7, e.result, "with the stereo's code");
        T.eq(3, KenwoodFrame.parse(k.poll(0).frame).cmdId[4] & 0xFF, "the next message goes out under a new number");
    }

    public static void toldItIsARepeatMeansTheStereoAlreadyHasIt() {
        KenwoodLink k = opened();
        k.mute(true);
        KenwoodLink.Event e = k.onFrame(taken(k.poll(0), 2));
        T.eq(0, e.result, "the first copy got through: accepted");
        T.ok(k.idle(), "nothing left in the air");
    }

    public static void anAcknowledgementForSomethingElseIsOnlyLogged() {
        KenwoodLink k = opened();
        k.mute(true);
        KenwoodLink.Out o = k.poll(0);
        byte[] id = KenwoodFrame.parse(o.frame).cmdId.clone();
        id[4] = 9;
        KenwoodLink.Event e = k.onFrame(KenwoodFrame.frame(id, KenwoodFrame.ACK, new byte[]{0}));
        T.ok(e.answered == null, "another number is not this message's answer");
        T.ok(!k.idle(), "so it is still waiting");
    }

    public static void theStereosAnswerToTheSetUpIsFollowedByTheFeatureRequest() {
        KenwoodLink k = opened();
        k.mute(true);
        byte[] info = KenwoodFrame.frame(new byte[]{0x50, (byte) 0xA4, 0x5B, 1, 1}, KenwoodFrame.CMD, new byte[]{1, 2});
        KenwoodLink.Event e = k.onFrame(info);
        T.eq(new byte[]{0}, KenwoodFrame.parse(e.ack).payload, "acknowledged");
        T.eq(new byte[]{(byte) 0xA4, 0x50, 0x3B, 0x34, 2}, KenwoodFrame.parse(k.poll(0).frame).cmdId,
                "3B/34 goes out next, ahead of the waiting command");
    }

    public static void onlyPlainMessagesAreAcknowledged() {
        KenwoodLink k = new KenwoodLink();
        byte[] reply = KenwoodFrame.frame(new byte[]{0x50, (byte) 0xA4, 0x41, 4, 5}, 3, new byte[]{1});
        T.ok(k.onFrame(reply).ack == null, "a reply is not acknowledged, as in the stock app");
        byte[] notice = KenwoodFrame.frame(new byte[]{0x50, (byte) 0xA4, 0x11, 2, 6}, 4, new byte[]{8});
        T.ok(k.onFrame(notice).ack == null, "nor is a notification");
        T.ok(k.onFrame(new byte[]{0x7E, 1, 2, 0x7C}).ack == null, "nor junk");
    }

    public static void aSilentStereoCanBeStartedFromThisSideOnce() {
        KenwoodLink k = new KenwoodLink();
        k.begin();
        k.begin();
        KenwoodLink.Out info = k.poll(0);
        T.eq(0x5B, KenwoodFrame.parse(info.frame).cmdId[2] & 0xFF, "the same set-up, unprompted");
        k.onFrame(taken(info, 0));
        k.onFrame(taken(k.poll(0), 0));
        T.ok(k.open() && k.idle(), "queued once however often it is asked for");
    }

    public static void theNumberWrapsBelowFF() {
        KenwoodLink k = opened();
        int last = 1;
        for (int i = 0; i < 300; i++) {
            k.ask("read", KenwoodCommands.CAT_SETTING, KenwoodCommands.GET_PRESET_EQ);
            KenwoodLink.Out o = k.poll(0);
            int n = KenwoodFrame.parse(o.frame).cmdId[4] & 0xFF;
            T.ok(n != 0xFF, "FF is never used");
            T.ok(n == last + 1 || (last == 0xFE && n == 0), "counts up and wraps from FE to 0");
            last = n;
            k.onFrame(taken(o, 0));
        }
    }

    /** A plain message from the stereo to the app. */
    static byte[] says(int cat, int cmd, int seq, int... payload) {
        byte[] b = new byte[payload.length];
        for (int i = 0; i < b.length; i++) b[i] = (byte) payload[i];
        return KenwoodFrame.frame(new byte[]{0x50, (byte) 0xA4, (byte) cat, (byte) cmd, (byte) seq}, KenwoodFrame.CMD, b);
    }

    public static void theStartUpGoesOnAsTheStockAppDoesAfterTheFeatures() {
        KenwoodLink k = opened();
        k.onFrame(says(0x5B, 1, 1, 0, 0x10, 0, 0));
        KenwoodLink.Out features = k.poll(0);
        k.onFrame(taken(features, 0));
        k.onFrame(says(0x5B, 0x34, 2, 0xFF, 0x11, 0x24, 0x22, 0, 0, 0, 1));
        KenwoodLink.Out version = k.poll(0);
        KenwoodFrame.Parsed v = KenwoodFrame.parse(version.frame);
        T.eq(new byte[]{(byte) 0xA4, 0x50, 0x3B, 0x36, 3}, v.cmdId, "after the features, 3B/36");
        T.eq(new byte[]{0}, v.payload, "with a single 0");
        k.onFrame(taken(version, 0));
        k.onFrame(says(0x5B, 0x36, 3, 4));
        T.eq(4, k.remoteVersion(), "the stereo's answer is its remote-key version");
        KenwoodLink.Out next = k.poll(0);
        KenwoodFrame.Parsed n = KenwoodFrame.parse(next.frame);
        T.eq(new byte[]{(byte) 0xA4, 0x50, 0x3B, 0x37, 4}, n.cmdId, "then 3B/37");
        T.eq(0, n.payload.length, "empty");
        k.onFrame(taken(next, 0));
        k.onFrame(says(0x5B, 0x37, 4, 0));
        KenwoodLink.Out sources = k.poll(0);
        T.eq(new byte[]{(byte) 0xA4, 0x50, 0x3B, 0x12, 5}, KenwoodFrame.parse(sources.frame).cmdId, "then 3B/12, the last of it");
        k.onFrame(taken(sources, 0));
        T.ok(k.idle(), "and the start-up is done");
    }

    public static void commandsWaitWhileTheStartUpIsUnderWay() {
        KenwoodLink k = opened();
        k.onFrame(says(0x5B, 1, 1, 0, 0x10, 0, 0));
        k.onFrame(taken(k.poll(0), 0));
        k.mute(true);
        T.ok(k.poll(100) == null, "held: the stereo's next start-up message is still to come");
        k.onFrame(says(0x5B, 0x34, 2, 0xFF, 0x11, 0x24, 0x22, 0, 0, 0, 1));
        k.onFrame(taken(k.poll(200), 0));
        k.onFrame(says(0x5B, 0x36, 3, 2));
        k.onFrame(taken(k.poll(300), 0));
        k.onFrame(says(0x5B, 0x37, 4, 0));
        KenwoodLink.Out sources = k.poll(400);
        T.ok(k.poll(450) == null, "still held until the last start-up message is taken");
        k.onFrame(taken(sources, 0));
        T.eq(KenwoodCommands.MUTE, KenwoodFrame.parse(k.poll(500).frame).cmdId[3] & 0xFF, "then the command goes");
    }

    public static void aStalledStartUpDoesNotHoldCommandsForEver() {
        KenwoodLink k = opened();
        k.onFrame(says(0x5B, 1, 1, 0, 0x10, 0, 0));
        k.onFrame(taken(k.poll(0), 0));
        k.mute(true);
        T.ok(k.poll(10) == null, "held while the stereo may still answer");      // the link's clock is the poll's
        T.ok(k.poll(10 + KenwoodLink.SETTLE_MS - 1) == null, "still held just short of the limit");
        T.ok(k.poll(10 + KenwoodLink.SETTLE_MS) != null, "let go once it has been quiet long enough");
    }

    public static void theKnobKeysUseTheCodesForTheStereosRemoteVersion() {
        KenwoodLink k = opened();
        k.volumeUp();
        KenwoodLink.Out up = k.poll(0);
        KenwoodFrame.Parsed p = KenwoodFrame.parse(up.frame);
        T.eq(new byte[]{(byte) 0xA4, 0x50, 0x11, 5, 2}, p.cmdId, "a remote key is 11/05");
        T.eq(new byte[]{8}, p.payload, "volume up is key 8 unless the stereo says version 4");
        k.onFrame(taken(up, 0));
        k.volumeDown();
        KenwoodLink.Out down = k.poll(0);
        T.eq(new byte[]{7}, KenwoodFrame.parse(down.frame).payload, "volume down is key 7");
        k.onFrame(taken(down, 0));
        k.att();
        KenwoodLink.Out att = k.poll(0);
        T.eq(new byte[]{12}, KenwoodFrame.parse(att.frame).payload, "ATT is key 12");
        k.onFrame(taken(att, 0));

        k.onFrame(says(0x5B, 0x36, 9, 4));
        k.onFrame(taken(k.poll(0), 0));                           // the 3B/37 that follows
        k.onFrame(says(0x5B, 0x37, 10, 0));
        k.onFrame(taken(k.poll(0), 0));                           // the 3B/12 that ends the start-up
        k.volumeUp();
        k.volumeDown();
        k.att();
        int[] want = {1, 2, 4};
        for (int w : want) {
            KenwoodLink.Out o = k.poll(0);
            T.eq(new byte[]{(byte) w}, KenwoodFrame.parse(o.frame).payload, "version 4 key " + w);
            k.onFrame(taken(o, 0));
        }
    }
}
