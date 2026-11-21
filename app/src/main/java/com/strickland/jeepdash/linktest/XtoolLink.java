package com.strickland.jeepdash.linktest;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Talks to the GearWrench GWSMARTBT (XTOOL hardware) over Bluetooth Classic serial.
 *
 * Frame (worked out from an iPhone capture):
 *   TX: 55 aa | seq(2) | len(2) | ~len(2) | payload | ck     RX: same with aa 55
 *   payload = 01 | innerLen(2) | inner
 *   ck = NOT(XOR of every byte after the 2-byte magic)
 * The dongle answers each frame with an ack (00 00 02 00 00) and, for queries,
 * a reply (01 | len(2) | data) with the same seq; 01 ff ff = vehicle did not answer.
 */
public class XtoolLink {

    public interface Logger { void log(String line); }

    public static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");

    public static class Result {
        public boolean acked;
        public boolean timedOut;
        public boolean noResponse;
        public byte[] data;
    }

    private static class Pending {
        final boolean wantReply;
        final CountDownLatch latch = new CountDownLatch(1);
        final Result result = new Result();
        Pending(boolean wantReply) { this.wantReply = wantReply; }
    }

    private final Logger logger;
    private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
    private BluetoothSocket socket;
    private InputStream in;
    private OutputStream out;
    private Thread reader;
    private volatile boolean open;
    private int seq = 0;

    // receive buffer
    private byte[] buf = new byte[4096];
    private int bufLen = 0;
    private volatile boolean sawFrame = false;
    private int rawLogged = 0;
    public volatile boolean showRaw = false;

    public XtoolLink(Logger logger) { this.logger = logger; }

    public boolean isOpen() { return open; }

    // ---------------- connection ----------------

    public void connect(BluetoothDevice dev) throws IOException {
        IOException last = null;
        String[] modes = {"standard serial (secure)", "standard serial (insecure)", "serial channel 4"};
        for (int i = 0; i < modes.length && socket == null; i++) {
            BluetoothSocket s = null;
            try {
                logger.log("Trying " + modes[i] + "...");
                if (i == 0) s = dev.createRfcommSocketToServiceRecord(SPP);
                else if (i == 1) s = dev.createInsecureRfcommSocketToServiceRecord(SPP);
                else {
                    Method m = dev.getClass().getMethod("createRfcommSocket", int.class);
                    s = (BluetoothSocket) m.invoke(dev, 4);
                }
                s.connect();
                socket = s;
                logger.log("Connected using " + modes[i] + ".");
            } catch (IOException e) {
                last = e;
                logger.log("  failed: " + e.getMessage());
                closeQuietly(s);
            } catch (Exception e) {
                last = new IOException(e.toString());
                logger.log("  failed: " + e);
                closeQuietly(s);
            }
        }
        if (socket == null) throw last != null ? last : new IOException("could not connect");
        in = socket.getInputStream();
        out = socket.getOutputStream();
        open = true;
        reader = new Thread(this::readLoop, "xtool-reader");
        reader.setDaemon(true);
        reader.start();
    }

    public void close() {
        open = false;
        closeQuietly(socket);
        socket = null;
    }

    private static void closeQuietly(BluetoothSocket s) {
        if (s == null) return;
        try { s.close(); } catch (IOException ignored) { }
    }

    private void readLoop() {
        byte[] chunk = new byte[1024];
        try {
            while (open) {
                int n = in.read(chunk);
                if (n < 0) break;
                if (n == 0) continue;
                if (showRaw || (!sawFrame && rawLogged < 60)) {
                    rawLogged++;
                    logger.log("raw " + hex(chunk, 0, n));
                }
                push(chunk, n);
            }
        } catch (IOException e) {
            if (open) logger.log("Connection lost: " + e.getMessage());
        }
        open = false;
        logger.log("Disconnected.");
    }

    // ---------------- framing ----------------

    static byte[] buildFrame(int seq, byte[] inner) {
        int n = inner.length + 3;
        int nn = (~n) & 0xffff;
        byte[] f = new byte[2 + 6 + n + 1];
        f[0] = 0x55; f[1] = (byte) 0xaa;
        f[2] = (byte) (seq >> 8); f[3] = (byte) seq;
        f[4] = (byte) (n >> 8); f[5] = (byte) n;
        f[6] = (byte) (nn >> 8); f[7] = (byte) nn;
        f[8] = 0x01; f[9] = (byte) (inner.length >> 8); f[10] = (byte) inner.length;
        System.arraycopy(inner, 0, f, 11, inner.length);
        int x = 0;
        for (int i = 2; i < f.length - 1; i++) x ^= (f[i] & 0xff);
        f[f.length - 1] = (byte) (~x);
        return f;
    }

