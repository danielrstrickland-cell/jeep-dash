package com.strickland.jeepdash;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.SystemClock;
import com.strickland.jeepdash.core.DiagLog;
import com.strickland.jeepdash.core.KenwoodLink;
import com.strickland.jeepdash.core.KenwoodProbe;
import com.strickland.jeepdash.core.KenwoodStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The live link to the Kenwood KMM-BT728HD, used by the Sound hub and by the stereo probe. It gets the serial
 * link the way the stock app does ({@link StereoSocket}), then runs the conversation {@link KenwoodLink}
 * describes: it acknowledges what the stereo says, starts the session when the stereo says hello, and sends
 * one message at a time, again each second until the stereo takes it. Every frame either way goes in the log.
 * If the link ends it waits for the stereo to call the tablet. The caller holds the Bluetooth permission.
 */
final class StereoLink {
    /** Where the link's log lines go: the level is one of {@link DiagLog}'s. */
    interface Sink { void line(int level, String tag, String text); }

    private static final long SILENT_MS = 3000, TICK_MS = 50;

    private final Sink sink;
    private volatile KenwoodLink link = new KenwoodLink();
    private volatile BluetoothSocket socket;
    private volatile OutputStream out;
    private volatile boolean closing, connecting;
    private volatile StereoSocket sock;            // the connect in hand
    private volatile int run;                      // each connect is its own run; an older one stands down
    private volatile String state = "Not connected.", last = "";

    /** Logs to the app's diagnostics log. */
    StereoLink() { this((level, tag, text) -> Diag.LOG.add(Diag.now(), level, tag, text)); }

    StereoLink(Sink sink) { this.sink = sink; }

    private void info(String tag, String text) { sink.line(DiagLog.INFO, tag, text); }
    private void warn(String tag, String text) { sink.line(DiagLog.WARN, tag, text); }
    private void error(String tag, String text) { sink.line(DiagLog.ERROR, tag, text); }

    /** One line for the screen: the link's state and the last command's fate. */
    String state() { return last.isEmpty() ? state : state + "  " + last; }

    /** True while a serial link to the stereo is up, whether or not the session is open yet. */
    boolean connected() { return out != null; }

    /** True once the stereo has opened the session: commands go out from then on. */
    boolean ready() { return connected() && link.open(); }

    /** True when nothing is waiting to be sent or acknowledged. */
    boolean idle() { return link.idle(); }

    boolean busy() { return connecting; }

    void connect(final BluetoothAdapter adapter, final BluetoothDevice device, final String name) {
        if (connecting || connected()) return;
        closing = false;
        connecting = true;
        last = "";
        state = "Calling " + name + "...";
        final int mine = ++run;
        Thread t = new Thread(() -> run(adapter, device, name, mine), "stereo-link");
        t.setDaemon(true);
        t.start();
    }

    private boolean over(int mine) { return closing || run != mine; }

    private void run(BluetoothAdapter adapter, BluetoothDevice device, String name, int mine) {
        final StereoSocket k = new StereoSocket();
        sock = k;
        // if the stereo calls while the link is on the tablet's own call, give way to the stereo's, as the stock app does
        k.onCaller(() -> {
            BluetoothSocket cur = socket;
            if (!over(mine) && k.dialled(cur)) {
                info(DiagLog.BT, "Dropping the tablet's own call for the stereo's.");
                drop(cur);
            }
        });
        try {
            BluetoothSocket s = k.first(adapter, device, text -> warn(DiagLog.BT, text));
            while (s != null && !over(mine)) {
                session(s, k.dialled(s), name, mine);
                if (over(mine)) break;
                connecting = true;
                last = "";
                state = "The link to " + name + " ended. Waiting for the stereo to call this tablet. " + StereoSocket.HOW;
                s = k.next();
            }
            if (s != null) closeQuietly(s);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            k.cancel();
        }
        if (over(mine)) return;
        connecting = false;
        state = "Could not reach the stereo. Is Bluetooth on, and the stereo's REMOTE APP set to ANDROID?";
    }

    private static void closeQuietly(BluetoothSocket s) {
        try { if (s != null) s.close(); } catch (IOException ignored) { }
    }

    /** Ends the link on this socket, if it is still the one in use, and closes the socket either way. */
    private void drop(BluetoothSocket s) {
        if (s != null && socket == s) {
            out = null;
            socket = null;
        }
        closeQuietly(s);
    }

