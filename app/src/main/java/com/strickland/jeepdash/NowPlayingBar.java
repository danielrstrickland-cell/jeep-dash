package com.strickland.jeepdash;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.strickland.jeepdash.core.NowPlaying;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A slim bar laid over the dash or the visualizer: what the music app (Spotify) is playing, with previous,
 * play/pause and next. Ordinary Android views on top of the drawn screen, so no look has to know about it.
 * It follows the music app's media session, which Android only shows to an app with notification access
 * (see MediaListener). The text rules are in NowPlaying.
 */
final class NowPlayingBar extends LinearLayout implements MediaSessionManager.OnActiveSessionsChangedListener {
    static final String PREF = "now_playing", OFF = "off", TOP = "top", BOTTOM = "bottom";
    static final String[] PLACES = {OFF, TOP, BOTTOM};
    private static final int HEIGHT_DP = 44, MAX_WIDTH_DP = 640, MAX_CHARS = 80;

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextView text;
    private final ImageButton playPause;
    private final List<MediaController> watched = new ArrayList<>();
    private MediaController followed;
    private MediaSessionManager sessions;
    private boolean access, running;

    private final MediaController.Callback changes = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { choose(); }
        @Override public void onPlaybackStateChanged(PlaybackState s) { choose(); }
        @Override public void onSessionDestroyed() { reload(); }
    };

    /** off, top or bottom, as saved. */
    static String place(Context c) {
        String p = c.getSharedPreferences(DashApp.PREFS, Context.MODE_PRIVATE).getString(PREF, OFF);
        return TOP.equals(p) || BOTTOM.equals(p) ? p : OFF;
    }

    /**
     * Puts the bar where the saved setting says, takes it away when off. Returns the bar, or null when off.
     * With the setting off nothing is added to the screen at all.
     */
    static NowPlayingBar sync(Activity a, NowPlayingBar bar) {
        String p = place(a);
        if (OFF.equals(p)) {
            if (bar != null) {
                bar.stop();
                if (bar.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) bar.getParent()).removeView(bar);
            }
            return null;
        }
        float d = a.getResources().getDisplayMetrics().density;
        int w = Math.min(a.getResources().getDisplayMetrics().widthPixels, Math.round(MAX_WIDTH_DP * d));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, Math.round(HEIGHT_DP * d),
                Gravity.CENTER_HORIZONTAL | (TOP.equals(p) ? Gravity.TOP : Gravity.BOTTOM));
        lp.topMargin = lp.bottomMargin = Math.round(4 * d);
        if (bar == null) {
            bar = new NowPlayingBar(a);
            a.addContentView(bar, lp);
        } else {
            bar.setLayoutParams(lp);
        }
        return bar;
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

    /** The system screen where notification access is given. */
    static void openAccessSettings(Context c) {
        try {
            c.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (RuntimeException ignored) {
            // this device has no such screen
        }
    }

    private NowPlayingBar(Activity a) {
        super(a);
        activity = a;
        float d = a.getResources().getDisplayMetrics().density;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(Math.round(14 * d), 0, Math.round(4 * d), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC101418);                      // dark and see-through: readable at night, not glaring
        bg.setStroke(Math.max(1, Math.round(d)), 0x40FFFFFF);
        bg.setCornerRadius(10 * d);
        setBackground(bg);
        setElevation(6 * d);
        setClickable(true);                           // a tap on the bar never reaches the gauge under it

        text = new TextView(a);
        text.setTextColor(0xFFE8ECEF);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setOnClickListener(v -> { if (!access) openAccessSettings(a); });
        addView(text, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
        text.setGravity(Gravity.CENTER_VERTICAL);

        addView(button(android.R.drawable.ic_media_previous, "Previous", v -> transport(-1)));
        playPause = button(android.R.drawable.ic_media_play, "Play or pause", v -> transport(0));
        addView(playPause);
        addView(button(android.R.drawable.ic_media_next, "Next", v -> transport(1)));
        show();
    }

    private ImageButton button(int icon, String name, View.OnClickListener click) {
        float d = getResources().getDisplayMetrics().density;
        ImageButton b = new ImageButton(getContext());
        b.setImageResource(icon);
        b.setColorFilter(Color.WHITE);
        b.setBackground(null);
        b.setContentDescription(name);
        b.setOnClickListener(click);
        b.setLayoutParams(new LayoutParams(Math.round(56 * d), LayoutParams.MATCH_PARENT));    // a big target
        return b;
    }

    /** Starts following the music app; called while the screen is visible. */
    void start() {
        if (running) return;
        running = true;
        sessions = getContext().getSystemService(MediaSessionManager.class);
        try {
            if (sessions == null) throw new SecurityException("no media sessions");
            sessions.addOnActiveSessionsChangedListener(this, MediaListener.component(getContext()), main);
            access = true;
            onActiveSessionsChanged(sessions.getActiveSessions(MediaListener.component(getContext())));
        } catch (SecurityException e) {
            access = false;
            unwatch();
            show();
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

    /** A session went away: ask the system for the list again. */
    private void reload() {
        if (!running || !access) return;
        try {
            onActiveSessionsChanged(sessions.getActiveSessions(MediaListener.component(getContext())));
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

    /** Follows the most recently playing session (NowPlaying.pick), then shows it. */
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
        show();
    }

    private void show() {
        String title = null, artist = null;
        boolean playing = false;
        MediaController c = followed;
        if (c != null) {
            MediaMetadata m = c.getMetadata();
            if (m != null) {
                title = m.getString(MediaMetadata.METADATA_KEY_TITLE);
                artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
                if (artist == null) artist = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            }
            PlaybackState s = c.getPlaybackState();
            playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
        }
        text.setText(NowPlaying.text(access, c != null, title, artist, MAX_CHARS));
        playPause.setImageResource(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
    }

    /** -1 previous, 0 play/pause, 1 next. */
    private void transport(int which) {
        if (!access) {
            openAccessSettings(activity);
            return;
        }
        MediaController c = followed;
        if (c == null) {
            // Nothing open: a play key wakes the music app that played last, as a steering-wheel button would.
            if (which == 0) pressPlay();
            return;
        }
        try {
            MediaController.TransportControls t = c.getTransportControls();
            if (which < 0) t.skipToPrevious();
            else if (which > 0) t.skipToNext();
            else {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) t.pause();
                else t.play();
            }
        } catch (RuntimeException ignored) {
            // the music app went away between the tap and now
        }
    }

    private void pressPlay() {
        AudioManager am = getContext().getSystemService(AudioManager.class);
        if (am == null) return;
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY));
    }
}
