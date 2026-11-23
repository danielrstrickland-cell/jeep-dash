package com.strickland.jeepdash;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.strickland.jeepdash.core.FuelUse;

import java.util.Locale;

/**
 * "Fuel: calibrate at fill-up": compares what the dash counted since the last fill-up with what the pump put
 * in, and corrects the fuel figures by the difference. The arithmetic and its limits are FuelUse.calibrate.
 */
final class FuelDialog {
    private FuelDialog() { }

    /** onChanged runs after a fill-up is marked, so the dash can start reading what the estimate needs. */
    static void show(Activity a, Runnable onChanged) {
        final FuelUse fuel = FuelStore.get(a);
        float d = a.getResources().getDisplayMetrics().density;
        int pad = Math.round(20 * d);

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView estimate = new TextView(a);
        estimate.setText(fuel.fillStarted()
                ? String.format(Locale.US, "The dash counted %.2f gal since the last fill-up.", fuel.sinceFill())
                : "No fill-up has been marked yet. Fill the tank to full and tap \"Start from this fill-up\".");
        estimate.setTextSize(16);
        box.addView(estimate);

        TextView ask = new TextView(a);
        ask.setText("Gallons the pump put in:");
        ask.setPadding(0, pad / 2, 0, 0);
        box.addView(ask);
        final EditText pumped = new EditText(a);
        pumped.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        pumped.setEms(6);
        box.addView(pumped);

        TextView note = new TextView(a);
        note.setText("This is only right if the dash was running for every drive since the last fill-up, and the "
                + "tank was filled to full both times. Calibrate needs at least "
                + (int) FuelUse.MIN_CALIBRATE_GAL + " gal counted, and one calibration changes the figures by "
                + "no more than half or double.\n\nFuel figures are multiplied by "
                + String.format(Locale.US, "%.2f", fuel.factor()) + " now.");
        note.setTextSize(12);
        note.setPadding(0, pad / 2, 0, 0);
        box.addView(note);

        ScrollView scroll = new ScrollView(a);
        scroll.addView(box);
        new AlertDialog.Builder(a)
                .setTitle("Fuel: calibrate at fill-up")
                .setView(scroll)
                .setPositiveButton("Calibrate", (dlg, w) -> {
                    double was = fuel.factor();
                    Double now = FuelUse.calibrate(was, fuel.sinceFill(), parse(pumped.getText().toString()));
                    if (now == null) {
                        message(a, "Not calibrated", fuel.sinceFill() < FuelUse.MIN_CALIBRATE_GAL
                                ? String.format(Locale.US, "The dash has only counted %.2f gal since the last fill-up; "
                                        + "it needs at least %d gal to go on. Nothing was changed.",
                                        fuel.sinceFill(), (int) FuelUse.MIN_CALIBRATE_GAL)
                                : "Type the gallons the pump put in. Nothing was changed.");
                        return;
                    }
                    fuel.setFactor(now);
                    fuel.startFill();
                    FuelStore.save();
                    onChanged.run();
                    message(a, "Calibrated", String.format(Locale.US, "Fuel figures are now multiplied by %.2f (they were "
                            + "multiplied by %.2f). Counting starts again from this fill-up.", now, was));
                })
                .setNeutralButton("Start from this fill-up", (dlg, w) -> {
                    fuel.startFill();
                    FuelStore.save();
                    onChanged.run();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void message(Activity a, String title, String text) {
        new AlertDialog.Builder(a).setTitle(title).setMessage(text).setPositiveButton("OK", null).show();
    }

    private static double parse(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
