package com.strickland.jeepdash;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.strickland.jeepdash.core.DriveLogWriter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Drive logs live in the tablet's Downloads/JeepDash folder so any file app can see them. */
final class DriveLogStore {
    private DriveLogStore() { }

    static final String FOLDER = Environment.DIRECTORY_DOWNLOADS + "/JeepDash";

    /** Starts a new CSV named drive-YYYYMMDD-HHMM.csv; null on Android 9 and older (no shared Downloads API). */
    static DriveLogWriter open(Context c, List<String> ids) throws IOException {
        if (Build.VERSION.SDK_INT < 29) return null;
        String name = "drive-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".csv";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/comma-separated-values");
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER);
        ContentResolver r = c.getContentResolver();
        Uri uri;
        try {
            uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        } catch (RuntimeException e) {
            throw new IOException("could not create the drive log: " + e);
        }
        if (uri == null) throw new IOException("could not create the drive log");
        OutputStream os = r.openOutputStream(uri, "w");
        if (os == null) throw new IOException("could not open the drive log");
        return new DriveLogWriter(new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8)), ids);
    }

    /** This app's drive logs, newest first. */
    static List<Uri> list(Context c) {
        List<Uri> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT < 29) return out;
        String[] cols = {MediaStore.MediaColumns._ID};
        String where = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND " + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?";
        String[] args = {FOLDER + "%", "drive-%"};
        try (Cursor cur = c.getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cols, where, args,
                MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            if (cur == null) return out;
            while (cur.moveToNext())
                out.add(Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, String.valueOf(cur.getLong(0))));
        } catch (RuntimeException ignored) {
            // no access or no storage: show an empty list rather than crash
        }
        return out;
    }

    /** File name and size for the logs screen, e.g. "drive-20261121-0815.csv  (42 KB)". */
    static String label(Context c, Uri uri) {
        String[] cols = {MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE};
        try (Cursor cur = c.getContentResolver().query(uri, cols, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                long kb = Math.max(1, cur.getLong(1) / 1024);
                return cur.getString(0) + "  (" + kb + " KB)";
            }
        } catch (RuntimeException ignored) { }
        return "drive log";
    }

    /** Saves a text file next to the drive logs; null on Android 9 and older or on failure. */
    static Uri saveText(Context c, String name, String text) {
        if (Build.VERSION.SDK_INT < 29) return null;
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER);
            ContentResolver r = c.getContentResolver();
            Uri uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) return null;
            try (OutputStream os = r.openOutputStream(uri, "w")) {
                if (os == null) return null;
                os.write(text.getBytes(StandardCharsets.UTF_8));
            }
            return uri;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static void shareText(Activity a, Uri uri, String title) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.putExtra(Intent.EXTRA_SUBJECT, title);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        a.startActivity(Intent.createChooser(i, title));
    }

    static void share(Activity a, Uri uri) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/csv");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        a.startActivity(Intent.createChooser(i, "Share drive log"));
    }
}
