package com.strickland.jeepdash;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import com.strickland.jeepdash.core.DiagLog;
import com.strickland.jeepdash.core.SoundTune;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The lossless player's screen, in the Sound hub's plain dark style: what is playing and in what format,
 * whether the room correction is in the signal, a spectrum of the sound leaving the tablet with the
 * correction drawn over it, the transport, and the list of files in the chosen folder. The playing itself is
 * {@link LosslessPlayer} in the link service, so music carries on when this screen is closed.
 */
public class PlayerActivity extends Activity implements ServiceConnection {
    private static final int REQ_TREE = 6, SCOPE = 4096;
    private static final long TICK_MS = 60;
    private static final int BG = Color.rgb(12, 16, 24), CARD = Color.rgb(22, 28, 40), LINE = Color.rgb(40, 50, 68),
            INK = Color.rgb(226, 232, 242), DIM = Color.rgb(138, 150, 170), GOOD = Color.rgb(96, 214, 150),
            BLUE = Color.rgb(110, 170, 240);

    private final Handler main = new Handler(Looper.getMainLooper());
    private final short[] scope = new short[SCOPE];
    private SharedPreferences prefs;
    private LinkService service;
    private LosslessPlayer player;
    private boolean bound, dragging;
    private List<LosslessPlayer.Track> tracks = new ArrayList<>();
    private int playingRow = -1, ticks;
    private double[] smooth, correction;
    private float density;
    private TextView title, artist, format, filterBadge, timeNow, timeAll, filterNote, empty;
    private Button folder, playPause;
    private SeekBar bar;
    private RtaView graph;
    private TrackAdapter adapter;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            LosslessPlayer p = player;
            if (p != null) {
                drawSpectrum(p);
                if (!dragging) {
                    long all = p.durationMs(), now = p.positionMs();
                    bar.setProgress(all > 0 ? (int) Math.min(1000, now * 1000 / all) : 0);
                    set(timeNow, clock(now));
                    set(timeAll, clock(all));
                }
                if (ticks++ % 8 == 0) showState(p);
            }
            main.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = SoundPrefs.of(this);
        density = getResources().getDisplayMetrics().density;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildScreen();
    }

    @Override
    protected void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, LinkService.class), this, Context.BIND_AUTO_CREATE);
        main.post(refresh);
    }

    @Override
    protected void onStop() {
        main.removeCallbacks(refresh);
        if (bound) {
            unbindService(this);
            bound = false;
        }
        service = null;
        player = null;
        super.onStop();
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((LinkService.LocalBinder) binder).service();
        player = service.player();
        List<LosslessPlayer.Track> queued = player.queue();
        if (!queued.isEmpty()) {
            showTracks(queued);                              // already loaded: the music may be playing
        } else {
            String saved = prefs.getString(SoundPrefs.PLAYER_TREE, null);
            if (saved != null) load(Uri.parse(saved));
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
        player = null;
    }

    // ---------------- screen ----------------

    private int dp(float v) { return (int) (v * density); }

    private TextView label(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private TextView badge(int color) {
        TextView t = label("", 12, color);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(6));
        bg.setStroke(Math.max(1, dp(1)), LINE);
        t.setBackground(bg);
        t.setPadding(dp(8), dp(3), dp(8), dp(3));
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button x = new Button(this);
        x.setText(s);
        x.setAllCaps(false);
        x.setTextSize(15);
        x.setOnClickListener(l);
        return x;
    }

    private static void set(TextView t, String s) {
        if (!s.contentEquals(t.getText())) t.setText(s);
    }

    private static String clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);

        // left: what is playing
        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(16), dp(14), dp(16));
        scroll.addView(col);
        root.addView(scroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.15f));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = label("Lossless player", 20, INK);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        folder = button("Pick music folder", v -> pickFolder());
        folder.setTextSize(13);
        head.addView(folder);
        col.addView(head);

        title = label("Nothing playing", 20, INK);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, dp(12), 0, 0);
        col.addView(title);
        artist = label("", 14, DIM);
        col.addView(artist);

        LinearLayout badges = new LinearLayout(this);
        badges.setPadding(0, dp(8), 0, dp(8));
        format = badge(BLUE);
        badges.addView(format);
        filterBadge = badge(DIM);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gap.leftMargin = dp(8);
        badges.addView(filterBadge, gap);
        col.addView(badges);

        graph = new RtaView(this);
        col.addView(graph, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));
        col.addView(label("Bars: the music leaving the tablet.  Green: the room correction in force.", 12, DIM));

        LinearLayout seek = new LinearLayout(this);
        seek.setGravity(Gravity.CENTER_VERTICAL);
        seek.setPadding(0, dp(10), 0, 0);
        timeNow = label("0:00", 12, DIM);
        seek.addView(timeNow);
        bar = new SeekBar(this);
        bar.setMax(1000);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                LosslessPlayer lp = player;
                if (user && lp != null) set(timeNow, clock(lp.durationMs() * p / 1000));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                LosslessPlayer lp = player;
                if (lp != null && lp.durationMs() > 0) lp.seek(lp.durationMs() * s.getProgress() / 1000);
            }
        });
        seek.addView(bar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        timeAll = label("0:00", 12, DIM);
        seek.addView(timeAll);
        col.addView(seek);

        LinearLayout transport = new LinearLayout(this);
        transport.setGravity(Gravity.CENTER_HORIZONTAL);
        transport.setPadding(0, dp(6), 0, dp(6));
        transport.addView(button("Prev", v -> { if (ready()) player.prev(); }), new LinearLayout.LayoutParams(dp(96), dp(56)));
        playPause = button("Play", v -> { if (ready()) player.toggle(); });
        transport.addView(playPause, new LinearLayout.LayoutParams(dp(132), dp(56)));
        transport.addView(button("Next", v -> { if (ready()) player.next(); }), new LinearLayout.LayoutParams(dp(96), dp(56)));
        col.addView(transport);

        Switch filter = new Switch(this);
        filter.setText("Room correction");
        filter.setTextColor(INK);
        filter.setTextSize(15);
        filter.setChecked(prefs.getBoolean(SoundPrefs.PLAYER_FILTER, true));
        filter.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(SoundPrefs.PLAYER_FILTER, on).apply();
            LinkService s = service;
            if (s != null) s.soundChanged();                 // the player rebuilds its filter at the next block
        });
        col.addView(filter);
        filterNote = label("", 12, DIM);
        col.addView(filterNote);

        // right: the folder's files
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(6), dp(16), dp(14), dp(16));
        empty = label("Pick the folder with your music (the USB stick) and its files are listed here.", 14, DIM);
        empty.setPadding(dp(12), dp(8), dp(12), 0);
        right.addView(empty);
        ListView list = new ListView(this);
        adapter = new TrackAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, row, id) -> { if (ready()) player.playAt(row); });
        right.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        setContentView(root);
    }

    private boolean ready() {
        if (player == null) return false;
        if (!tracks.isEmpty()) return true;
        Toast.makeText(this, "Pick a music folder first", Toast.LENGTH_SHORT).show();
        return false;
    }

    /** The slower-changing words: song, format, correction state, and which row is playing. */
    private void showState(LosslessPlayer p) {
        set(title, p.title().isEmpty() ? "Nothing playing" : p.title());
        set(artist, p.artist());
        set(format, p.format().isEmpty() ? "No file yet" : p.format());
        set(playPause, p.playing() ? "Pause" : "Play");
        correction = SoundPrefs.correction(prefs);
        boolean wanted = prefs.getBoolean(SoundPrefs.PLAYER_FILTER, true);
        boolean on = p.filtering() && p.playing();
        set(filterBadge, on ? "Room correction on" : "Room correction off");
        filterBadge.setTextColor(on ? GOOD : DIM);
        String target = SoundPrefs.target(prefs).name();
        set(filterNote, correction == null ? "No Calibrate yet, so files play untouched. Run Calibrate in the Sound hub."
                : !wanted ? "Off: files play untouched."
                : "From Calibrate, " + target.charAt(0) + target.substring(1).toLowerCase(Locale.US) + " target.");
        int row = p.playing() || p.positionMs() > 0 ? p.index() : -1;
        if (row != playingRow) {
            playingRow = row;
            adapter.notifyDataSetChanged();
        }
    }

    private void drawSpectrum(LosslessPlayer p) {
        double[] shown = p.filtering() ? correction : null;
        int rate = p.rate();
        if (rate <= 0 || !p.scope(scope)) {
            smooth = null;
            graph.set(null, null, shown);
            return;
        }
        double[] bands = SoundTune.bandLevelsDb(SoundTune.magnitudes(scope), rate, SCOPE);
        if (smooth == null) smooth = bands;
        else for (int i = 0; i < bands.length; i++) smooth[i] = 0.6 * smooth[i] + 0.4 * bands[i];
        graph.set(SoundActivity.centred(smooth), null, shown);
    }

    // ---------------- the music folder ----------------

    private void pickFolder() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE);
        } catch (RuntimeException e) {
            Toast.makeText(this, "No folder picker on this tablet", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQ_TREE || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException e) {
            Diag.warn(DiagLog.APP, "The music folder will have to be picked again next time: " + e);
        }
        prefs.edit().putString(SoundPrefs.PLAYER_TREE, tree.toString()).apply();
        load(tree);
    }

    /** Lists the folder's audio files off the main thread and hands them to the player as its queue. */
    private void load(final Uri tree) {
        folder.setText("Reading the folder...");
        final Context app = getApplicationContext();
        Thread t = new Thread(() -> {
            final List<LosslessPlayer.Track> found = LosslessPlayer.scan(app, tree);
            main.post(() -> {
                if (isFinishing()) return;
                LosslessPlayer p = player;
                if (p != null) p.setQueue(found, 0);
                showTracks(found);
                if (found.isEmpty())
                    Toast.makeText(this, "No music files found in that folder", Toast.LENGTH_LONG).show();
            });
        }, "player-scan");
        t.setDaemon(true);
        t.start();
    }

    private void showTracks(List<LosslessPlayer.Track> list) {
        tracks = list;
        playingRow = -1;
        adapter.notifyDataSetChanged();
        empty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        String saved = prefs.getString(SoundPrefs.PLAYER_TREE, null);
        String where = "";
        if (saved != null) {
            try {
                where = DocumentsContract.getTreeDocumentId(Uri.parse(saved));
                where = where.substring(where.indexOf(':') + 1);
            } catch (RuntimeException ignored) {
                // not a tree any more: the plain label will do
            }
        }
        folder.setText(list.isEmpty() ? "Pick music folder"
                : (where.isEmpty() ? "Folder" : where) + " (" + list.size() + ")");
    }

    private final class TrackAdapter extends BaseAdapter {
        @Override public int getCount() { return tracks.size(); }

        @Override public Object getItem(int row) { return tracks.get(row); }

        @Override public long getItemId(int row) { return row; }

        @Override
        public View getView(int row, View old, ViewGroup parent) {
            TextView t = old instanceof TextView ? (TextView) old : label("", 15, INK);
            t.setPadding(dp(12), dp(12), dp(12), dp(12));
            t.setText(tracks.get(row).path);
            t.setTextColor(row == playingRow ? GOOD : INK);
            t.setTypeface(row == playingRow ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            return t;
        }
    }
}
