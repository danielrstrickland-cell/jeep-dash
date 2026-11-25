package com.strickland.jeepdash.scene;

import com.strickland.jeepdash.core.HoloEffects;
import com.strickland.jeepdash.core.HoloFraming;
import com.strickland.jeepdash.core.QualityMode;

/**
 * What the Holo look's 2D layers and its 3D truck share each frame: the Quality mode and hologram effects in force,
 * the parallax, and the camera framing (so the platform rings drawn in 2D sit under the 3D truck). Main thread only.
 */
public final class HoloState {
    public QualityMode mode = QualityMode.of(QualityMode.HIGH);
    public HoloEffects fx = HoloEffects.of(mode, true, false);
    public double parallaxYaw, parallaxPitch;
    /** The truck view's framing, or null before there is one. */
    public HoloFraming framing;
    /** The truck in the scene (TruckModel.LENGTH long). */
    public double truckWidth = 1.55, truckHeight = 1.8;
    /** False when the 3D view is not running (not available here, paused for heat); note says why. */
    public boolean sceneOk = true;
    public String sceneNote = "";
    /** The night trail scene instead of the projector room (Holo settings). */
    public boolean trail;
    /** Hood, liftgate or neither can open on this model. */
    public boolean canOpen;
}
