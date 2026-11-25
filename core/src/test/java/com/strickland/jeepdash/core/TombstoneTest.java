package com.strickland.jeepdash.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public class TombstoneTest {
    // a tiny protobuf writer, enough to build a tombstone the way Android's debuggerd lays it out
    private static final class Pb {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Pb varint(int field, long v) {
            raw((field << 3));
            raw(v);
            return this;
        }

        Pb str(int field, String s) { return bytes(field, s.getBytes(StandardCharsets.UTF_8)); }

        Pb msg(int field, Pb m) { return bytes(field, m.out.toByteArray()); }

        Pb bytes(int field, byte[] b) {
            raw((field << 3) | 2);
            raw(b.length);
            out.write(b, 0, b.length);
            return this;
        }

        private void raw(long v) {
            while ((v & ~0x7fL) != 0) {
                out.write((int) ((v & 0x7f) | 0x80));
                v >>>= 7;
            }
            out.write((int) v);
        }
    }

    private static Pb frame(long relPc, String fn, long off, String file) {
        return new Pb().varint(1, relPc).varint(2, relPc + 0x7000).str(4, fn).varint(5, off).str(6, file).str(8, "abc");
    }

    private static byte[] tombstone() {
        Pb crashing = new Pb().varint(1, 101).str(2, "RenderThread")
                .msg(4, frame(0x5a1c, "abort", 164, "/apex/com.android.runtime/lib64/bionic/libc.so"))
                .msg(4, frame(0x12f0, "", 0, "/data/app/x/lib/arm64/libfilament-jni.so"));
        Pb idle = new Pb().varint(1, 100).str(2, "binder:100_1")
                .msg(4, frame(0x10, "__ioctl", 8, "/apex/com.android.runtime/lib64/bionic/libc.so"));
        Pb log = new Pb().str(1, "main")
                .msg(2, new Pb().str(1, "11-25 10:42:52.300").varint(2, 100).varint(3, 101).varint(4, 6)
                        .str(5, "Filament").str(6, "Precondition in setImage:242 reason: buffer too small"))
                .msg(2, new Pb().str(1, "11-25 10:42:52.301").varint(2, 100).varint(3, 101).varint(4, 4)
                        .str(5, "Filament").str(6, "in thread RenderThread"));
        return new Pb().str(2, "Lenovo/TB361FU").varint(5, 100).varint(6, 101).str(9, "com.strickland.jeepdash")
                .msg(10, new Pb().varint(1, 6).str(2, "SIGABRT").varint(3, -6).str(4, "SI_QUEUE"))
                .str(14, "Filament panic")
                .msg(16, new Pb().varint(1, 100).msg(2, idle))
                .msg(16, new Pb().varint(1, 101).msg(2, crashing))
                .msg(18, log)
                .out.toByteArray();
    }

    public static void summaryLeadsWithSignalAbortAndTheCrashingThread() {
        String s = Tombstone.summary(tombstone(), 20000);
        T.ok(s != null, "parsed");
        T.ok(s.contains("Signal: SIGABRT (SI_QUEUE)"), "signal: " + s);
        T.ok(s.contains("Abort message: Filament panic"), "abort message");
        T.ok(s.contains("Crashing thread 101 \"RenderThread\""), "the crashing thread by tid, not the first in the map");
        T.ok(s.indexOf("#00 pc 0000000000005a1c  /apex/com.android.runtime/lib64/bionic/libc.so (abort+164)") > 0, "frame 0: " + s);
        T.ok(s.contains("#01 pc 00000000000012f0  /data/app/x/lib/arm64/libfilament-jni.so"), "frame 1 without a name");
        T.ok(s.contains("E Filament: Precondition in setImage:242 reason: buffer too small"), "the app's log lines");
        T.ok(s.indexOf("Crashing thread") < s.indexOf("Precondition"), "thread before log");
        T.ok(!s.contains("__ioctl"), "idle threads left out");
    }

    public static void garbageIsNotATombstone() {
        T.eq(null, Tombstone.summary("not a protobuf at all".getBytes(StandardCharsets.UTF_8), 1000), "text");
        T.eq(null, Tombstone.summary(new byte[0], 1000), "empty");
    }

    public static void summaryIsCapped() {
        T.ok(Tombstone.summary(tombstone(), 120).length() <= 120, "capped");
    }
}
