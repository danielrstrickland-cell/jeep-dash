package com.strickland.jeepdash.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class TrailWorldTest {
    public static void theGlbIsWellFormed() {
        byte[] g = TrailWorld.glb();
        T.ok(Glb.check(g, g.length) == null, "passes the same check as a truck model");
        ByteBuffer b = ByteBuffer.wrap(g).order(ByteOrder.LITTLE_ENDIAN);
        T.eq(g.length, b.getInt(8), "length in the header");
        int jsLen = b.getInt(12);
        String json = new String(g, 20, jsLen, StandardCharsets.UTF_8).trim();
        T.ok(json.contains("\"" + TrailWorld.segName(TrailWorld.SEGMENTS - 1) + "\"") && json.contains("\"" + TrailWorld.treeName(TrailWorld.TREES - 1) + "\""),
                "every piece and tree is a named node");
        int binLen = b.getInt(20 + jsLen);
        T.eq(0x004E4942, b.getInt(24 + jsLen), "binary chunk");
        T.ok(json.contains("\"byteLength\":" + binLen + "}]"), "buffer size matches its chunk");
    }

    public static void driveMovesTheForestTowardTheCamera() {
        TrailWorld w = new TrailWorld();
        float[][] a = new float[TrailWorld.SEGMENTS + TrailWorld.TREES][16], b = new float[a.length][16];
        w.place(a);
        w.step(0.1, 10, 0);
        w.place(b);
        int tree = TrailWorld.SEGMENTS + 3;
        double moved = b[tree][14] - a[tree][14];
        T.ok(Math.abs(moved - 1.0) < 1e-4 || moved < -50, "a tree comes a metre closer (or wraps to the far end): " + moved);
        for (float[] m : b) for (float v : m) T.ok(Float.isFinite(v), "finite");
    }

    public static void aLeftTurnBendsTheTrailLeft() {
        TrailWorld w = new TrailWorld();
        for (int i = 0; i < 50; i++) w.step(0.1, 8, 20);
        T.ok(w.sideAt(40) < -1, "40 m ahead the trail is off to the left: " + w.sideAt(40));
        T.ok(w.sideAt(-5) == 0, "behind the truck it stays straight");
        float[][] m = new float[TrailWorld.SEGMENTS + TrailWorld.TREES][16];
        w.place(m);
        float[] far = m[TrailWorld.SEGMENTS - 1];
        // the far piece's far end (local 0,0,-SEG_LEN) lies further left than its near end
        double farEndX = far[12] + far[8] * -TrailWorld.SEG_LEN;
        T.ok(farEndX < far[12], "pieces turn to follow the bend");
        TrailWorld still = new TrailWorld();
        still.step(0.1, 0, 0);
        T.ok(!still.moving(0), "parked and straight: nothing to redraw");
    }
}
