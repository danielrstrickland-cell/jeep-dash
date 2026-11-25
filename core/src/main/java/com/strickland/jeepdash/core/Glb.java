package com.strickland.jeepdash.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** A quick look at a file picked as the truck model, before the 3D view is asked to load it. */
public final class Glb {
    private Glb() { }

    private static final int MAGIC = 0x46546C67, JSON = 0x4E4F534A;

    /**
     * Null when the start of the file (at least 20 bytes) looks like a complete GLB (glTF 2 binary); otherwise a
     * sentence for the owner saying what is wrong.
     */
    public static String check(byte[] head, long fileSize) {
        if (head == null || head.length < 20 || fileSize < 20) return "That file is too small to be a truck model.";
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0) != MAGIC) {
            if (head[0] == '{') return "That is a .gltf text file. Export the model as one .glb (glTF binary) file instead.";
            return "That is not a .glb (glTF binary) file.";
        }
        int version = b.getInt(4);
        if (version != 2) return "That model is glTF version " + version + "; the dash needs version 2. Export it again as .glb.";
        long declared = b.getInt(8) & 0xffffffffL;
        if (declared > fileSize) return "The file is cut short: it says " + declared + " bytes but has " + fileSize + ".";
        if (b.getInt(16) != JSON) return "That is not a .glb (glTF binary) file: its first part is not the scene.";
        return null;
    }
}
