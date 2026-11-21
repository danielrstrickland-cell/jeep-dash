package com.strickland.jeepdash.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public class DongleSessionTest {

    /** Fake dongle: records writes and answers each frame with the payloads its responder returns. */
    static final class FakeLink implements ByteLink {
        DongleSession session;
        final List<byte[]> writes = new ArrayList<>();
        Function<Integer, String[]> responder = seq -> new String[0];
        boolean async = false;

        @Override public void write(byte[] b) {
            writes.add(b);
            int seq = ((b[2] & 0xff) << 8) | (b[3] & 0xff);
            String[] payloads = responder.apply(seq);
            Runnable answer = () -> {
                for (String p : payloads) { byte[] f = rx(seq, p); session.onBytes(f, f.length); }
            };
            if (async) new Thread(() -> { sleep(20); answer.run(); }).start(); else answer.run();
        }
        @Override public void close() { }
    }

    static byte[] rx(int seq, String payloadHex) {
        byte[] p = Hex.bytes(payloadHex);
        int n = p.length, nn = (~n) & 0xffff;
        byte[] f = new byte[8 + n + 1];
        f[0] = (byte) 0xaa; f[1] = 0x55;
        f[2] = (byte) (seq >> 8); f[3] = (byte) seq; f[4] = (byte) (n >> 8); f[5] = (byte) n;
        f[6] = (byte) (nn >> 8); f[7] = (byte) nn;
        System.arraycopy(p, 0, f, 8, n);
        int x = 0;
        for (int i = 2; i < 8 + n; i++) x ^= (f[i] & 0xff);
        f[8 + n] = (byte) ~x;
        return f;
    }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    static FakeLink link(Function<Integer, String[]> responder) {
        FakeLink l = new FakeLink();
        l.responder = responder;
        l.session = new DongleSession(l);
        return l;
    }

    static final String ACK = "00 00 02 00 00";

    public static void ackOnlyCompletes() throws Exception {
        FakeLink l = link(seq -> new String[]{ACK});
        DongleSession.Result r = l.session.send(Hex.bytes("60 05 19 01"), false, 1000);
        T.ok(r.acked, "acked");
        T.ok(!r.timedOut, "not timed out");
        T.eq(null, r.data, "no data");
    }

    public static void replyCarriesData() throws Exception {
        FakeLink l = link(seq -> new String[]{ACK, "01 00 02 16 91"});
        l.async = true;
        DongleSession.Result r = l.session.send(Hex.bytes("61 04 00 02 f4 16 01"), true, 1000);
        T.eq("16 91", r.data == null ? null : Hex.str(r.data), "reply data");
        T.ok(!r.noResponse, "not a no-response");
    }

    public static void noResponseFlagged() throws Exception {
        FakeLink l = link(seq -> new String[]{ACK, "01 ff ff"});
        DongleSession.Result r = l.session.send(Hex.bytes("61 04 00 02 f4 16 01"), true, 1000);
        T.ok(r.noResponse, "no-response flagged");
        T.eq(null, r.data, "no data");
    }

    public static void timeoutWithoutAnswer() throws Exception {
        FakeLink l = link(seq -> new String[0]);
        long t0 = System.nanoTime();
        DongleSession.Result r = l.session.send(Hex.bytes("61 04 00 02 f4 16 01"), true, 200);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        T.ok(r.timedOut, "timed out");
        T.ok(ms < 400, "returned within 400 ms, took " + ms);
    }

    public static void closeFailsPending() throws Exception {
        FakeLink l = link(seq -> new String[0]);
        DongleSession.Result[] box = new DongleSession.Result[1];
        Thread t = new Thread(() -> {
            try { box[0] = l.session.send(Hex.bytes("61 04 00 02 f4 16 01"), true, 5000); } catch (Exception ignored) { }
        });
        t.start();
        sleep(100);
        long t0 = System.nanoTime();
        l.session.close();
        t.join(1000);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        T.ok(box[0] != null && box[0].closed, "pending send reports closed");
        T.ok(ms < 300, "returned within 300 ms of close, took " + ms);
    }

    public static void seqIncrementsAndWraps() throws Exception {
        FakeLink l = link(seq -> new String[]{ACK});
        for (int i = 0; i < 0xffff; i++) l.session.send(Hex.bytes("60 06 ff"), false, 1000);
        T.eq(1, seqOf(l.writes.get(0)), "first seq is 1");
        T.eq(0xffff, seqOf(l.writes.get(0xfffe)), "65535th seq is 0xffff");
        l.session.send(Hex.bytes("60 06 ff"), false, 1000);
        T.eq(1, seqOf(l.writes.get(0xffff)), "wraps back to 1");
    }

    static int seqOf(byte[] f) { return ((f[2] & 0xff) << 8) | (f[3] & 0xff); }
}
