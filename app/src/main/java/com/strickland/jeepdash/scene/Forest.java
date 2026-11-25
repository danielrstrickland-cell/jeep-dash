package com.strickland.jeepdash.scene;

import com.google.android.filament.Engine;
import com.google.android.filament.EntityManager;
import com.google.android.filament.Scene;
import com.google.android.filament.TransformManager;
import com.google.android.filament.gltfio.AssetLoader;
import com.google.android.filament.gltfio.FilamentAsset;
import com.google.android.filament.gltfio.ResourceLoader;
import com.google.android.filament.gltfio.UbershaderProvider;
import com.strickland.jeepdash.core.TrailWorld;

import java.nio.ByteBuffer;

/** The night trail's floor, trail pieces and pines in the 3D view (made by TrailWorld), moved each frame. */
final class Forest {
    private final Engine engine;
    private final Scene scene;
    private final UbershaderProvider materials;
    private final AssetLoader loader;
    private final FilamentAsset asset;
    private final int[] pieces = new int[TrailWorld.SEGMENTS + TrailWorld.TREES];
    private final float[][] transforms = new float[pieces.length][16];

    Forest(Engine e, Scene s) {
        engine = e;
        scene = s;
        Trail.step("forest build");
        byte[] glb = TrailWorld.glb();
        ByteBuffer buf = ByteBuffer.allocateDirect(glb.length);
        buf.put(glb);
        buf.flip();
        materials = new UbershaderProvider(e);
        loader = new AssetLoader(e, materials, EntityManager.get());
        asset = loader.createAsset(buf);
        if (asset == null) {
            loader.destroy();
            materials.destroyMaterials();
            materials.destroy();
            throw new IllegalStateException("trail scene did not load");
        }
        // no textures: the plain loader finishes at once, then the source can go
        ResourceLoader res = new ResourceLoader(e);
        res.loadResources(asset);
        res.destroy();
        asset.releaseSourceData();
        for (int i = 0; i < TrailWorld.SEGMENTS; i++) pieces[i] = asset.getFirstEntityByName(TrailWorld.segName(i));
        for (int j = 0; j < TrailWorld.TREES; j++)
            pieces[TrailWorld.SEGMENTS + j] = asset.getFirstEntityByName(TrailWorld.treeName(j));
        scene.addEntities(asset.getEntities());
        Trail.step("forest in");
    }

    /** Moves every piece to where the trail world says. */
    void place(TrailWorld w) {
        w.place(transforms);
        TransformManager tm = engine.getTransformManager();
        for (int i = 0; i < pieces.length; i++) {
            if (pieces[i] == 0) continue;
            int ti = tm.getInstance(pieces[i]);
            if (ti != 0) tm.setTransform(ti, transforms[i]);
        }
    }

    void destroy() {
        scene.removeEntities(asset.getEntities());
        loader.destroyAsset(asset);
        loader.destroy();
        materials.destroyMaterials();
        materials.destroy();
    }
}
