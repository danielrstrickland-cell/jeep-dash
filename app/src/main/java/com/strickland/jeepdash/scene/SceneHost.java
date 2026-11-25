package com.strickland.jeepdash.scene;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.view.Surface;
import android.view.TextureView;

import com.google.android.filament.Camera;
import com.google.android.filament.Engine;
import com.google.android.filament.EntityManager;
import com.google.android.filament.Filament;
import com.google.android.filament.Renderer;
import com.google.android.filament.Scene;
import com.google.android.filament.SwapChain;
import com.google.android.filament.View;
import com.google.android.filament.Viewport;
import com.google.android.filament.android.UiHelper;
import com.google.android.filament.gltfio.Gltfio;
import com.strickland.jeepdash.core.QualityMode;

/**
 * Google Filament on a see-through TextureView: the engine, a scene with one camera, and the quality settings of
 * the Holo look's Quality mode (render scale, MSAA, bloom, soft shadow, ambient occlusion). It draws a Stage when
 * HoloLoop says so, on the main thread; Filament does the GPU work on its own thread, so the gauges and the car
 * link do not wait on it.
 */
public final class SceneHost implements UiHelper.RendererCallback {
    private static Boolean loaded;

    /** True when Filament's native code loads on this device (once per run). */
    public static synchronized boolean available() {
        if (loaded == null) {
            try {
                Filament.init();
                Gltfio.init();
                loaded = true;
            } catch (Throwable t) {
                loaded = false;
            }
        }
        return loaded;
    }

