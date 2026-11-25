package com.strickland.jeepdash.scene;

import android.os.SystemClock;
import android.view.Choreographer;
import android.view.Display;

import com.strickland.jeepdash.core.FramePacer;
import com.strickland.jeepdash.core.QualityMode;

/**
 * The Holo face's one frame clock: on each screen refresh it decides (FramePacer) whether to draw, at the Quality
 * mode's cap while anything moves or a reading changes and at its idle rate when all is still, then moves and
 * draws the 3D truck and redraws the 2D layers in the same frame. Never above 60 a second, whatever the screen
 * does. Runs only while the face is on screen.
 */
public final class HoloLoop implements Choreographer.FrameCallback {
    /** The 2D layers: redraw them; true while they animate (panels opening, needles catching up). */
    public interface Layers {
        boolean redraw(long nowMs);
    }

    private static final long ACTIVE_MS = 400;
    private final FramePacer pacer = new FramePacer();
    private final Layers layers;
    private final HoloState state;
    private SceneHost scene;
    private QualityMode mode = QualityMode.of(QualityMode.HIGH);
    private boolean running, layersBusy = true;
    private long lastNs, pokedMs, vsyncNs = 16_666_667;

    public HoloLoop(Layers layers, HoloState state) {
        this.layers = layers;
        this.state = state;
    }

    public void setScene(SceneHost s) { scene = s; }

    public void setMode(QualityMode m) {
        mode = m;
        pacer.reset();
    }

    public void setDisplay(Display d) {
        float hz = d == null ? 60 : d.getRefreshRate();
        vsyncNs = (long) (1e9 / Math.max(30, hz));
    }

    public void start() {
        if (running) return;
        running = true;
        lastNs = 0;
        pacer.reset();
        Choreographer.getInstance().postFrameCallback(this);
    }

    public void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
    }

    public boolean running() { return running; }

    /** A reading changed or the owner touched something: full rate for a moment, drawn at the next refresh. */
    public void poke() {
        long now = SystemClock.elapsedRealtime();
        if (now - pokedMs > ACTIVE_MS) pacer.reset();
        pokedMs = now;
    }

    @Override
    public void doFrame(long frameNs) {
        if (!running) return;
        Choreographer.getInstance().postFrameCallback(this);
        long now = SystemClock.elapsedRealtime();
        boolean active = now - pokedMs < ACTIVE_MS || layersBusy || scene != null && scene.moving();
        if (!pacer.due(frameNs, vsyncNs, mode.fps(active))) return;
        double dt = lastNs == 0 ? 1.0 / mode.maxFps : Math.min(0.5, (frameNs - lastNs) / 1e9);
        lastNs = frameNs;
        if (scene != null && !scene.frame(frameNs, dt)) {
            state.sceneOk = false;
            state.sceneNote = "3D view stopped";
            scene = null;
        }
        layersBusy = layers.redraw(now);
    }
}
