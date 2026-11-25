package com.strickland.jeepdash;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.Window;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LayoutAnimationController;
import android.view.animation.TranslateAnimation;
import android.widget.ListView;

import com.strickland.jeepdash.core.Materialize;

/**
 * Menus and settings in the Holo look: dark glass with a cyan edge, and when animations are on they materialize
 * like the panels (stretch open from a line) with their rows unfolding one after another. Other looks keep
 * Android's own dialogs.
 */
final class HoloDialogs {
    private HoloDialogs() { }

    private static final long ROW_MS = 160;

    static AlertDialog.Builder builder(Activity a, boolean holo) {
        return holo ? new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert) : new AlertDialog.Builder(a);
    }

    /** Shows the dialog, dressed for the Holo look when it is on. */
    static AlertDialog show(AlertDialog.Builder b, boolean holo, boolean animate) {
        AlertDialog d = b.create();
        if (holo) dress(d, animate);
        d.show();
        return d;
    }

    private static void dress(AlertDialog d, boolean animate) {
        final float density = d.getContext().getResources().getDisplayMetrics().density;
        d.setOnShowListener(x -> {
            Window w = d.getWindow();
            if (w == null) return;
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xF2050C14);
            bg.setStroke(Math.round(1.5f * density), 0xC046E8FF);
            bg.setCornerRadius(4 * density);
            w.setBackgroundDrawable(bg);
            if (!animate) return;
            View decor = w.getDecorView();
            decor.setPivotY(decor.getHeight() / 2f);
            decor.setScaleY((float) Materialize.LINE);
            decor.setAlpha(0.6f);
            decor.animate().scaleY(1).alpha(1).setDuration(Materialize.MS).setInterpolator(new DecelerateInterpolator()).start();
        });
        if (!animate) return;
        ListView lv = d.getListView();
        if (lv == null) return;
        AnimationSet set = new AnimationSet(true);
        set.addAnimation(new AlphaAnimation(0, 1));
        set.addAnimation(new TranslateAnimation(Animation.RELATIVE_TO_SELF, -0.06f, Animation.RELATIVE_TO_SELF, 0,
                Animation.RELATIVE_TO_SELF, 0, Animation.RELATIVE_TO_SELF, 0));
        set.setDuration(ROW_MS);
        lv.setLayoutAnimation(new LayoutAnimationController(set, Materialize.rowDelay(1, true) / (float) ROW_MS));
    }
}
