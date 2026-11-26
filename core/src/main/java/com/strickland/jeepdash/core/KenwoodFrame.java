package com.strickland.jeepdash.core;

import java.io.ByteArrayOutputStream;

/**
 * The wire format of the Kenwood OpenLink protocol the KMM-BT728HD speaks over its Bluetooth serial link —
 * ported from the stock Kenwood remote app so this app can control the head unit directly (EQ, crossover,
 * time alignment, source, volume). Pure byte work with no Android or Bluetooth dependency, so it is unit
 * tested; the service layer owns the RFCOMM socket and hands whole frames to and from here.
 *
 * A message is: {@code 0x0B 0x01  cmdId[5]  ptype 0x01  lenHi lenLo  payload[len]  crcHi crcLo}, with a
 * CRC-16/ARC (poly 0xA001, init 0) over everything up to but not including the CRC. The five id bytes are
 * {@code 0xA4 (OpenLink core)  addr  category  command  sequence}; an ACK echoes the five it answers. The
 * message is then byte-stuffed — 0x7E, 0x7C and 0x7D become 0x7D followed by the byte XOR 0x20 — and sent
 * as {@code 0x7E  stuffed…  0x7C}. (The stock app keeps a two-byte length in front of that in its own buffer,
 * but never writes it to the socket.)
 */
public final class KenwoodFrame {
    public static final int CMD = 0, ACK = 1;      // ptype: a command to the head unit, or an acknowledgement

    private static final int FLAG = 0x7E, END = 0x7C, ESC = 0x7D, XOR = 0x20;
    private static final int[] CRC = crcTable();

    private KenwoodFrame() { }

    private static int[] crcTable() {
        int[] t = new int[256];
        for (int n = 0; n < 256; n++) {
            int c = n;
            for (int k = 0; k < 8; k++) c = (c & 1) != 0 ? (c >>> 1) ^ 0xA001 : c >>> 1;
            t[n] = c;
        }
        return t;
    }

    /** CRC-16/ARC over the first {@code len} bytes, init 0, as the head unit computes it. */
    public static int crc16(byte[] data, int len) {
        int c = 0;
        for (int i = 0; i < len; i++) c = CRC[(c ^ data[i]) & 0xFF] ^ (c >>> 8);
        return c & 0xFFFF;
    }

    /** A complete frame ready to write to the socket, for a command with the given 5-byte id and payload. */
    public static byte[] frame(byte[] cmdId, int ptype, byte[] payload) {
        if (cmdId == null || cmdId.length != 5) throw new IllegalArgumentException("cmdId must be 5 bytes");
        int plen = payload == null ? 0 : payload.length;
        byte[] msg = new byte[plen + 13];
        msg[0] = 0x0B;
        msg[1] = 0x01;
        System.arraycopy(cmdId, 0, msg, 2, 5);
        msg[7] = (byte) ptype;
        msg[8] = 0x01;
        msg[9] = (byte) ((plen >> 8) & 0xFF);
        msg[10] = (byte) (plen & 0xFF);
        if (plen > 0) System.arraycopy(payload, 0, msg, 11, plen);
        int crc = crc16(msg, plen + 11);
        msg[plen + 11] = (byte) ((crc >> 8) & 0xFF);
        msg[plen + 12] = (byte) (crc & 0xFF);

        byte[] stuffed = stuff(msg);
        byte[] out = new byte[stuffed.length + 2];     // 0x7E + stuffed + 0x7C
        out[0] = (byte) FLAG;
        System.arraycopy(stuffed, 0, out, 1, stuffed.length);
        out[out.length - 1] = (byte) END;
        return out;
    }

    /** What a received frame carried, once unwrapped and checked. */
    public static final class Parsed {
        public final int ptype;
        public final byte[] cmdId;
        public final byte[] payload;
        Parsed(int ptype, byte[] cmdId, byte[] payload) {
            this.ptype = ptype; this.cmdId = cmdId; this.payload = payload;
        }
    }

    /** Unwraps and verifies a whole frame, or returns null if it is malformed or the CRC does not match. */
    public static Parsed parse(byte[] in) {
        if (in == null || in.length < 3 || (in[0] & 0xFF) != FLAG || (in[in.length - 1] & 0xFF) != END)
            return null;
        byte[] msg = unstuff(in, 1, in.length - 1);    // between the 0x7E and the 0x7C
        if (msg == null || msg.length < 13) return null;
        int plen = ((msg[9] & 0xFF) << 8) | (msg[10] & 0xFF);
        if (msg.length != plen + 13) return null;
        int want = ((msg[plen + 11] & 0xFF) << 8) | (msg[plen + 12] & 0xFF);
        if (crc16(msg, plen + 11) != want) return null;
        byte[] cmdId = new byte[5];
        System.arraycopy(msg, 2, cmdId, 0, 5);
        byte[] payload = new byte[plen];
        System.arraycopy(msg, 11, payload, 0, plen);
        return new Parsed(msg[7] & 0xFF, cmdId, payload);
    }

    static byte[] stuff(byte[] msg) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(msg.length + 8);
        for (byte value : msg) {
            int b = value & 0xFF;
            if (b == FLAG || b == END || b == ESC) {
                out.write(ESC);
                out.write(b ^ XOR);
            } else {
                out.write(b);
            }
        }
        return out.toByteArray();
    }

    static byte[] unstuff(byte[] in, int from, int to) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(to - from);
        for (int i = from; i < to; i++) {
            int b = in[i] & 0xFF;
            if (b == ESC) {
                if (++i >= to) return null;
                out.write((in[i] & 0xFF) ^ XOR);
            } else {
                out.write(b);
            }
        }
        return out.toByteArray();
    }
}