    private synchronized void push(byte[] data, int n) {
        if (bufLen + n > buf.length) {
            byte[] nb = new byte[Math.max(buf.length * 2, bufLen + n)];
            System.arraycopy(buf, 0, nb, 0, bufLen);
            buf = nb;
        }
        System.arraycopy(data, 0, buf, bufLen, n);
        bufLen += n;
        while (true) {
            int k = -1;
            for (int i = 0; i + 1 < bufLen; i++) {
                if ((buf[i] & 0xff) == 0xaa && (buf[i + 1] & 0xff) == 0x55) { k = i; break; }
            }
            if (k < 0) { if (bufLen > 1) { buf[0] = buf[bufLen - 1]; bufLen = 1; } return; }
            if (k > 0) drop(k);
            if (bufLen < 8) return;
            int s = ((buf[2] & 0xff) << 8) | (buf[3] & 0xff);
            int len = ((buf[4] & 0xff) << 8) | (buf[5] & 0xff);
            int nlen = ((buf[6] & 0xff) << 8) | (buf[7] & 0xff);
            if ((len ^ nlen) != 0xffff) { drop(2); continue; }
            if (bufLen < 8 + len + 1) return;
            int x = 0;
            for (int i = 2; i < 8 + len; i++) x ^= (buf[i] & 0xff);
            boolean ckOk = ((~x) & 0xff) == (buf[8 + len] & 0xff);
            byte[] payload = new byte[len];
            System.arraycopy(buf, 8, payload, 0, len);
            drop(8 + len + 1);
            onFrame(s, payload, ckOk);
        }
    }

    private void drop(int n) {
        System.arraycopy(buf, n, buf, 0, bufLen - n);
        bufLen -= n;
    }

    private void onFrame(int s, byte[] p, boolean ckOk) {
        sawFrame = true;
        if (!ckOk) logger.log("RX seq " + s + " BAD CHECKSUM " + hex(p, 0, p.length));
        Pending pd = pending.get(s);
        boolean isAck = p.length == 5 && p[0] == 0 && p[1] == 0 && p[2] == 2;
        if (isAck) {
            if (pd != null) {
                pd.result.acked = true;
                if (!pd.wantReply) { pending.remove(s); pd.latch.countDown(); }
            }
            return;
        }
        if (p.length >= 3 && p[0] == 1) {
            boolean noResp = (p[1] & 0xff) == 0xff && (p[2] & 0xff) == 0xff;
            byte[] data = null;
            if (!noResp) {
                int dl = Math.min(((p[1] & 0xff) << 8) | (p[2] & 0xff), p.length - 3);
                data = new byte[dl];
                System.arraycopy(p, 3, data, 0, dl);
            }
            if (pd != null) {
                pd.result.acked = true;
                pd.result.noResponse = noResp;
                pd.result.data = data;
                pending.remove(s);
                pd.latch.countDown();
            }
            return;
        }
        logger.log("RX seq " + s + " other: " + hex(p, 0, p.length));
    }

    // ---------------- requests ----------------

    public Result send(String innerHex, boolean wantReply, int timeoutMs, boolean quiet) throws IOException {
        byte[] inner = unhex(innerHex);
        int mySeq;
        synchronized (this) { seq = (seq % 0xffff) + 1; mySeq = seq; }
        Pending pd = new Pending(wantReply);
        pending.put(mySeq, pd);
        if (!quiet) logger.log("TX " + mySeq + ": " + innerHex);
        out.write(buildFrame(mySeq, inner));
        out.flush();
        try {
            if (!pd.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                pending.remove(mySeq);
                pd.result.timedOut = true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Result r = pd.result;
        if (!quiet) {
            if (r.data != null) logger.log("RX " + mySeq + ": " + hex(r.data, 0, r.data.length));
            else if (r.noResponse) logger.log("RX " + mySeq + ": (vehicle did not answer)");
            else if (r.timedOut) logger.log("   " + mySeq + ": " + (r.acked ? "acked, no reply" : "NO ANSWER FROM DONGLE"));
        }
        return r;
    }

    // ---------------- helpers ----------------

    public static String hex(byte[] a, int off, int n) {
        StringBuilder sb = new StringBuilder(n * 3);
        for (int i = off; i < off + n; i++) {
            if (i > off) sb.append(' ');
            sb.append(String.format("%02x", a[i] & 0xff));
        }
        return sb.toString();
    }

    public static byte[] unhex(String s) {
        String[] parts = s.trim().split("\\s+");
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = (byte) Integer.parseInt(parts[i], 16);
        return out;
    }
}