    public final TextureView view;
    public final Engine engine;
    public final Scene scene;
    public final View filamentView;
    public final Camera camera;
    private final Renderer renderer;
    private final int cameraEntity;
    private final UiHelper ui = new UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK);
    private SwapChain swapChain;
    private Stage stage;
    private boolean destroyed, failed, moving = true;
    private QualityMode quality = QualityMode.of(QualityMode.HIGH);
    private int viewW, viewH, framesMarked;

    /** Call only when available() is true. */
    public SceneHost(Context c) {
        Trail.start(c);
        view = new TextureView(c);
        Trail.step("Engine.create");
        engine = Engine.create();
        renderer = engine.createRenderer();
        scene = engine.createScene();
        filamentView = engine.createView();
        cameraEntity = EntityManager.get().create();
        camera = engine.createCamera(cameraEntity);
        camera.setExposure(16f, 1f / 125f, 100f);
        filamentView.setScene(scene);
        filamentView.setCamera(camera);
        // see-through: the Holo backdrop shows round the truck
        filamentView.setBlendMode(View.BlendMode.TRANSLUCENT);
        Renderer.ClearOptions clear = new Renderer.ClearOptions();
        clear.clear = true;
        clear.clearColor = new float[]{0, 0, 0, 0};
        renderer.setClearOptions(clear);
        filamentView.setAntiAliasing(View.AntiAliasing.NONE);
        View.RenderQuality q = new View.RenderQuality();
        q.hdrColorBuffer = View.QualityLevel.MEDIUM;
        filamentView.setRenderQuality(q);
        view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != viewW || b - t != viewH) {
                viewW = r - l;
                viewH = b - t;
                applySize();
            }
        });
        Trail.step("view set up; attach TextureView");
        ui.setOpaque(false);
        ui.setRenderCallback(this);
        ui.attachTo(view);
        setQuality(quality);
    }

    public void setStage(Stage s) {
        if (stage != null) stage.detach(this);
        stage = s;
        if (s != null) {
            s.attach(this);
            int[] size = bufferSize();
            if (size[0] > 0) s.resize(size[0], size[1]);
        }
        moving = true;
    }

    /** The Quality mode's settings for the 3D view. */
    public void setQuality(QualityMode q) {
        Trail.step("setQuality " + q.name + " msaa=" + q.msaa + " bloom=" + q.bloom + " shadow=" + q.shadow + " ssao=" + q.ssao + " scale=" + q.renderScale);
        quality = q;
        View.MultiSampleAntiAliasingOptions msaa = new View.MultiSampleAntiAliasingOptions();
        msaa.enabled = q.msaa;
        msaa.sampleCount = 4;
        filamentView.setMultiSampleAntiAliasingOptions(msaa);
        View.BloomOptions b = new View.BloomOptions();
        b.enabled = q.bloom;
        b.strength = 0.10f;
        b.levels = q.multiPassBloom ? 7 : 4;
        b.resolution = q.multiPassBloom ? 512 : 256;
        b.quality = q.multiPassBloom ? View.QualityLevel.HIGH : View.QualityLevel.LOW;
        filamentView.setBloomOptions(b);
        filamentView.setShadowingEnabled(q.shadow);
        if (q.shadow) filamentView.setShadowType(View.ShadowType.DPCF);
        View.AmbientOcclusionOptions ao = new View.AmbientOcclusionOptions();
        ao.enabled = q.ssao;
        ao.intensity = 0.6f;
        ao.radius = 0.3f;
        filamentView.setAmbientOcclusionOptions(ao);
        applySize();
        Trail.step("setQuality done");
        moving = true;
    }

    public QualityMode quality() { return quality; }

    /** The render buffer: the view's size times the mode's scale (over 1 is supersampled, under 1 cheaper). */
    private int[] bufferSize() {
        return new int[]{Math.max(1, Math.round(viewW * quality.renderScale)), Math.max(1, Math.round(viewH * quality.renderScale))};
    }

    private void applySize() {
        if (viewW <= 0 || viewH <= 0 || destroyed) return;
        int[] s = bufferSize();
        ui.setDesiredSize(s[0], s[1]);
    }

    /** Something changed (a tap, a new model): treat the scene as moving. */
    public void requestFrame() { moving = true; }

    /** True while the stage has something moving (HoloLoop keeps the full frame rate). */
    public boolean moving() { return moving; }

    /** One paced frame: move the stage and draw it. False once the 3D view has failed. */
    public boolean frame(long frameNs, double dt) {
        if (destroyed || failed) return false;
        try {
            if (framesMarked < 3) Trail.step("frame " + framesMarked + ": stage.frame");
            moving = stage == null || stage.frame(SystemClock.elapsedRealtime(), dt);
            boolean mark = framesMarked < 3;
            if (mark) Trail.step("frame " + framesMarked + ": stage moved; swapChain=" + (swapChain != null) + " ready=" + ui.isReadyToRender());
            if (swapChain != null && ui.isReadyToRender() && renderer.beginFrame(swapChain, frameNs)) {
                if (mark) Trail.step("frame " + framesMarked + ": render " + viewW + "x" + viewH);
                renderer.render(filamentView);
                renderer.endFrame();
                if (mark) Trail.step("frame " + framesMarked++ + ": done");
            }
            return true;
        } catch (RuntimeException e) {
            // a bad model or a lost surface must not take the dash down: stop drawing the 3D view
            failed = true;
            return false;
        }
    }

    @Override
    public void onNativeWindowChanged(Surface surface) {
        if (destroyed) return;
        Trail.step("createSwapChain flags=" + ui.getSwapChainFlags());
        if (swapChain != null) engine.destroySwapChain(swapChain);
        swapChain = engine.createSwapChain(surface, ui.getSwapChainFlags());
        Trail.step("createSwapChain done");
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                surface.setFrameRate(60f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
            } catch (RuntimeException ignored) {
                // a hint only
            }
        }
        moving = true;
    }

    @Override
    public void onDetachedFromSurface() {
        if (swapChain != null) {
            engine.destroySwapChain(swapChain);
            engine.flushAndWait();
            swapChain = null;
        }
    }

    @Override
    public void onResized(int w, int h) {
        Trail.step("resized " + w + "x" + h);
        filamentView.setViewport(new Viewport(0, 0, w, h));
        if (stage != null) stage.resize(w, h);
        moving = true;
    }

    /** Frees the engine and everything in it. The host cannot be used after. */
    public void destroy() {
        if (destroyed) return;
        if (stage != null) stage.detach(this);
        stage = null;
        ui.detach();
        if (swapChain != null) engine.destroySwapChain(swapChain);
        swapChain = null;
        engine.destroyRenderer(renderer);
        engine.destroyView(filamentView);
        engine.destroyScene(scene);
        engine.destroyCameraComponent(cameraEntity);
        EntityManager.get().destroy(cameraEntity);
        engine.destroy();
        destroyed = true;
    }
}
