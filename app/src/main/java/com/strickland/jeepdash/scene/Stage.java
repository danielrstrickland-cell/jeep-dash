package com.strickland.jeepdash.scene;

/**
 * One 3D scene on the shared renderer (SceneHost): the Holo face's truck today; a night trail can be another
 * Stage using the same renderer, TruckModel and TruckMotion. Everything is called on the main thread.
 */
public interface Stage {
    /** Make lights and load models into the host's scene. */
    void attach(SceneHost host);

    /** The view's size in pixels. */
    void resize(int widthPx, int heightPx);

    /** Move and pose everything for this frame; true while anything is still moving (worth drawing often). */
    boolean frame(long nowMs, double dtSec);

    /** Take out and destroy everything attach made. */
    void detach(SceneHost host);
}
