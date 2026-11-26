package com.strickland.jeepdash;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import java.io.IOException;
import java.util.UUID;

/**
 * Gets the serial link to the Kenwood, either way round. The stock KENWOOD Remote app calls the stereo's
 * OpenLink service, so {@link #call} does that first. On the owner's KMM-BT728HD that call was taken and
 * hung up on within milliseconds while the stereo's REMOTE APP setting was not on Android, so the link also
 * knows how to wait for the stereo to call the tablet ({@link #await}), which is what Kenwood's own
 * instructions describe (start the app, then connect from the receiver).
 *
 * Either way the stereo's REMOTE APP setting has to be on ANDROID with this tablet picked, and the stock app
 * must not be running: the stereo keeps one such link. The caller holds the Bluetooth permission.
 */
final class StereoSocket {
    static final UUID OPENLINK = UUID.fromString("16f92dc0-2eeb-11e6-b342-0002a5d5c51b");

    /** What to do on the stereo so it calls, in the stereo's own menu words. */
    static final String HOW = "On the stereo: press the volume knob for FUNCTION, then REMOTE APP, SELECT, ANDROID, YES, "
            + "and pick this tablet in ANDROID LIST. Force-stop the KENWOOD Remote app on the tablet first. If nothing "
            + "happens, switch the stereo off and on so it reconnects.";

    interface Log { void line(String text); }

    /** Calls the stereo's OpenLink service: secured first, then unsecured. Null if neither connects. */
    static BluetoothSocket call(BluetoothAdapter adapter, BluetoothDevice device, Log log) {
        for (int attempt = 0; attempt < 2; attempt++) {
            BluetoothSocket cand = null;
            try {
                cand = attempt == 0 ? device.createRfcommSocketToServiceRecord(OPENLINK)
                        : device.createInsecureRfcommSocketToServiceRecord(OPENLINK);
                try { adapter.cancelDiscovery(); } catch (RuntimeException ignored) { }   // needs the scan permission; not vital
                cand.connect();
                return cand;
            } catch (IOException | RuntimeException e) {
                log.line("Calling the stereo (" + (attempt == 0 ? "secured" : "unsecured") + ") failed: " + e.getMessage());
                try { if (cand != null) cand.close(); } catch (IOException ignored) { }
            }
        }
        return null;
    }

    private volatile BluetoothServerSocket server;
    private volatile boolean cancelled;

    /** Blocks until the wanted stereo calls in; null if cancelled or this tablet cannot listen. */
    BluetoothSocket await(BluetoothAdapter adapter, BluetoothDevice wanted, Log log) {
        BluetoothServerSocket ss;
        try {
            ss = adapter.listenUsingRfcommWithServiceRecord("OpenLink", OPENLINK);
        } catch (IOException | RuntimeException e) {
            log.line("Cannot listen for the stereo: " + e.getMessage());
            return null;
        }
        server = ss;
        try {
            while (!cancelled) {
                BluetoothSocket s = ss.accept();             // until something calls, or cancel() closes it
                if (wanted == null || wanted.getAddress().equals(s.getRemoteDevice().getAddress())) return s;
                log.line("A different device called and was turned away.");
                try { s.close(); } catch (IOException ignored) { }
            }
        } catch (IOException | RuntimeException e) {
            if (!cancelled) log.line("Stopped listening for the stereo: " + e.getMessage());
        } finally {
            server = null;
            try { ss.close(); } catch (IOException ignored) { }
        }
        return null;
    }

    void cancel() {
        cancelled = true;
        BluetoothServerSocket ss = server;
        if (ss != null) {
            try { ss.close(); } catch (IOException ignored) { }
        }
    }
}
