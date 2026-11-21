package com.strickland.jeepdash.theme;

import android.graphics.Canvas;

/** A start-up screen's artwork. The splash screen decides what the check lines say; this only draws them. */
public interface BootArt {
    int WAITING = 0, GOOD = 1, BAD = 2, NOT_YET = 3;

    /**
     * @param seconds since the screen opened
     * @param labels  the check lines, top to bottom
     * @param results what each has found; for a line still waiting, a hint to show ahead of the dots
     * @param states  WAITING, GOOD, BAD or NOT_YET (not shown yet) for each line
     * @param verdict the closing line, or null until every check is done
     */
    void draw(Canvas c, float w, float h, float seconds, String[] labels, String[] results, int[] states,
              String verdict, boolean verdictGood);
}
