package com.strickland.jeepdash.scene;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.strickland.jeepdash.core.Glb;
import com.strickland.jeepdash.core.GlbImages;
import com.strickland.jeepdash.core.QualityMode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Map;

/**
 * The truck models: the owner's own GLB files, picked on the tablet and copied into the app's private storage
 * (they are licensed for his own use, so they are never in the app or its source): the full-detail one, an
 * optional lite one for Power saver, and a 2K-texture copy of the full one made at import for High when its
 * textures are bigger. Without any, the built-in stand-in truck. Which one a mode shows: QualityMode.model.
 */
public final class ModelStore {
    private ModelStore() { }

    static final String STAND_IN = "scene/zj-standin.glb";
    static final int TEXTURE_CAP = 2048;
    private static final String PREFS = "truck";

    private static File dir(Context c) { return new File(c.getFilesDir(), "truck"); }

    private static File file(Context c, String kind) { return new File(dir(c), kind + ".glb"); }

    public static boolean has(Context c, String kind) { return file(c, kind).isFile(); }

    /** The picked file's name for the full or lite model; null when there is none. */
    public static String name(Context c, String kind) {
        return has(c, kind) ? c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(kind, "your model") : null;
    }

    /** Which file this mode shows (QualityMode.FULL, TWO_K, LITE or STAND_IN). */
    public static String pick(Context c, String mode) {
        return QualityMode.model(mode, has(c, QualityMode.FULL), has(c, QualityMode.TWO_K), has(c, QualityMode.LITE));
    }

    /**
     * Copies a picked file in as the full or lite model, after checking it is a whole GLB; for the full one also
     * makes the 2K copy. Returns null, or a sentence saying what is wrong. Slow: not on the main thread.
     */
    public static String importFrom(Context c, Uri uri, String kind) {
        String name = "your model";
        try (Cursor q = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (q != null && q.moveToFirst() && !q.isNull(0)) name = q.getString(0);
        } catch (RuntimeException ignored) {
            // no name: a plain one will do
        }
        File d = dir(c);
        if (!d.isDirectory() && !d.mkdirs()) return "Could not make room for the model on the tablet.";
        File tmp = new File(d, "incoming.glb");
        byte[] head = new byte[20];
        int headLen = 0;
        long copied = 0;
        try (InputStream in = c.getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) return "Could not open that file.";
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (headLen < head.length) {
                    int k = Math.min(n, head.length - headLen);
                    System.arraycopy(buf, 0, head, headLen, k);
                    headLen += k;
                }
                out.write(buf, 0, n);
                copied += n;
            }
        } catch (IOException | RuntimeException e) {
            tmp.delete();
            return "Could not read that file: " + e.getMessage();
        }
        String bad = Glb.check(headLen == head.length ? head : null, copied);
        if (bad != null) {
            tmp.delete();
            return bad;
        }
        File dest = file(c, kind);
        if (dest.exists() && !dest.delete() || !tmp.renameTo(dest)) {
            tmp.delete();
            return "Could not keep the model on the tablet.";
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(kind, name).apply();
        if (QualityMode.FULL.equals(kind)) makeTwoK(c, dest);
        return null;
    }

    /** The 2K copy for High: pictures over 2048 pixels halved (or more) until they fit. Skipped if none are bigger. */
    private static void makeTwoK(Context c, File full) {
        File out = file(c, QualityMode.TWO_K);
        out.delete();
        try {
            byte[] glb = new byte[(int) full.length()];
            try (InputStream in = new FileInputStream(full)) { readFully(in, glb); }
            Map<Integer, byte[]> smaller = new HashMap<>();
            for (GlbImages.Image im : GlbImages.list(glb)) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(glb, im.offset, im.length, o);
                int big = Math.max(o.outWidth, o.outHeight);
                if (big <= TEXTURE_CAP) continue;
                int sample = 1;
                while (big / sample > TEXTURE_CAP) sample *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                Bitmap b = BitmapFactory.decodeByteArray(glb, im.offset, im.length, o);
                if (b == null) continue;
                ByteArrayOutputStream enc = new ByteArrayOutputStream();
                boolean jpeg = im.mime.contains("jpeg") || im.mime.contains("jpg");
                b.compress(jpeg ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG, 92, enc);
                b.recycle();
                smaller.put(im.index, enc.toByteArray());
            }
            if (smaller.isEmpty()) return;
            byte[] copy = GlbImages.replace(glb, smaller);
            if (copy == null) return;
            try (OutputStream o = new FileOutputStream(out)) { o.write(copy); }
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            out.delete();             // High then shows the full model
        }
    }

    /** Forget the full or lite model (forgetting the full one also drops its 2K copy). */
    public static void forget(Context c, String kind) {
        file(c, kind).delete();
        if (QualityMode.FULL.equals(kind)) file(c, QualityMode.TWO_K).delete();
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(kind).apply();
    }

    /** A model, whole, in a direct buffer, read without a copy on the Java heap (large models). Slow: not on the main thread. */
    public static ByteBuffer read(Context c, String kind) throws IOException {
        File f = file(c, kind);
        if (!QualityMode.STAND_IN.equals(kind) && f.isFile()) {
            try (FileInputStream in = new FileInputStream(f)) { return readChannel(in.getChannel(), 0, f.length()); }
        }
        try (AssetFileDescriptor fd = c.getAssets().openFd(STAND_IN); FileInputStream in = fd.createInputStream()) {
            return readChannel(in.getChannel(), fd.getStartOffset(), fd.getLength());
        } catch (IOException e) {
            // stored compressed after all: read it the slow way
            try (InputStream in = c.getAssets().open(STAND_IN)) {
                byte[] data = readAll(in);
                ByteBuffer b = ByteBuffer.allocateDirect(data.length);
                b.put(data);
                b.flip();
                return b;
            }
        }
    }

    private static ByteBuffer readChannel(FileChannel ch, long start, long length) throws IOException {
        ByteBuffer b = ByteBuffer.allocateDirect((int) length);
        long at = start;
        while (b.hasRemaining()) {
            int n = ch.read(b, at);
            if (n <= 0) throw new IOException("short read");
            at += n;
        }
        b.flip();
        return b;
    }

    static byte[] asset(Context c, String path) throws IOException {
        try (InputStream in = c.getAssets().open(path)) { return readAll(in); }
    }

    private static void readFully(InputStream in, byte[] data) throws IOException {
        int at = 0, n;
        while (at < data.length && (n = in.read(data, at, data.length - at)) > 0) at += n;
        if (at < data.length) throw new IOException("short read");
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
