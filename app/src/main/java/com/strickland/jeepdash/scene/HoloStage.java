package com.strickland.jeepdash.scene;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.android.filament.ColorGrading;
import com.google.android.filament.Engine;
import com.google.android.filament.EntityManager;
import com.google.android.filament.LightManager;
import com.google.android.filament.ToneMapper;
import com.strickland.jeepdash.core.HoloFraming;
import com.strickland.jeepdash.core.QualityMode;
import com.strickland.jeepdash.core.SpringDamper;
import com.strickland.jeepdash.core.TruckPose;

import java.nio.ByteBuffer;

/**
 * The Holo face's 3D scene: the truck on its platform under a dark studio light with a cyan rim from behind,
 * moving with the motion model (TruckMotion via MotionFeed). Tap: it spins round once and settles. Hold: the hood
 * (or else the liftgate) opens, and closes on the next hold or once the truck drives off.
 */
public final class HoloStage implements Stage {
    /** Default brightness of the studio light; the owner's Lighting setting moves the camera's exposure from here. */
    static final float IBL_LUX = 40_000, KEY_LUX = 70_000, RIM_LUMENS = 2_400_000;

    public interface Listener {
        /** The model loaded (summary of what moves) or failed (message). */
        void onModel(String summary, String error);
    }

    private final Context context;
    private final MotionFeed feed;
    private final HoloState state;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SpringDamper spin = new SpringDamper(0.45, 0.62), hood = new SpringDamper(0.8, 0.85);
    private SceneHost host;
    private Ibl ibl;
    private ColorGrading grading;
    private int key, rimLeft, rimRight;
    private TruckModel truck;
    private ShadowFloor floor;
    private String kind = QualityMode.STAND_IN, mode = QualityMode.HIGH;
    private boolean loading, attached, shadow;
    private int loadSerial;
    private double wheel, lastYaw = Double.NaN, lastPitch = Double.NaN;
    private int width = 1, height = 1;
    private float exposureEv;

    public HoloStage(Context c, MotionFeed feed, HoloState state, Listener listener) {
        this.context = c.getApplicationContext();
        this.feed = feed;
        this.state = state;
        this.listener = listener;
    }

    @Override
    public void attach(SceneHost h) {
        host = h;
        attached = true;
        Engine e = h.engine;
        try {
            ibl = Ibl.load(e, ModelStore.asset(context, "scene/holo_ibl.ktx"), IBL_LUX);
        } catch (java.io.IOException ex) {
            ibl = null;
        }
        if (ibl != null) h.scene.setIndirectLight(ibl.light);
        key = EntityManager.get().create();
        new LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(0.96f, 0.98f, 1.0f)
                .intensity(KEY_LUX)
                .direction(0.45f, -0.75f, -0.5f)
                .castShadows(false)
                .build(e, key);
        h.scene.addEntity(key);
        rimLeft = rim(e, -3.2f);
        rimRight = rim(e, 3.2f);
        h.scene.addEntity(rimLeft);
        h.scene.addEntity(rimRight);
        // a touch of the hologram's cyan over the whole truck
        grading = new ColorGrading.Builder()
                .toneMapper(new ToneMapper.Filmic())
                .slopeOffsetPower(new float[]{0.95f, 1.0f, 1.05f}, new float[]{0f, 0.002f, 0.004f}, new float[]{1f, 1f, 1f})
                .build(e);
        h.filamentView.setColorGrading(grading);
        setExposure(exposureEv);
        applyShadow();
        reload();
    }

    /** The Quality mode in force: picks the truck file for it and Ultra's shadow. */
    public void setMode(QualityMode q) {
        mode = q.name;
        shadow = q.shadow;
        if (!attached) return;
        applyShadow();
        if (!ModelStore.pick(context, mode).equals(kind)) reload();
    }

    private void applyShadow() {
        if (host == null) return;
        LightManager lm = host.engine.getLightManager();
        int li = lm.getInstance(key);
        if (li != 0) lm.setShadowCaster(li, shadow);
        if (shadow && floor == null) {
            try {
                floor = new ShadowFloor(host.engine, host.scene, ModelStore.asset(context, "scene/shadow_catcher.filamat"), 4f);
            } catch (java.io.IOException | RuntimeException e) {
                floor = null;                     // no shadow, everything else as Ultra
            }
        } else if (!shadow && floor != null) {
            floor.destroy(host.engine, host.scene);
            floor = null;
        }
        if (truck != null) truck.setCastShadows(shadow);
        host.requestFrame();
    }

    /** A cyan spot behind the truck, aimed at it: its light grazes the edges as a rim. */
    private static int rim(Engine e, float x) {
        int ent = EntityManager.get().create();
        float px = x, py = 2.6f, pz = -4.2f, tx = 0, ty = 0.9f, tz = 0;
        float dx = tx - px, dy = ty - py, dz = tz - pz, l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        new LightManager.Builder(LightManager.Type.SPOT)
                .color(0.25f, 0.85f, 1.0f)
                .intensity(RIM_LUMENS)
                .position(px, py, pz)
                .direction(dx / l, dy / l, dz / l)
                .falloff(14f)
                .spotLightCone((float) Math.toRadians(16), (float) Math.toRadians(32))
                .castShadows(false)
                .build(e, ent);
        return ent;
    }

    /** -2..+2: the owner's Lighting setting. */
    public void setExposure(float ev) {
        exposureEv = Math.max(-2, Math.min(2, ev));
        if (host != null) host.camera.setExposure(16f, 1f / 125f, (float) (100 * Math.pow(2, exposureEv)));
        if (host != null) host.requestFrame();
    }

