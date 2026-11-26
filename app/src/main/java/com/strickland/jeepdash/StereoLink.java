package com.strickland.jeepdash;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.strickland.jeepdash.core.DiagLog;
import com.strickland.jeepdash.core.KenwoodLink;
import com.strickland.jeepdash.core.KenwoodProbe;
import com.strickland.jeepdash.core.KenwoodStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Sound hub's live link to the Kenwood KMM-BT728HD, on the same Bluetooth serial service the stereo
 * probe uses. It calls the stereo as the stock app does and, if the stereo takes the call and hangs up
 * without a word, waits for the stereo to call the tablet instead ({@link StereoSocket}). Once a link is up
 * it opens the session, sends the commands {@link KenwoodLink} hands out (mute, volume, preset EQ),
 * acknowledges whatever the stereo says, and puts every frame either way in the diagnostics log. Open only
 * while the Sound hub is showing. The caller holds the Bluetooth permission.
 */
final class StereoLink {
    private static final long ANSWER_MS = 2500, QUIET_MS = 1500, QUICK_HANGUP_MS = 5000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stereo-send");
        t.setDaemon(true);
        return t;
    });
    private volatile KenwoodLink link = new KenwoodLink();
    private volatile BluetoothSocket socket;
    private volatile OutputStream out;
    private volatile boolean closing, connecting;
    private volatile StereoSocket waiter;          // while waiting for the stereo to call
    private volatile int run;                      // each connect is its own run; an older one stands down
    private volatile String state = "Not connected.", last = "";
    private volatile SharedPreferences prefs;      // where the acknowledgement style the stereo accepts is kept
    private volatile long heardAt;                 // when the stereo last sent anything on this link

    /** Gives the link somewhere to remember what it learns about the stereo between connections. */
    void remember(SharedPreferences p) { prefs = p; }

    /** One line for the screen: the link's state and the last command's fate. */
    String state() { return last.isEmpty() ? state : state + "  " + last; }

    boolean connected() { return out != null; }

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
        StereoSocket.Log log = text -> Diag.warn(DiagLog.BT, text);

        // first the way the stock app does it: the tablet calls the stereo
        BluetoothSocket s = StereoSocket.call(adapter, device, log);
        if (over(mine)) {
            closeQuietly(s);
            return;
        }
        if (s != null) {
            long up = SystemClock.elapsedRealtime();
            boolean heard = session(s, name, mine, "the tablet's call was taken");
            if (over(mine)) return;
            if (heard || SystemClock.elapsedRealtime() - up > QUICK_HANGUP_MS) {
                connecting = false;
                state = "The stereo hung up. Tap Connect to try again.";
                return;
            }
            Diag.warn(DiagLog.BT, "The stereo took the call and hung up without a word. Waiting for it to call instead.");
        }

        // then the other way round: wait for the stereo to call the tablet
        connecting = true;
        state = "Waiting for " + name + " to call this tablet. " + StereoSocket.HOW;
        StereoSocket w = new StereoSocket();
        waiter = w;
        s = w.await(adapter, device, log);
        waiter = null;
        if (over(mine)) {
            closeQuietly(s);
            return;
        }
        if (s == null) {
            connecting = false;
            state = "Could not listen for the stereo. Is Bluetooth on?";
            return;
        }
        session(s, name, mine, "the stereo called in");
        if (over(mine)) return;
        connecting = false;
        state = "The stereo hung up. Tap Connect to try again.";
    }

    private static void closeQuietly(BluetoothSocket s) {
        try { if (s != null) s.close(); } catch (IOException ignored) { }
    }

    /** Runs one link until it ends. True if the stereo sent anything at all over it. */
    private boolean session(BluetoothSocket s, String name, final int mine, String how) {
        InputStream in;
        OutputStream o;
        try {
            in = s.getInputStream();
            o = s.getOutputStream();
        } catch (IOException e) {
            Diag.error(DiagLog.BT, "Stereo link has no streams: " + e.getMessage());
            closeQuietly(s);
            return false;
        }
        SharedPreferences p = prefs;
        link = new KenwoodLink(p == null ? 0 : p.getInt(SoundPrefs.ACK_STYLE, 0));
        heardAt = SystemClock.elapsedRealtime();
        socket = s;
        out = o;
        connecting = false;
        last = "";
        state = "Connected to " + name + ".";
        Diag.info(DiagLog.BT, "Stereo link up (" + how + "): " + name);
        // The stereo speaks first (a hello it repeats every second until it is acknowledged the way it wants).
        // Open the session, as the stock app does, only once it has gone quiet: that is, once it is satisfied.
        final BluetoothSocket mineNow = s;
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (over(mine) || socket != mineNow) return;
                if (SystemClock.elapsedRealtime() - heardAt < QUIET_MS) {
                    main.postDelayed(this, 300);
                    return;
                }
                io.execute(() -> {
                    if (!over(mine) && socket == mineNow) send(link.open());
                });
            }
        }, QUIET_MS);
        boolean heard = readLoop(in, mine);
        if (!over(mine)) drop();
        return heard;
    }

    private boolean readLoop(InputStream in, int mine) {
        KenwoodStream stream = new KenwoodStream();
        KenwoodLink k = link;
        byte[] buf = new byte[1024];
        boolean heard = false;
        try {
            int n;
            while (!over(mine) && (n = in.read(buf)) > 0) {
                heard = true;
                heardAt = SystemClock.elapsedRealtime();
                for (byte[] frame : stream.feed(buf, n)) {
                    KenwoodLink.Event e = k.onFrame(frame);
                    Diag.info(DiagLog.CMD, "< " + e.line);
                    if (e.ack != null) write(e.ack);
                    SharedPreferences p = prefs;
                    if (k.ackStyleConfirmed() && p != null && p.getInt(SoundPrefs.ACK_STYLE, -1) != k.ackStyle()) {
                        p.edit().putInt(SoundPrefs.ACK_STYLE, k.ackStyle()).apply();
                        Diag.info(DiagLog.BT, "The stereo accepts acknowledgements this way: "
                                + KenwoodLink.styleName(k.ackStyle()) + ". Remembered.");
                    }
                    if (e.answered == null) continue;
                    if (e.result == 0) {
                        last = e.answered + ": accepted.";
                    } else {
                        last = e.answered + ": the stereo answered with code " + e.result + ".";
                        Diag.warn(DiagLog.CMD, last);
                    }
                }
            }
        } catch (IOException e) {
            if (!over(mine)) Diag.warn(DiagLog.BT, "Stereo link dropped" + (heard ? "" : " before the stereo said anything")
                    + ": " + e.getMessage());
        }
        if (stream.strayBytes() > 0)
            Diag.warn(DiagLog.BT, stream.strayBytes() + " bytes from the stereo arrived outside any frame.");
        return heard;
    }

    private boolean write(byte[] frame) {
        OutputStream o = out;
        if (o == null) return false;
        try {
            synchronized (o) {
                o.write(frame);
                o.flush();
            }
            return true;
        } catch (IOException e) {
            Diag.error(DiagLog.CMD, "Send to the stereo failed: " + e.getMessage());
            return false;
        }
    }

    /** Writes a command and, if the stereo has not answered it in time, says so. */
    private void send(final KenwoodLink.Sent s) {
        final KenwoodLink k = link;
        if (!write(s.frame)) {
            k.giveUp(s.seq);
            last = s.label + ": not sent.";
            return;
        }
        Diag.info(DiagLog.CMD, "> " + s.label + ": " + KenwoodProbe.describe(s.frame));
        last = s.label + ": sent...";
        main.postDelayed(() -> {
            String silent = k.giveUp(s.seq);
            if (silent == null) return;
            last = silent + ": no answer from the stereo.";
            Diag.warn(DiagLog.CMD, last);
        }, ANSWER_MS);
    }

    void mute(final boolean on) { io.execute(() -> send(link.mute(on))); }

    /** Held to the stereo's scale, 0 to {@link KenwoodLink#VOLUME_MAX}. */
    void volume(final int level) { io.execute(() -> send(link.volume(level))); }

    void presetEq(final int index) { io.execute(() -> send(link.presetEq(index))); }

    private void drop() {
        out = null;
        BluetoothSocket s = socket;
        socket = null;
        closeQuietly(s);
    }

    void close() {
        closing = true;
        connecting = false;
        StereoSocket w = waiter;
        if (w != null) w.cancel();
        drop();
        state = "Not connected.";
        last = "";
    }
}
