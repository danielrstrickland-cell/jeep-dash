package com.strickland.jeepdash.scene;

import com.google.android.filament.Box;
import com.google.android.filament.Engine;
import com.google.android.filament.EntityManager;
import com.google.android.filament.RenderableManager;
import com.google.android.filament.Scene;
import com.google.android.filament.TransformManager;
import com.google.android.filament.gltfio.AssetLoader;
import com.google.android.filament.gltfio.FilamentAsset;
import com.google.android.filament.gltfio.ResourceLoader;
import com.google.android.filament.gltfio.UbershaderProvider;
import com.strickland.jeepdash.core.Mat4;
import com.strickland.jeepdash.core.TruckPose;
import com.strickland.jeepdash.core.TruckRig;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A truck from a GLB file in a Filament scene: loaded with gltfio (textures decode in the background and parts
 * appear as they are ready), its moving parts found by TruckRig, and posed each frame by TruckPose: placed on the
 * origin, body rolled and pitched, wheels spun and steered, hood and liftgate opened. Any scene can use it.
 */
public final class TruckModel {
    /** The truck's length in the scene; the Holo look's lights and rings are sized for it. */
    public static final double LENGTH = 4.0;

    private final Engine engine;
    private final Scene scene;
    private final UbershaderProvider materials;
    private final AssetLoader loader;
    private final ResourceLoader resources;
    private final FilamentAsset asset;
    private final TransformManager tm;
    private final int[] entities, instances;
    private final double[][] world0, parentWorld0;
    private final int rootInstance;
    private final int[] ready = new int[64];
    public final TruckRig rig;
    private boolean loaded, destroyed;