    /** Runs one link until it ends. */
    private void session(final BluetoothSocket s, boolean dialled, final String name, final int mine) {
        InputStream in;
        final OutputStream o;
        try {
            in = s.getInputStream();
            o = s.getOutputStream();
        } catch (IOException e) {
            error(DiagLog.BT, "Stereo link has no streams: " + e.getMessage());
            closeQuietly(s);
            return;
        }
        final KenwoodLink k = new KenwoodLink();
        final AtomicBoolean heard = new AtomicBoolean();
        link = k;
        socket = s;
        out = o;
        connecting = false;
        last = "";
        state = "Linked to " + name + ". Starting the session...";
        info(DiagLog.BT, "Stereo link up (" + (dialled ? "the stereo took the tablet's call" : "the stereo called the tablet")
                + "): " + name);
        Thread pump = new Thread(() -> pump(k, s, o, heard, name, mine), "stereo-send");
        pump.setDaemon(true);
        pump.start();
        readLoop(in, o, k, heard, mine);
        drop(s);
    }

    /** Writes whatever the conversation has to send, when it has it, until this link ends. */
    private void pump(KenwoodLink k, BluetoothSocket s, OutputStream o, AtomicBoolean heard, String name, int mine) {
        final long up = SystemClock.elapsedRealtime();
        boolean prompted = false, opened = false;
        try {
            while (!over(mine) && socket == s) {
                long now = SystemClock.elapsedRealtime();
                if (!prompted && !heard.get() && now - up >= SILENT_MS) {
                    prompted = true;
                    warn(DiagLog.BT, "The stereo has said nothing for 3 seconds. Starting the session from this side.");
                    k.begin();
                }
                if (!opened && k.open()) {
                    opened = true;
                    state = "Connected to " + name + ".";
                    info(DiagLog.BT, "Session open: the stereo took the opener.");
                }
                KenwoodLink.Out m = k.poll(now);
                if (m != null) {
                    if (!write(o, m.frame)) break;
                    info(DiagLog.CMD, "> " + m.label + (m.attempt > 1 ? " (again, try " + m.attempt + ")" : "") + ": "
                            + KenwoodProbe.describe(m.frame) + "  [" + KenwoodProbe.hex(m.frame) + "]");
                    if (m.attempt == 1) last = m.label + ": sent...";
                } else if (k.lost()) {
                    warn(DiagLog.BT, "The stereo left a message unacknowledged for ten seconds. Hanging up, as the stock app does.");
                    break;
                }
                Thread.sleep(TICK_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!over(mine) && socket == s) drop(s);       // a failed write or a lost link: end it, which ends the reader too
    }

    private void readLoop(InputStream in, OutputStream o, KenwoodLink k, AtomicBoolean heard, int mine) {
        KenwoodStream stream = new KenwoodStream();
        byte[] buf = new byte[1024];
        try {
            int n;
            while (!over(mine) && (n = in.read(buf)) > 0) {
                heard.set(true);
                for (byte[] frame : stream.feed(buf, n)) {
                    KenwoodLink.Event e = k.onFrame(frame);
                    info(DiagLog.CMD, "< " + e.line);
                    if (e.ack != null && write(o, e.ack))
                        info(DiagLog.CMD, "> acknowledged  [" + KenwoodProbe.hex(e.ack) + "]");
                    if (e.answered == null) continue;
                    if (e.result == 0) {
                        last = e.answered + ": accepted.";
                    } else {
                        last = e.answered + ": the stereo answered with code " + e.result + ".";
                        warn(DiagLog.CMD, last);
                    }
                }
            }
        } catch (IOException e) {
            if (!over(mine)) warn(DiagLog.BT, "Stereo link dropped" + (heard.get() ? "" : " before the stereo said anything")
                    + ": " + e.getMessage());
        }
        if (stream.strayBytes() > 0)
            warn(DiagLog.BT, stream.strayBytes() + " bytes from the stereo arrived outside any frame.");
    }

    private boolean write(OutputStream o, byte[] frame) {
        try {
            synchronized (o) {
                o.write(frame);
                o.flush();
            }
            return true;
        } catch (IOException e) {
            error(DiagLog.CMD, "Send to the stereo failed: " + e.getMessage());
            return false;
        }
    }

    // Commands wait in the conversation until the session is open, then go out one at a time.

    void mute(boolean on) { link.mute(on); }

    /** Held to the stereo's scale, 0 to {@link KenwoodLink#VOLUME_MAX}. */
    void volume(int level) { link.volume(level); }

    void presetEq(int index) { link.presetEq(index); }

    /** Any read request, by its numbers: the probe's questions come this way. */
    void ask(String label, int category, int cmd) { link.ask(label, category, cmd); }

    void close() {
        closing = true;
        connecting = false;
        StereoSocket k = sock;
        if (k != null) k.cancel();
        drop(socket);
        state = "Not connected.";
        last = "";
    }
}