    /** Load the model this mode shows again, e.g. after a new one was picked. */
    public void reload() { load(ModelStore.pick(context, mode)); }

    private void load(String which) {
        kind = which;
        if (!attached) return;
        final int serial = ++loadSerial;
        final String k = which;
        loading = true;
        new Thread(() -> {
            ByteBuffer data = null;
            String error = null;
            try {
                data = ModelStore.read(context, k);
            } catch (java.io.IOException | OutOfMemoryError ex) {
                error = "Could not read the model file.";
            }
            final ByteBuffer d = data;
            final String err = error;
            main.post(() -> install(serial, d, err));
        }, "truck-model").start();
    }

    private void install(int serial, ByteBuffer data, String error) {
        if (!attached || serial != loadSerial) return;
        loading = false;
        if (truck != null) {
            truck.destroy();
            truck = null;
        }
        if (data != null) {
            try {
                truck = TruckModel.load(host.engine, host.scene, data);
            } catch (RuntimeException ex) {
                error = ex.getMessage() == null ? "The 3D view could not read that model." : ex.getMessage();
            }
        }
        if (truck == null && !QualityMode.STAND_IN.equals(kind)) {
            // the owner's model failed: show the stand-in rather than nothing
            listener.onModel(null, error);
            load(QualityMode.STAND_IN);
            return;
        }
        if (truck != null) truck.setCastShadows(shadow);
        if (truck != null) {
            double s = TruckModel.LENGTH / Math.max(1e-6, truck.rig.length);
            state.truckWidth = truck.rig.width * s;
            state.truckHeight = truck.rig.height * s;
            state.canOpen = truck.rig.hood.length > 0 || truck.rig.gate.length > 0;
            listener.onModel(truck.rig.summary(), null);
        } else {
            listener.onModel(null, error);
        }
        lastYaw = Double.NaN;
        host.requestFrame();
    }

    /** Tap: one turn round, then back where it was. */
    public void spin() {
        spin.setTarget(spin.target() + 360);
        if (host != null) host.requestFrame();
    }

    /** Hold: open or close the hood (or the liftgate). False when the model has neither. */
    public boolean toggleOpen() {
        if (truck == null || truck.rig.hood.length == 0 && truck.rig.gate.length == 0) return false;
        double open = truck.rig.hood.length > 0 ? TruckPose.HOOD_OPEN_DEG : TruckPose.GATE_OPEN_DEG;
        hood.setTarget(hood.target() > 0 ? 0 : open);
        if (host != null) host.requestFrame();
        return true;
    }

    @Override
    public void resize(int w, int h) {
        width = Math.max(1, w);
        height = Math.max(1, h);
        lastYaw = Double.NaN;
    }

    @Override
    public boolean frame(long nowMs, double dt) {
        feed.step(nowMs);
        boolean busy = loading;
        if (truck != null) busy |= truck.update();
        spin.step(dt);
        hood.step(dt);
        if (spin.settled(0.2, 1) && spin.target() >= 360) spin.shift(-360 * Math.floor(spin.target() / 360));
        if (feed.motion.speedMps() > 1.5 && hood.target() > 0) hood.setTarget(0);      // driving off closes it
        double speed = feed.motion.speedMps();
        wheel = (wheel + speed / com.strickland.jeepdash.core.TruckMotion.TIRE_RADIUS_M * dt) % (2 * Math.PI);

        // the camera, swung a little by the parallax
        double py = feed.parallax.yawDeg(), pp = feed.parallax.pitchDeg();
        state.parallaxYaw = py;
        state.parallaxPitch = pp;
        boolean camMoved = Double.isNaN(lastYaw) || Math.abs(py - lastYaw) > 0.01 || Math.abs(pp - lastPitch) > 0.01;
        if (camMoved) {
            lastYaw = py;
            lastPitch = pp;
            HoloFraming f = HoloFraming.fit(width / (double) height, TruckModel.LENGTH, state.truckWidth, state.truckHeight, py, pp);
            state.framing = f;
            host.camera.setProjection(f.fovDeg, f.aspect, f.near, f.far, com.google.android.filament.Camera.Fov.VERTICAL);
            host.camera.lookAt(f.eye[0], f.eye[1], f.eye[2], f.target[0], f.target[1], f.target[2], 0, 1, 0);
        }
        if (truck != null) {
            double yaw = Math.toRadians(BASE_YAW_DEG + spin.value());
            double open = Math.toRadians(hood.value());
            boolean hoodPart = truck.rig.hood.length > 0;
            truck.pose(yaw, feed.motion.rollDeg(), feed.motion.pitchDeg(), Math.toRadians(feed.motion.steerDeg()), wheel,
                    hoodPart ? open : 0, hoodPart ? 0 : open);
        }
        return busy || camMoved || feed.motion.moving() || !spin.settled(0.05, 0.5) || !hood.settled(0.05, 0.5);
    }

    /** The truck faces the viewer's lower left, like the mockup: its front and its driver's side show. */
    static final double BASE_YAW_DEG = -55;

    @Override
    public void detach(SceneHost h) {
        attached = false;
        loadSerial++;
        if (truck != null) truck.destroy();
        truck = null;
        Engine e = h.engine;
        if (floor != null) floor.destroy(e, h.scene);
        floor = null;
        h.scene.setIndirectLight(null);
        if (ibl != null) ibl.destroy(e);
        ibl = null;
        for (int ent : new int[]{key, rimLeft, rimRight}) {
            h.scene.removeEntity(ent);
            e.getLightManager().destroy(ent);
            EntityManager.get().destroy(ent);
        }
        h.filamentView.setColorGrading(null);
        if (grading != null) e.destroyColorGrading(grading);
        grading = null;
        host = null;
    }
}