    private TruckModel(Engine engine, Scene scene, UbershaderProvider materials, AssetLoader loader, ResourceLoader resources,
                       FilamentAsset asset) {
        this.engine = engine;
        this.scene = scene;
        this.materials = materials;
        this.loader = loader;
        this.resources = resources;
        this.asset = asset;
        tm = engine.getTransformManager();
        entities = asset.getEntities();
        instances = new int[entities.length];
        world0 = new double[entities.length][];
        parentWorld0 = new double[entities.length][];
        Map<Integer, Integer> index = new HashMap<>();
        for (int i = 0; i < entities.length; i++) index.put(entities[i], i);
        rootInstance = tm.getInstance(asset.getRoot());
        RenderableManager rm = engine.getRenderableManager();
        float[] m = new float[16];
        Box box = new Box();
        List<double[]> mins = new ArrayList<>(), maxs = new ArrayList<>();
        int[] parent = new int[entities.length];
        for (int i = 0; i < entities.length; i++) {
            int inst = tm.getInstance(entities[i]);
            instances[i] = inst;
            world0[i] = inst == 0 ? Mat4.identity() : Mat4.fromFloat(tm.getWorldTransform(inst, m));
            int pe = inst == 0 ? 0 : tm.getParent(inst);
            Integer pi = index.get(pe);
            parent[i] = pi == null ? -1 : pi;
            int pInst = pe == 0 ? 0 : tm.getInstance(pe);
            parentWorld0[i] = pInst == 0 ? Mat4.identity() : Mat4.fromFloat(tm.getWorldTransform(pInst, m));
            double[] lo = null, hi = null;
            int ri = rm.getInstance(entities[i]);
            if (ri != 0) {
                rm.getAxisAlignedBoundingBox(ri, box);
                float[] c = box.getCenter(), h = box.getHalfExtent();
                lo = new double[]{Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
                hi = new double[]{-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
                for (int k = 0; k < 8; k++) {
                    double[] p = Mat4.point(world0[i], new double[]{c[0] + ((k & 1) == 0 ? -h[0] : h[0]),
                            c[1] + ((k & 2) == 0 ? -h[1] : h[1]), c[2] + ((k & 4) == 0 ? -h[2] : h[2])});
                    for (int a = 0; a < 3; a++) {
                        lo[a] = Math.min(lo[a], p[a]);
                        hi[a] = Math.max(hi[a], p[a]);
                    }
                }
            }
            mins.add(lo);
            maxs.add(hi);
        }
        // a node's extent includes everything under it
        for (int i = 0; i < entities.length; i++) {
            double[] lo = mins.get(i), hi = maxs.get(i);
            if (lo == null) continue;
            for (int p = parent[i]; p >= 0; p = parent[p]) {
                double[] plo = mins.get(p), phi = maxs.get(p);
                if (plo == null) {
                    mins.set(p, lo.clone());
                    maxs.set(p, hi.clone());
                } else {
                    for (int a = 0; a < 3; a++) {
                        plo[a] = Math.min(plo[a], lo[a]);
                        phi[a] = Math.max(phi[a], hi[a]);
                    }
                }
            }
        }
        List<TruckRig.Part> parts = new ArrayList<>();
        for (int i = 0; i < entities.length; i++) {
            String name = asset.getName(entities[i]);
            double[] w = world0[i];
            parts.add(new TruckRig.Part(name == null ? "" : name, parent[i], new double[]{w[12], w[13], w[14]}, mins.get(i), maxs.get(i)));
        }
        rig = TruckRig.build(parts);
    }

    /**
     * Starts loading a GLB (a direct buffer holding the whole file) into the scene. Throws IllegalArgumentException
     * with a sentence for the owner when Filament cannot read it.
     */
    public static TruckModel load(Engine engine, Scene scene, ByteBuffer glb) {
        UbershaderProvider materials = new UbershaderProvider(engine);
        AssetLoader loader = new AssetLoader(engine, materials, EntityManager.get());
        ResourceLoader resources = new ResourceLoader(engine);
        FilamentAsset asset = loader.createAsset(glb);
        if (asset == null) {
            resources.destroy();
            loader.destroy();
            materials.destroyMaterials();
            materials.destroy();
            throw new IllegalArgumentException("The 3D view could not read that model.");
        }
        String[] uris = asset.getResourceUris();
        if (uris != null && uris.length > 0) {
            loader.destroyAsset(asset);
            resources.destroy();
            loader.destroy();
            materials.destroyMaterials();
            materials.destroy();
            throw new IllegalArgumentException("That model needs files beside it. Export it as one .glb with everything packed in.");
        }
        resources.asyncBeginLoad(asset);
        asset.releaseSourceData();
        return new TruckModel(engine, scene, materials, loader, resources, asset);
    }

    /** Call every frame while loading: finishes textures and adds parts to the scene as they become ready. */
    public boolean update() {
        if (destroyed || loaded) return false;
        resources.asyncUpdateLoad();
        int n;
        while ((n = asset.popRenderables(ready)) > 0) {
            int[] some = new int[n];
            System.arraycopy(ready, 0, some, 0, n);
            scene.addEntities(some);
        }
        if (resources.asyncGetLoadProgress() >= 1f) {
            while ((n = asset.popRenderables(ready)) > 0) {
                int[] some = new int[n];
                System.arraycopy(ready, 0, some, 0, n);
                scene.addEntities(some);
            }
            loaded = true;
        }
        return true;
    }

    public boolean loaded() { return loaded; }

    /** Ultra: the truck throws its shadow on the projector base. */
    public void setCastShadows(boolean on) {
        if (destroyed) return;
        RenderableManager rm = engine.getRenderableManager();
        for (int e : asset.getRenderableEntities()) {
            int i = rm.getInstance(e);
            if (i != 0) {
                rm.setCastShadows(i, on);
                rm.setReceiveShadows(i, on);
            }
        }
    }

    /**
     * Poses the truck: turned by yaw (radians) on the origin, body roll and pitch (degrees), front wheels steered
     * (radians, positive left), all wheels turned (radians), hood and liftgate open (radians).
     */
    public void pose(double yaw, double rollDeg, double pitchDeg, double steer, double spin, double hood, double gate) {
        if (destroyed) return;
        tm.setTransform(rootInstance, Mat4.toFloat(TruckPose.root(rig, LENGTH, yaw, rollDeg, pitchDeg)));
        double[] body = TruckPose.body(rig, rollDeg, pitchDeg);
        if (rig.hasWheels()) {
            for (int c = 0; c < 4; c++) {
                TruckRig.Corner corner = rig.wheels[c];
                double st = c == TruckRig.FL || c == TruckRig.FR ? steer : 0;
                for (int i : corner.parts)
                    set(i, TruckPose.wheelLocal(corner, world0[i], parentWorld0[i], body, st, spin, rig));
            }
        }
        for (int i : rig.hood) set(i, TruckPose.hinge(world0[i], parentWorld0[i], rig.hoodPivot, rig.left, -hood));
        for (int i : rig.gate) set(i, TruckPose.hinge(world0[i], parentWorld0[i], rig.gatePivot, rig.left, gate));
    }

    private void set(int i, double[] local) {
        if (instances[i] != 0) tm.setTransform(instances[i], Mat4.toFloat(local));
    }

    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        scene.removeEntities(asset.getEntities());
        scene.removeEntity(asset.getRoot());
        resources.asyncCancelLoad();
        loader.destroyAsset(asset);
        resources.destroy();
        loader.destroy();
        materials.destroyMaterials();
        materials.destroy();
    }
}
