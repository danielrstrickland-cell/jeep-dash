package com.strickland.jeepdash;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.TextView;
import com.strickland.jeepdash.core.SoundHelp;

/** The small round "i" beside a card's title: tap it for what the card does and how it works ({@link SoundHelp}). */
final class HelpButton {
    private HelpButton() { }

    static TextView make(final Activity a, final String topic) {
        float density = a.getResources().getDisplayMetrics().density;
        TextView i = new TextView(a);
        i.setText("i");
        i.setTextSize(15);
        i.setTextColor(Color.rgb(226, 232, 242));
        i.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC));
        i.setGravity(Gravity.CENTER);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.rgb(30, 38, 54));
        ring.setStroke(Math.max(1, (int) (1.5f * density)), Color.rgb(138, 150, 170));
        i.setBackground(ring);
        i.setContentDescription("About " + SoundHelp.title(topic));
        i.setOnClickListener(v -> new AlertDialog.Builder(a).setTitle(SoundHelp.title(topic))
                .setMessage(SoundHelp.text(topic)).setPositiveButton("Close", null).show());
        return i;
    }

    /** The side of the button, in pixels: big enough for a thumb. */
    static int size(Activity a) { return (int) (34 * a.getResources().getDisplayMetrics().density); }
}
