package com.strickland.jeepdash;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import java.io.IOException;
import java.util.UUID;

/**
 * Gets the serial link to the Kenwood the way the stock KENWOOD Remote app does: the tablet listens on the
 * OpenLink service and the stereo calls in. Calling out to the stereo instead is accepted and hung up on
 * within milliseconds (seen on the owner's KMM-BT728HD, build 84), and Kenwood's own instructions are to
 * start the app first and then connect from the receiver.
 *
 * The stereo calls when its REMOTE APP setting is on ANDROID with this tablet picked, at the moment
 * Bluetooth connects or that setting is made. The stock app must not be running: it listens on the same
 * service. The caller holds the Bluetooth permission.
 */
final class StereoSocket {
    static final UUID OPENLINK = UUID.fromString("16f92dc0-2eeb-11e6-b342-0002a5d5c51b");

    /** What to do on the stereo so it calls, in the stereo's own menu words. */
    static final String HOW = "On the stereo: press the volume knob for FUNCTION, then REMOTE APP, SELECT, ANDROID, YES, "
            + "and pick this tablet in ANDROID LIST. Force-stop the KENWOOD Remote app on the tablet first.";

    interface Log { void line(String text); }

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
