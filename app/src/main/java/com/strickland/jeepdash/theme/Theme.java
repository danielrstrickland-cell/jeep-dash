package com.strickland.jeepdash.theme;

import android.graphics.Canvas;
import android.graphics.RectF;

import com.strickland.jeepdash.LinkService;
import com.strickland.jeepdash.core.GaugeSpec;
import com.strickland.jeepdash.core.Param;

/** One look of the dash. All sizes are in dp; DashView scales the canvas. */
public interface Theme {

    /** What the bar and buttons around the gauges (and the music window) need to know. */
    final class Chrome {
        public LinkService.Status status = LinkService.Status.SEARCHING;
        public String detail = "";
        public int readsPerSec;
        public String tab = "gauges";      // gauges | viz
        public boolean canEdit;
        public boolean editing;
        public boolean snap = true;
        public String nextLook;            // label of the "switch look" button, where the look shows one
        public boolean music;              // the music button is on (Music button setting)
        public boolean musicOpen;          // its window is open
        public String musicTitle = "", musicArtist = "";     // the window's two lines (see NowPlaying)
        public boolean musicPlaying;
    }

    /** "xp" or "jeep": also the key the layout is saved under. */
    String id();

    void drawBackground(Canvas c, float w, float h);

    /**
     * @param value the true reading (null = no fresh reading: draw a dash)
     * @param shown the smoothed reading for needles and bars (null with value)
     */
    void drawGauge(Canvas c, GaugeSpec g, Param p, Double value, Double shown, boolean flashOn, boolean editing);

    /** A message in place of the gauges (e.g. the Visualizer tab before it exists). */
    void drawPanel(Canvas c, float w, float h, String title, String message);

    void drawChrome(Canvas c, float w, float h, Chrome s);

    /**
     * Which button is at this point, from the last drawChrome: menu, tab:gauges, tab:viz, logs, switch,
     * status, edit, add, snap, done. Null when none.
     */
    String hit(float x, float y);

    /** Where a button was drawn by the last drawChrome (dp), or null; the music window opens beside "music". */
    default RectF buttonRect(String action) { return null; }

    /** How this look dresses the music window: its own colours and lettering on the shared layout. */
    default MusicPainter.Dress musicDress() { return MusicPainter.plain(); }
}
