package com.strickland.jeepdash;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.strickland.jeepdash.core.SciCodes;

import java.util.ArrayList;
import java.util.List;

/**
 * The Codes screen: lists the engine computer's stored codes, looks one up on the web when it is tapped,
 * and can clear them. Clearing is the only thing the dash ever changes in the engine computer, so it always
 * asks first, with the codes it is about to erase.
 */
final class CodesDialog {
    private final Activity activity;
    private final LinkService service;
    private AlertDialog dialog;
    private TextView status;
    private ArrayAdapter<String> rows;
    private List<Integer> codes;
    private boolean busy;

    private CodesDialog(Activity activity, LinkService service) {
        this.activity = activity;
        this.service = service;
    }

    static void show(Activity activity, LinkService service) { new CodesDialog(activity, service).open(); }

    private void open() {
        int pad = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        status = new TextView(activity);
        status.setPadding(pad, pad / 2, pad, pad / 2);
        status.setTextSize(16);
        rows = new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, new ArrayList<String>());
        ListView list = new ListView(activity);
        list.setAdapter(rows);
        list.setOnItemClickListener((parent, view, position, id) -> lookUp(position));
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(status);
        box.addView(list);

        dialog = new AlertDialog.Builder(activity)
                .setTitle("Trouble codes")
                .setView(box)
                .setPositiveButton("Close", null)
                .setNeutralButton("Read again", null)
                .setNegativeButton("Clear codes", null)
                .create();
        dialog.show();
        // set after show(), so these two buttons leave the box open
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(v -> read());
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(v -> confirmClear());
        read();
    }

    private void working(String what) {
        busy = true;
        status.setText(what);
        rows.clear();
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setEnabled(false);
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setEnabled(false);
    }

    private void read() {
        if (busy) return;
        working("Reading the engine computer…");
        service.readCodes((list, cleared) -> result(list, null));
    }

    /** Opens a Google search for the tapped code in the browser. */
    private void lookUp(int position) {
        if (codes == null || position < 0 || position >= codes.size()) return;
        String query = SciCodes.searchQuery(codes.get(position));
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SciCodes.searchUrl(query))));
        } catch (RuntimeException e) {      // no browser on this device
            status.setText("Could not open a browser here. Search for: " + query);
        }
    }

    private void confirmClear() {
        if (busy || codes == null || codes.isEmpty()) return;
        int n = codes.size();
        new AlertDialog.Builder(activity)
                .setTitle(n == 1 ? "Clear this code?" : "Clear these " + n + " codes?")
                .setMessage(SciCodes.listing(codes) + "\n\nThis erases them from the engine computer. The engine must be"
                        + " stopped with the key on. A fault that is still there will set its code again.")
                .setPositiveButton("Clear", (d, w) -> clear())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void clear() {
        if (busy) return;
        working("Clearing…");
        service.clearCodes(this::result);
    }

    private void result(List<Integer> list, SciCodes.Outcome cleared) {
        busy = false;
        codes = list;
        if (!dialog.isShowing()) return;
        boolean any = list != null && !list.isEmpty();
        String text = any ? "Tap a code to look it up on Google." : SciCodes.listing(list);
        if (cleared != null) text = SciCodes.say(cleared) + (any ? "\n\nStored now. " : "\n\n") + text;
        status.setText(text);
        rows.clear();
        if (any) for (int raw : list) rows.add(SciCodes.listing(java.util.Collections.singletonList(raw)));
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setEnabled(true);
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setEnabled(any);
    }
}
