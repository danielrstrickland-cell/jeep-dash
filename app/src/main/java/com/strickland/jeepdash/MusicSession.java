package com.strickland.jeepdash;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;

import com.strickland.jeepdash.core.NowPlaying;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Follows what the music app (Spotify) is playing, for the music button's window, and sends it previous,
 * play/pause and next. Android only shows another app's media session to an app with notification access (see
 * MediaListener). Runs only while a screen with the music button is visible. Everything on the main thread.
 */
final class MusicSession implements MediaSessionManager.OnActiveSessionsChangedListener {
    static final String PREF = "music_button";
    private static final String OLD_BAR_PREF = "now_playing";       // the always-on bar this replaced

    private final Context context;
    private final Runnable onChange;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<MediaController> watched = new ArrayList<>();
    private MediaController followed;
    private MediaSessionManager sessions;
    private boolean access, running;

    private final MediaController.Callback changes = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { choose(); }
        @Override public void onPlaybackStateChanged(PlaybackState s) { choose(); }
        @Override public void onSessionDestroyed() { reload(); }
    };

    /** onChange runs after anything the window shows has changed. */
    MusicSession(Context c, Runnable onChange) {
        context = c;
        this.onChange = onChange;
    }

    /** Whether the music button is on. An old now-playing bar that was on turns it on, once, and is forgotten. */
    static boolean buttonOn(Context c) {
        SharedPreferences p = c.getSharedPreferences(DashApp.PREFS, Context.MODE_PRIVATE);
        Boolean saved = p.contains(PREF) ? p.getBoolean(PREF, false) : null;
        String old = p.getString(OLD_BAR_PREF, null);
        boolean on = NowPlaying.buttonOn(saved, old);
        if (old != null) p.edit().putBoolean(PREF, on).remove(OLD_BAR_PREF).apply();
        return on;
    }

    static void setButtonOn(Context c, boolean on) {
        c.getSharedPreferences(DashApp.PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply();
        MediaListener.enable(c, on);
    }

    /** True when the owner has given the app notification access (needed to see another app's music). */
    static boolean hasAccess(Context c) {
        ComponentName me = MediaListener.component(c);
        if (Build.VERSION.SDK_INT >= 27) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            return nm != null && nm.isNotificationListenerAccessGranted(me);
        }
        String on = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return on != null && on.contains(me.flattenToString());
    }

    /** The system screen where notification access is given (this app's own switch where Android has one). */
    static void openAccessSettings(Context c) {
        try {
            Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            if (Build.VERSION.SDK_INT >= 30) {
                i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, MediaListener.component(c).flattenToString());
            }
            if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (RuntimeException e) {
            try {
                c.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) {
                // this device has no such screen
            }
        }
    }

    /** This app's own info screen: where Android 13+ lets a side-loaded app's "restricted settings" be allowed. */
    static void openAppInfo(Context c) {
        try {
            c.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) { }
    }

    /**
     * Tells the owner notification access is missing and takes them there. An app installed from a downloaded
     * APK has the switch greyed out on Android 13+ until "Allow restricted settings" is chosen on its app info
     * screen, which is the usual reason the music window stays empty, so that is offered too.
     */
    static void promptAccess(Context c) {
        if (!(c instanceof Activity) || ((Activity) c).isFinishing()) { openAccessSettings(c); return; }
        new AlertDialog.Builder(c)
                .setTitle("Notification access needed")
                .setMessage("The music window needs notification access for Jeep Dash to see and control Spotify.\n\n"
                        + "1. Tap \"Open access screen\" and switch Jeep Dash on.\n"
                        + "2. If the switch is greyed out (Android 13+, app installed from a download), tap \"App info\", "
                        + "open the three-dot menu, choose \"Allow restricted settings\", then try step 1 again.")
                .setPositiveButton("Open access screen", (d, w) -> openAccessSettings(c))
                .setNeutralButton("App info", (d, w) -> openAppInfo(c))
                .setNegativeButton("Not now", null)
                .show();
    }

    /** A tap on the window's own text: with no access it asks for it. */
    void windowTap() {
        if (!access) promptAccess(context);
    }

    void start() {
        if (running) return;
        running = true;
        MediaListener.enable(context, true);      // the listener must be switched on whatever path turned the button on
        sessions = context.getSystemService(MediaSessionManager.class);
        try {
            if (sessions == null) throw new SecurityException("no media sessions");
            sessions.addOnActiveSessionsChangedListener(this, MediaListener.component(context), main);
            access = true;
            onActiveSessionsChanged(sessions.getActiveSessions(MediaListener.component(context)));
        } catch (SecurityException e) {
            access = false;
            unwatch();
            onChange.run();
        }
    }

    void stop() {
        if (!running) return;
        running = false;
        try {
            if (sessions != null && access) sessions.removeOnActiveSessionsChangedListener(this);
        } catch (RuntimeException ignored) { }
        unwatch();
    }

    @Override
    public void onActiveSessionsChanged(List<MediaController> list) {
        unwatch();
        if (list != null) watched.addAll(list);
        for (MediaController c : watched) {
            try { c.registerCallback(changes, main); } catch (RuntimeException ignored) { }
        }
        choose();
    }

    private void reload() {
        if (!running || !access) return;
        try {
            onActiveSessionsChanged(sessions.getActiveSessions(MediaListener.component(context)));
        } catch (SecurityException e) {
            access = false;
            onActiveSessionsChanged(Collections.<MediaController>emptyList());
        }
    }

    private void unwatch() {
        for (MediaController c : watched) {
            try { c.unregisterCallback(changes); } catch (RuntimeException ignored) { }
        }
        watched.clear();
        followed = null;
    }

    /** Follows the most recently playing session (NowPlaying.pick). */
    private void choose() {
        boolean[] playing = new boolean[watched.size()];
        long[] since = new long[watched.size()];
        for (int i = 0; i < playing.length; i++) {
            PlaybackState s = watched.get(i).getPlaybackState();
            playing[i] = s != null && s.getState() == PlaybackState.STATE_PLAYING;
            since[i] = s == null ? 0 : s.getLastPositionUpdateTime();
        }
        int i = NowPlaying.pick(playing, since);
        followed = i < 0 ? null : watched.get(i);
        onChange.run();
    }

    boolean access() { return access; }

    boolean hasSession() { return followed != null; }

    String title() { return meta(MediaMetadata.METADATA_KEY_TITLE); }

    String artist() {
        String a = meta(MediaMetadata.METADATA_KEY_ARTIST);
        return a != null ? a : meta(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
    }

    private String meta(String key) {
        MediaController c = followed;
        MediaMetadata m = c == null ? null : c.getMetadata();
        return m == null ? null : m.getString(key);
    }

    boolean playing() {
        MediaController c = followed;
        PlaybackState s = c == null ? null : c.getPlaybackState();
        return s != null && s.getState() == PlaybackState.STATE_PLAYING;
    }

    /** -1 previous, 0 play/pause, 1 next. Without access it opens the screen where access is given. */
    void transport(int which) {
        if (!access) {
            promptAccess(context);
            return;
        }
        MediaController c = followed;
        if (c == null) {
            if (which == 0) pressPlay();      // a play key wakes the music app that played last
            return;
        }
        try {
            MediaController.TransportControls t = c.getTransportControls();
            if (which < 0) t.skipToPrevious();
            else if (which > 0) t.skipToNext();
            else if (playing()) t.pause();
            else t.play();
        } catch (RuntimeException ignored) {
            // the music app went away between the tap and now
        }
    }

    private void pressPlay() {
        AudioManager am = context.getSystemService(AudioManager.class);
        if (am == null) return;
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY));
    }
}
