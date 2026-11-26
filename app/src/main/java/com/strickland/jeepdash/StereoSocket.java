package com.strickland.jeepdash;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Gets the serial link to the Kenwood the way the stock KENWOOD Remote app (2.0.5) gets it. That app does two
 * things at once: it offers a serial service of its own, named "JK Open Link", for the stereo to call, and
 * half a second later it calls the stereo's service. Whichever connects is the link; if the stereo calls
 * within a second of taking the app's call, the stereo's call is used and the app's is dropped.
 *
 * The stereo's REMOTE APP setting has to be on ANDROID with this tablet picked, and the stock app must not be
 * running: the stereo keeps one such link. The caller holds the Bluetooth permission. One object per connect.
 */
final class StereoSocket {
    /** The stereo's serial service, which the app calls. */
    static final UUID CALL = UUID.fromString("16f92dc0-2eeb-11e6-b342-0002a5d5c51b");
    /** The service the app offers for the stereo to call, and the name it is offered under. */
    static final UUID LISTEN = UUID.fromString("dba8f580-2d22-11e6-9569-0002a5d5c51b");
    static final String NAME = "JK Open Link";
    private static final long PRELISTEN_MS = 500, CALLBACK_MS = 1000;

    /** What to do on the stereo so it links, in the stereo's own menu words. */
    static final String HOW = "On the stereo: press the volume knob for FUNCTION, then REMOTE APP, SELECT, ANDROID, YES, "
            + "and pick this tablet in ANDROID LIST. Force-stop the KENWOOD Remote app on the tablet first. If nothing "
            + "happens, switch the stereo off and on so it reconnects.";

    interface Log { void line(String text); }

    private final LinkedBlockingQueue<BluetoothSocket> callers = new LinkedBlockingQueue<>();
    private volatile BluetoothServerSocket server;
    private volatile BluetoothSocket dialling, dialled;
    private volatile boolean cancelled;
    private volatile Runnable onCaller;

    /** Run whenever the stereo calls in, so a link on the tablet's own call can give way to it. */
    void onCaller(Runnable r) { onCaller = r; }

    /** True if this socket is the tablet's own call rather than one the stereo made. */
    boolean dialled(BluetoothSocket s) { return s != null && s == dialled; }

    private static void close(BluetoothSocket s) {
        try { if (s != null) s.close(); } catch (IOException ignored) { }
    }

    /** Offers the app's service and keeps taking the stereo's calls until cancelled. False if it cannot listen. */
    private boolean listen(BluetoothAdapter adapter, final BluetoothDevice wanted, final Log log) {
        final BluetoothServerSocket ss;
        try {
            ss = adapter.listenUsingRfcommWithServiceRecord(NAME, LISTEN);
        } catch (IOException | RuntimeException e) {
            log.line("Cannot listen for the stereo: " + e.getMessage());
            return false;
        }
        server = ss;
        Thread t = new Thread(() -> {
            try {
                while (!cancelled) {
                    BluetoothSocket s = ss.accept();             // until something calls, or cancel() closes it
                    if (wanted != null && !wanted.getAddress().equals(s.getRemoteDevice().getAddress())) {
                        log.line("A different device called and was turned away.");
                        close(s);
                        continue;
                    }
                    log.line("The stereo called this tablet.");
                    callers.add(s);
                    Runnable r = onCaller;
                    if (r != null) r.run();
                }
            } catch (IOException | RuntimeException e) {
                if (!cancelled) log.line("Stopped listening for the stereo: " + e.getMessage());
            } finally {
                server = null;
                try { ss.close(); } catch (IOException ignored) { }
            }
        }, "stereo-listen");
        t.setDaemon(true);
        t.start();
        return true;
    }

    /** Calls the stereo's service: secured first, then unsecured. Null if neither connects. */
    private BluetoothSocket call(BluetoothAdapter adapter, BluetoothDevice device, Log log) {
        for (int attempt = 0; attempt < 2 && !cancelled; attempt++) {
            BluetoothSocket cand = null;
            try {
                cand = attempt == 0 ? device.createRfcommSocketToServiceRecord(CALL)
                        : device.createInsecureRfcommSocketToServiceRecord(CALL);
                dialling = cand;
                try { adapter.cancelDiscovery(); } catch (RuntimeException ignored) { }   // needs the scan permission; not vital
                cand.connect();
                dialling = null;
                return cand;
            } catch (IOException | RuntimeException e) {
                dialling = null;
                if (!cancelled)
                    log.line("Calling the stereo (" + (attempt == 0 ? "secured" : "unsecured") + ") failed: " + e.getMessage());
                close(cand);
            }
        }
        return null;
    }

    /**
     * The first link: listen, call, and prefer the stereo's own call if it comes within a second. If the
     * stereo does not take the tablet's call, waits for the stereo to call. Null if cancelled, or if the
     * tablet can neither call nor listen.
     */
    BluetoothSocket first(BluetoothAdapter adapter, BluetoothDevice device, Log log) throws InterruptedException {
        boolean listening = listen(adapter, device, log);
        if (listening) Thread.sleep(PRELISTEN_MS);
        BluetoothSocket out = call(adapter, device, log);
        if (cancelled) {
            close(out);
            return null;
        }
        if (out == null) return listening ? next() : null;
        BluetoothSocket in = callers.poll(CALLBACK_MS, TimeUnit.MILLISECONDS);
        if (in == null) {
            dialled = out;
            return out;
        }
        log.line("The stereo took the tablet's call and called back: using the stereo's call, as the stock app does.");
        close(out);
        return in;
    }

    /** Blocks until the stereo calls in. Null if cancelled or no longer listening. */
    BluetoothSocket next() throws InterruptedException {
        while (!cancelled) {
            BluetoothSocket s = callers.poll(500, TimeUnit.MILLISECONDS);
            if (s != null) return s;
            if (server == null && callers.isEmpty()) return null;
        }
        return null;
    }

    void cancel() {
        cancelled = true;
        onCaller = null;
        BluetoothServerSocket ss = server;
        if (ss != null) {
            try { ss.close(); } catch (IOException ignored) { }
        }
        close(dialling);
        BluetoothSocket s;
        while ((s = callers.poll()) != null) close(s);
    }
}
