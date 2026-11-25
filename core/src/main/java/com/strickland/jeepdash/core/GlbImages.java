package com.strickland.jeepdash.core;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The pictures packed in a GLB, and a copy of the file with some of them swapped for other bytes (the app uses it
 * to make a 2K-texture copy of a model for the High quality mode). Every packed part after a changed picture
 * moves up; the scene description is rewritten to match.
 */
public final class GlbImages {
    private GlbImages() { }

    private static final int MAGIC = 0x46546C67, JSON_CHUNK = 0x4E4F534A, BIN_CHUNK = 0x004E4942;

    public static final class Image {
        public final int index;
        public final String mime;
        /** Where its bytes are in the whole file, and how many. */
        public final int offset, length;

        Image(int index, String mime, int offset, int length) {
            this.index = index; this.mime = mime; this.offset = offset; this.length = length;
        }
    }

    /** The scene description (JSON) of a GLB. */
    public static String json(byte[] glb) {
        ByteBuffer b = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0) != MAGIC || b.getInt(16) != JSON_CHUNK) throw new IllegalArgumentException("not a GLB");
        return new String(glb, 20, b.getInt(12), StandardCharsets.UTF_8);
    }

    private static int binStart(byte[] glb) {
        ByteBuffer b = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
        int at = 20 + b.getInt(12);
        if (at + 8 > glb.length || b.getInt(at + 4) != BIN_CHUNK) return -1;
        return at + 8;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> root(byte[] glb) { return (Map<String, Object>) Json.parse(json(glb)); }

    private static int num(Object o, int dflt) { return o instanceof Number ? ((Number) o).intValue() : dflt; }

    /** Pictures stored inside the file (not ones it points to elsewhere). */
    public static List<Image> list(byte[] glb) {
        Map<String, Object> root = root(glb);
        int bin = binStart(glb);
        List<Image> out = new ArrayList<>();
        List<?> images = (List<?>) root.get("images"), views = (List<?>) root.get("bufferViews");
        if (images == null || views == null || bin < 0) return out;
        for (int i = 0; i < images.size(); i++) {
            Map<?, ?> im = (Map<?, ?>) images.get(i);
            int v = num(im.get("bufferView"), -1);
            if (v < 0 || v >= views.size()) continue;
            Map<?, ?> view = (Map<?, ?>) views.get(v);
            if (num(view.get("buffer"), 0) != 0) continue;
            Object mime = im.get("mimeType");
            out.add(new Image(i, mime == null ? "" : mime.toString(), bin + num(view.get("byteOffset"), 0), num(view.get("byteLength"), 0)));
        }
        return out;
    }

    /** A new GLB with the given pictures' bytes (by image index) put in; null if the file is laid out oddly. */
    @SuppressWarnings("unchecked")
    public static byte[] replace(byte[] glb, Map<Integer, byte[]> newImages) {
        Map<String, Object> root = root(glb);
        int bin = binStart(glb);
        List<Object> views = (List<Object>) root.get("bufferViews");
        List<Object> images = (List<Object>) root.get("images");
        if (bin < 0 || views == null) return null;
        Map<Integer, byte[]> byView = new TreeMap<>();
        if (images != null) {
            for (Map.Entry<Integer, byte[]> e : newImages.entrySet()) {
                Map<?, ?> im = (Map<?, ?>) images.get(e.getKey());
                byView.put(num(im.get("bufferView"), -1), e.getValue());
            }
        }
        // the views in the order they sit in the binary part; overlapping views are left to tools that know better
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < views.size(); i++) if (num(((Map<?, ?>) views.get(i)).get("buffer"), 0) == 0) order.add(i);
        order.sort((a, c) -> Integer.compare(num(((Map<?, ?>) views.get(a)).get("byteOffset"), 0),
                num(((Map<?, ?>) views.get(c)).get("byteOffset"), 0)));
        int end = -1;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int v : order) {
            Map<String, Object> view = (Map<String, Object>) views.get(v);
            int off = num(view.get("byteOffset"), 0), len = num(view.get("byteLength"), 0);
            if (off < end) return null;
            end = off + len;
            byte[] data = byView.get(v);
            while (out.size() % 4 != 0) out.write(0);
            view.put("byteOffset", (long) out.size());
            if (data != null) {
                out.write(data, 0, data.length);
                view.put("byteLength", (long) data.length);
            } else {
                out.write(glb, bin + off, len);
            }
        }
        while (out.size() % 4 != 0) out.write(0);
        byte[] binary = out.toByteArray();
        List<Object> buffers = (List<Object>) root.get("buffers");
        if (buffers != null && !buffers.isEmpty()) ((Map<String, Object>) buffers.get(0)).put("byteLength", (long) binary.length);
        byte[] j = Json.write(root).getBytes(StandardCharsets.UTF_8);
        int jp = (j.length + 3) & ~3;
        ByteBuffer b = ByteBuffer.allocate(12 + 8 + jp + 8 + binary.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(MAGIC).putInt(2).putInt(b.capacity());
        b.putInt(jp).putInt(JSON_CHUNK).put(j);
        for (int k = j.length; k < jp; k++) b.put((byte) ' ');
        b.putInt(binary.length).putInt(BIN_CHUNK).put(binary);
        return b.array();
    }
}
