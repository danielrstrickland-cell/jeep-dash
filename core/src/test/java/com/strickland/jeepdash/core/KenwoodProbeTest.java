package com.strickland.jeepdash.core;

import java.util.List;

/** The stereo probe and the stream splitter that feeds it. */
public class KenwoodProbeTest {

    public static void probeOpensThenOnlyReads() {
        KenwoodProbe p = new KenwoodProbe();
        int n = 0;
        while (!p.done()) {
            KenwoodProbe.Step s = p.peek();
            KenwoodFrame.Parsed f = KenwoodFrame.parse(p.nextFrame());
            T.ok(f != null, "step " + s.label + " builds a valid frame");
            T.eq(0, f.payload.length, s.label + " carries no payload");
            T.ok(!KenwoodCommands.unconfirmed(s.category, s.cmd), s.label + " is not a setting write");
            T.eq(++n, f.cmdId[4] & 0xFF, "sequence counts up");
        }
        T.eq(p.stepCount(), n, "every step was sent");
        T.ok(p.nextFrame() == null, "nothing after the last step");
    }

    public static void ackOnlyForMessages() {
        byte[] msg = KenwoodFrame.frame(new byte[]{0x50, (byte) 0xA4, 0x41, 14, 3}, KenwoodFrame.CMD, new byte[]{2});
        KenwoodFrame.Parsed ack = KenwoodFrame.parse(KenwoodProbe.ackFor(msg));
        T.eq(KenwoodFrame.ACK, ack.ptype, "a message is acknowledged");
        T.eq(3, ack.cmdId[4] & 0xFF, "with its own sequence");
        T.ok(KenwoodProbe.ackFor(KenwoodProbe.ackFor(msg)) == null, "an ACK is not acknowledged");
        T.ok(KenwoodProbe.ackFor(new byte[]{0x7E, 1, 2, 0x7C}) == null, "nor is junk");
    }

    public static void describeShowsIdsAndPayload() {
        byte[] msg = KenwoodFrame.frame(new byte[]{0x50, (byte) 0xA4, 0x41, 14, 3}, KenwoodFrame.CMD, new byte[]{2, (byte) 0xFF});
        String line = KenwoodProbe.describe(msg);
        T.ok(line.startsWith("MSG from 50 to A4 cat 41 cmd 0E seq 03 len 2: 02 FF"), "readable line: " + line);
        T.ok(KenwoodProbe.describe(new byte[]{0x7E, 9, 0x7C}).startsWith("unreadable frame"), "junk is named");
    }

    public static void streamSplitsAndJoinsFrames() {
        byte[] a = KenwoodCommands.volume(1, 10), b = KenwoodCommands.mute(2, true);
        byte[] both = new byte[a.length + b.length + 2];
        both[0] = 0x11;                                          // a stray byte before the first frame
        System.arraycopy(a, 0, both, 1, a.length);
        System.arraycopy(b, 0, both, 1 + a.length, b.length);
        both[both.length - 1] = 0x22;                            // and one after
        KenwoodStream s = new KenwoodStream();
        List<byte[]> first = s.feed(both, a.length + 3);         // first frame whole, second cut after 2 bytes
        T.eq(1, first.size(), "one whole frame so far");
        T.eq(a, first.get(0), "it is the first frame");
        byte[] rest = new byte[both.length - (a.length + 3)];
        System.arraycopy(both, a.length + 3, rest, 0, rest.length);
        List<byte[]> second = s.feed(rest, rest.length);
        T.eq(1, second.size(), "the split frame completes");
        T.eq(b, second.get(0), "and is intact");
        T.eq(2, s.strayBytes(), "both stray bytes counted");
    }
}
