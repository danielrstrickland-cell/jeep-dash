package com.strickland.jeepdash;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;

import com.strickland.jeepdash.core.ValueScan;

/**
 * "Scan all values" (Diagnostics, Jeep only): asks first, then shows the scan's progress with a Cancel button,
 * and offers the finished CSV to share. The scan itself runs on the link thread (LinkService); closing this
 * window leaves it running, and opening the menu entry again comes back here.
 */
final class ScanDialog {
    private static final long REFRESH_MS = 500;

    private final Activity activity;
    private final LinkService service;
    private final Handler main = new Handler(Looper.getMainLooper());
    private AlertDialog dialog;

    private ScanDialog(Activity activity, LinkService service) {
        this.activity = activity;
        this.service = service;
    }

    static void show(Activity activity, LinkService service) {
        ScanDialog d = new ScanDialog(activity, service);
        if (service.scanRunning()) d.progress();
        else d.confirm();
    }

    private void confirm() {
        if (!service.scanAvailable()) {
            new AlertDialog.Builder(activity).setTitle("Scan all values")
                    .setMessage("The dongle is not connected to the Jeep yet.").setPositiveButton("OK", null).show();
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle("Scan all values")
                .setMessage("Run this with the engine idling warm, in Park.\n\nThe dash asks the engine computer for each "
                        + "of the 239 values in its live-data table (01 to ef), one at a time, three times over (about three "
                        + "minutes). It sends only the same read it uses for the gauges and changes nothing. The gauges "
                        + "are blank while it runs. A value that does not answer gets the line set up again and one more try. The result is saved as a CSV in Downloads/JeepDash to share.")
                .setPositiveButton("Start", (d, w) -> {
                    if (service.startScan()) progress();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void progress() {
        dialog = new AlertDialog.Builder(activity)
                .setTitle("Scan all values")
                .setMessage("Starting")
                .setPositiveButton("Close", null)
                .setNeutralButton("Cancel scan", null)
                .create();
        dialog.setOnDismissListener(d -> main.removeCallbacksAndMessages(null));
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            if (service.scanRunning()) service.cancelScan();
            else share();
        });
        refresh();
    }

    private void refresh() {
        if (dialog == null || !dialog.isShowing()) return;
        ValueScan s = service.scan();
        Button neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (s == null) {
            dialog.setMessage("No scan");
            neutral.setEnabled(false);
        } else if (service.scanRunning()) {
            dialog.setMessage(s.progress() + "\n\nThe gauges come back when it ends. Closing this window leaves it running.");
            neutral.setText(s.cancelled() ? "Cancelling" : "Cancel scan");
            neutral.setEnabled(!s.cancelled());
        } else {
            String where = service.scanUri() != null ? "Saved in Downloads/JeepDash as " + service.scanName() + "."
                    : "Could not save the file; Share sends it as text.";
            dialog.setMessage(s.progress() + "\n\n" + where);
            neutral.setText("Share");
            neutral.setEnabled(service.scanCsv() != null);
            return;                                 // ended: nothing more to follow
        }
        main.postDelayed(this::refresh, REFRESH_MS);
    }

    private void share() {
        try {
            if (service.scanUri() != null) {
                DriveLogStore.share(activity, service.scanUri(), "Jeep Dash value scan");
            } else if (service.scanCsv() != null) {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, service.scanCsv());
                activity.startActivity(Intent.createChooser(i, "Jeep Dash value scan"));
            }
        } catch (RuntimeException e) {
            new AlertDialog.Builder(activity).setTitle("Scan all values")
                    .setMessage("Could not open the share sheet. The file is in Downloads/JeepDash.")
                    .setPositiveButton("OK", null).show();
        }
    }
}
