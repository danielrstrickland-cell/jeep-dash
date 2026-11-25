package com.strickland.jeepdash.scene;

import com.google.android.filament.Box;
import com.google.android.filament.Engine;
import com.google.android.filament.EntityManager;
import com.google.android.filament.IndexBuffer;
import com.google.android.filament.Material;
import com.google.android.filament.MaterialInstance;
import com.google.android.filament.RenderableManager;
import com.google.android.filament.Scene;
import com.google.android.filament.VertexBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Ultra's soft shadow on the projector base: an invisible square of floor under the truck whose material
 * (tools/materials/shadow_catcher.mat) shows only the shadow falling on it, over the 2D platform behind.
 */
final class ShadowFloor {
    private final Material material;
    private final MaterialInstance instance;
    private final VertexBuffer vertices;
    private final IndexBuffer indices;
    private final int entity;

    ShadowFloor(Engine e, Scene scene, byte[] filamat, float half) {
        ByteBuffer mat = ByteBuffer.allocateDirect(filamat.length);
        mat.put(filamat);
        mat.flip();
        material = new Material.Builder().payload(mat, filamat.length).build(e);
        instance = material.createInstance();
        instance.setParameter("strength", 0.6f);
        // four corners: position, then the tangent frame as a quaternion (normal straight up)
        float q = (float) Math.sqrt(0.5);
        float[] v = {
                -half, 0, -half, -q, 0, 0, q,
                half, 0, -half, -q, 0, 0, q,
                half, 0, half, -q, 0, 0, q,
                -half, 0, half, -q, 0, 0, q};
        ByteBuffer vb = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder());
        for (float f : v) vb.putFloat(f);
        vb.flip();
        vertices = new VertexBuffer.Builder()
                .bufferCount(1)
                .vertexCount(4)
                .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 28)
                .attribute(VertexBuffer.VertexAttribute.TANGENTS, 0, VertexBuffer.AttributeType.FLOAT4, 12, 28)
                .build(e);
        vertices.setBufferAt(e, 0, vb);
        ByteBuffer ib = ByteBuffer.allocateDirect(12).order(ByteOrder.nativeOrder());
        for (short s : new short[]{0, 2, 1, 0, 3, 2}) ib.putShort(s);
        ib.flip();
        indices = new IndexBuffer.Builder().indexCount(6).bufferType(IndexBuffer.Builder.IndexType.USHORT).build(e);
        indices.setBuffer(e, ib);
        entity = EntityManager.get().create();
        new RenderableManager.Builder(1)
                .boundingBox(new Box(0, 0, 0, half, 0.01f, half))
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertices, indices)
                .material(0, instance)
                .culling(false)
                .castShadows(false)
                .receiveShadows(true)
                .build(e, entity);
        scene.addEntity(entity);
    }

    void destroy(Engine e, Scene scene) {
        scene.removeEntity(entity);
        e.destroyEntity(entity);
        EntityManager.get().destroy(entity);
        e.destroyVertexBuffer(vertices);
        e.destroyIndexBuffer(indices);
        e.destroyMaterialInstance(instance);
        e.destroyMaterial(material);
    }
}
