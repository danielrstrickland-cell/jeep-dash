package com.strickland.jeepdash;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import com.strickland.jeepdash.core.Convolver;
import com.strickland.jeepdash.core.DiagLog;
import com.strickland.jeepdash.core.FirDesign;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The local lossless player's engine: decodes a queue of files from a folder (the USB stick), keeps the audio
 * as floating point at the file's own sample rate, convolves it with the room-correction filter made from
 * Calibrate's measurement ({@link FirDesign}, {@link Convolver}) and plays it. With no measurement, or with
 * the filter switched off, the samples go through untouched.
 *
 * While it is filtering it tells the {@link SoundEngine} so, and that effect (which also sits on this
 * player's output) drops its own copy of the correction and keeps only the adaptive parts. So the player
 * still corrects on a tablet that refuses the system-wide effect. Owned by the link service: it plays on
 * behind every screen, and publishes a media session, so the dash's music button and the stereo's own track
 * buttons work on it as they do on Spotify. One worker thread does all the decoding and writing.
 */
final class LosslessPlayer {

    /** One file in the queue: where it is and its path under the chosen folder. */
    static final class Track {
        final Uri uri;
        final String path;
        Track(Uri uri, String path) { this.uri = uri; this.path = path; }

        /** The file's name without its folders or extension. */
        String title() {
            String n = path.substring(path.lastIndexOf('/') + 1);
            int dot = n.lastIndexOf('.');
            return dot > 0 ? n.substring(0, dot) : n;
        }
    }

    private static final String[] EXT = {".flac", ".wav", ".m4a", ".mp3", ".ogg", ".opus", ".aac"};
    private static final int ENC_24 = 21, ENC_32 = 22;       // AudioFormat's packed 24-bit and 32-bit integer

    private final Context app;
    private final SoundEngine engine;
    private final Runnable retick;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private List<Track> queue = new ArrayList<>();           // guarded by lock, as are the next five
    private int index;
    private boolean wantPlay, quit;
    private long seekMs = -1;
    private int serial;                                      // bumped whenever the worker must drop the song in hand
    private Thread worker;
    private volatile boolean refilter, playing, filtering, resumeOnGain;
    private volatile String title = "", artist = "", format = "";
    private volatile long positionMs, durationMs;
    private MediaSession session;
    private AudioFocusRequest focus;

    LosslessPlayer(Context c, SoundEngine engine, Runnable retick) {
        app = c.getApplicationContext();
        this.engine = engine;
        this.retick = retick;
        prefs = SoundPrefs.of(app);
    }

    // ---------------- what the screen reads ----------------

    boolean playing() { return playing; }

    /** True while the room-correction filter is in the signal. */
    boolean filtering() { return filtering; }

    String title() { return title; }

    String artist() { return artist; }

    /** The file's format in words, e.g. "FLAC 44.1 kHz 16-bit". */
    String format() { return format; }

    long positionMs() { return positionMs; }

    long durationMs() { return durationMs; }

    List<Track> queue() { synchronized (lock) { return new ArrayList<>(queue); } }

    int index() { synchronized (lock) { return index; } }

    // ---------------- controls ----------------

    void setQueue(List<Track> tracks, int start) {
        synchronized (lock) {
            queue = new ArrayList<>(tracks);
            index = Math.max(0, Math.min(queue.size() - 1, start));
            serial++;
            seekMs = -1;
            lock.notifyAll();
        }
    }

    void play() {
        synchronized (lock) {
            if (queue.isEmpty() || quit) return;
            wantPlay = true;
            if (worker == null) {
                worker = new Thread(this::run, "lossless-player");
                worker.setDaemon(true);
                worker.start();
            }
            lock.notifyAll();
        }
        takeFocus();
        openSession();
    }

    void pause() {
        synchronized (lock) {
            wantPlay = false;
            lock.notifyAll();
        }
    }

    void toggle() {
        boolean on;
        synchronized (lock) { on = wantPlay; }
        if (on) pause();
        else play();
    }

    void next() { step(1); }

    /** Back to the start of the song, or to the one before when already near the start. */
    void prev() {
        if (positionMs > 3000) seek(0);
        else step(-1);
    }

    private void step(int by) {
        synchronized (lock) {
            if (queue.isEmpty()) return;
            index = (index + by + queue.size()) % queue.size();
            serial++;
            seekMs = -1;
            lock.notifyAll();
        }
    }

    void seek(long ms) {
        synchronized (lock) {
            seekMs = Math.max(0, ms);
            positionMs = seekMs;                             // shown at once, even while paused
            lock.notifyAll();
        }
    }

    /** A Sound hub setting changed: rebuild the filter at the next block (new target, new Calibrate, on/off). */
    void refilter() { refilter = true; }

    void release() {
        synchronized (lock) {
            quit = true;
            wantPlay = false;
            serial++;
            lock.notifyAll();
        }
        AudioManager am = app.getSystemService(AudioManager.class);
        if (am != null && focus != null) am.abandonAudioFocusRequest(focus);
        MediaSession s = session;
        session = null;
        if (s != null) s.release();
        engine.setConvolving(false);
    }

    // ---------------- audio focus and the media session ----------------

    private void takeFocus() {
        AudioManager am = app.getSystemService(AudioManager.class);
        if (am == null) return;
        if (focus == null) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes())
                    .setOnAudioFocusChangeListener(change -> {
                        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                            boolean was;
                            synchronized (lock) { was = wantPlay; }
                            resumeOnGain = was && change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT;
                            pause();
                        } else if (change == AudioManager.AUDIOFOCUS_GAIN && resumeOnGain) {
                            resumeOnGain = false;
                            play();
                        }
                    }, main)
                    .build();
        }
        am.requestAudioFocus(focus);         // Spotify (or anything else playing) pauses
    }

    private static AudioAttributes attributes() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
    }

    private void openSession() {
        if (session != null) return;
        try {
            MediaSession s = new MediaSession(app, "JeepDashLossless");
            s.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { play(); }
                @Override public void onPause() { pause(); }
                @Override public void onStop() { pause(); }
                @Override public void onSkipToNext() { next(); }
                @Override public void onSkipToPrevious() { prev(); }
                @Override public void onSeekTo(long pos) { seek(pos); }
            }, main);
            s.setActive(true);
            session = s;
            publish();
        } catch (RuntimeException e) {
            Diag.warn(DiagLog.APP, "Player has no media session (track buttons will not reach it): " + e);
        }
    }

    /** Tells the media session what is playing and whether it is, for the dash's music window and the stereo. */
    private void publish() {
        MediaSession s = session;
        if (s == null) return;
        try {
            s.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs).build());
            s.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                            | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                            | PlaybackState.ACTION_SEEK_TO)
                    .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, positionMs,
                            playing ? 1f : 0f).build());
        } catch (RuntimeException ignored) {
            // the session was released under us
        }
    }

    private void setPlaying(boolean on) {
        if (playing == on) return;
        playing = on;
        engine.setConvolving(on && filtering);
        retick.run();
        publish();
    }

    // ---------------- the worker ----------------

    private void run() {
        while (true) {
            Track t;
            int mine;
            synchronized (lock) {
                while (!wantPlay && !quit) {
                    try { lock.wait(); } catch (InterruptedException e) { return; }
                }
                if (quit) return;
                if (queue.isEmpty()) { wantPlay = false; continue; }
                t = queue.get(index);
                mine = serial;
            }
            boolean ended = playOne(t, mine);
            setPlaying(false);
            synchronized (lock) {
                if (serial != mine || !ended) continue;      // skipped, re-queued or stopped: look again
                if (index + 1 < queue.size()) index++;
                else { index = 0; wantPlay = false; }         // the end of the folder
            }
        }
    }

    private boolean moved(int mine) { synchronized (lock) { return serial != mine; } }

    /** Plays one file to its end. False if it was cut short by a skip, a new queue or shutting down. */
    private boolean playOne(Track t, int mine) {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        Chain chain = null;
        try {
            ex.setDataSource(app, t.uri, null);
            MediaFormat in = null;
            for (int i = 0; i < ex.getTrackCount() && in == null; i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) {
                    ex.selectTrack(i);
                    in = f;
                }
            }
            if (in == null) throw new IOException("no audio in this file");
            String mime = in.getString(MediaFormat.KEY_MIME);
            durationMs = in.containsKey(MediaFormat.KEY_DURATION) ? in.getLong(MediaFormat.KEY_DURATION) / 1000 : 0;
            positionMs = 0;
            int bits = in.containsKey("bits-per-sample") ? in.getInteger("bits-per-sample") : 0;
            // ask for floating point out so 24-bit files keep all their bits; raw PCM already says what it is
            if (!"audio/raw".equals(mime)) in.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(in, null, null, 0);
            codec.start();
            describe(t);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            int channels = 2, encoding = AudioFormat.ENCODING_PCM_16BIT;
            while (!outDone) {
                long seek;
                synchronized (lock) {
                    while (!wantPlay && serial == mine) {       // paused: hold here with the output stopped
                        if (chain != null) chain.pause();
                        setPlaying(false);
                        lock.wait();
                    }
                    if (serial != mine) return false;
                    seek = seekMs;
                    seekMs = -1;
                }
                if (seek >= 0) {
                    ex.seekTo(seek * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    codec.flush();
                    inDone = false;
                    if (chain != null) chain.flush();
                    positionMs = seek;
                }
                if (chain != null) chain.resume();
                setPlaying(true);
                if (!inDone) {
                    int ib = codec.dequeueInputBuffer(10000);
                    if (ib >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(ib);
                        int n = buf == null ? -1 : ex.readSampleData(buf, 0);
                        if (n < 0) {
                            codec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            codec.queueInputBuffer(ib, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int ob = codec.dequeueOutputBuffer(info, 10000);
                if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    int rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    encoding = of.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            ? of.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
                    if (chain != null) chain.release();
                    chain = new Chain(rate);
                    format = formatName(mime, rate, bits);
                } else if (ob >= 0) {
                    if (info.size > 0 && chain != null) {
                        ByteBuffer b = codec.getOutputBuffer(ob);
                        if (b != null) {
                            b.position(info.offset);
                            b.limit(info.offset + info.size);
                            chain.feed(b.order(ByteOrder.nativeOrder()), channels, encoding);
                            positionMs = info.presentationTimeUs / 1000;
                        }
                    }
                    codec.releaseOutputBuffer(ob, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                }
            }
            if (chain != null) {
                chain.finish();
                for (int i = 0; i < 150 && !chain.drained() && !moved(mine); i++) Thread.sleep(20);
            }
            return !moved(mine);
        } catch (InterruptedException e) {
            return false;
        } catch (IOException | RuntimeException e) {
            Diag.error(DiagLog.APP, "Player could not play " + t.path + ": " + e);
            return !moved(mine);                              // a bad file: on to the next one
        } finally {
            if (chain != null) chain.release();
            if (codec != null) {
                try { codec.stop(); } catch (RuntimeException ignored) { }
                codec.release();
            }
            ex.release();
        }
    }

    private void describe(Track t) {
        String name = t.title(), by = "";
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(app, t.uri);
            String v = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            if (v != null && !v.trim().isEmpty()) name = v.trim();
            v = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            if (v != null) by = v.trim();
        } catch (RuntimeException ignored) {
            // no tags: the file name will do
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
        title = name;
        artist = by;
        publish();
    }

    private static String formatName(String mime, int rate, int bits) {
        String kind = "audio/raw".equals(mime) ? "WAV" : "audio/mpeg".equals(mime) ? "MP3"
                : "audio/mp4a-latm".equals(mime) ? "AAC" : mime.substring(mime.indexOf('/') + 1).toUpperCase(Locale.US);
        String khz = rate % 1000 == 0 ? String.valueOf(rate / 1000) : String.format(Locale.US, "%.1f", rate / 1000.0);
        return kind + " " + khz + " kHz" + (bits > 0 ? " " + bits + "-bit" : "");
    }

    /** The output for one sample rate: the filter (when there is one), the block being filled and the track. */
    private final class Chain {
        private final int rate, block;
        private final AudioTrack track;
        private final float[] inL, inR, outL, outR, mix;
        private Convolver left, right;
        private float gain = 1;
        private int fill;
        private long written, shortUntil;
        private boolean paused;

        Chain(int rate) {
            this.rate = rate;
            block = FirDesign.tapsFor(rate);
            inL = new float[block];
            inR = new float[block];
            outL = new float[block];
            outR = new float[block];
            mix = new float[block * 2];
            design();
            int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
            if (min <= 0) throw new IllegalStateException("this tablet cannot play " + rate + " Hz");
            track = new AudioTrack.Builder().setAudioAttributes(attributes())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(Math.max(min, rate * 4))          // about half a second of stereo float
                    .setTransferMode(AudioTrack.MODE_STREAM).build();
            track.play();
        }

        /** Builds the filter from the stored measurement and the chosen target, or clears it. */
        private void design() {
            refilter = false;
            double[] c = prefs.getBoolean(SoundPrefs.PLAYER_FILTER, true) ? SoundPrefs.correction(prefs) : null;
            boolean any = false;
            if (c != null) for (double d : c) any |= Math.abs(d) > 0.05;
            if (any) {
                double[] taps = FirDesign.minimumPhase(c, rate, block);
                left = new Convolver(taps);
                right = new Convolver(taps);
                gain = (float) Math.pow(10, FirDesign.headroomDb(c) / 20);
            } else {
                left = right = null;
                gain = 1;
            }
            filtering = any;
            engine.setConvolving(any && playing);
            retick.run();
        }

        private float sample(ByteBuffer b, int encoding) {
            switch (encoding) {
                case AudioFormat.ENCODING_PCM_FLOAT: return b.getFloat();
                case ENC_32: return b.getInt() / 2147483648f;
                case ENC_24: {
                    int v = (b.get() & 0xFF) | ((b.get() & 0xFF) << 8) | (b.get() << 16);
                    return v / 8388608f;
                }
                case AudioFormat.ENCODING_PCM_8BIT: return ((b.get() & 0xFF) - 128) / 128f;
                default: return b.getShort() / 32768f;
            }
        }

        /** Takes decoded audio in whatever layout the decoder gave and plays it a block at a time. */
        void feed(ByteBuffer b, int channels, int encoding) {
            int bytes = encoding == AudioFormat.ENCODING_PCM_FLOAT || encoding == ENC_32 ? 4
                    : encoding == ENC_24 ? 3 : encoding == AudioFormat.ENCODING_PCM_8BIT ? 1 : 2;
            int frames = b.remaining() / (bytes * Math.max(1, channels));
            for (int f = 0; f < frames; f++) {
                float l = sample(b, encoding), r = channels > 1 ? sample(b, encoding) : l;
                for (int c = 2; c < channels; c++) sample(b, encoding);       // only the front pair is played
                inL[fill] = l;
                inR[fill] = r;
                if (++fill == block) emit(block);
            }
        }

        private void emit(int frames) {
            if (refilter) design();
            float[] l = inL, r = inR;
            if (left != null) {
                left.process(inL, outL);
                right.process(inR, outR);
                l = outL;
                r = outR;
            }
            for (int i = 0; i < frames; i++) {
                mix[2 * i] = Math.max(-1f, Math.min(1f, l[i] * gain));
                mix[2 * i + 1] = Math.max(-1f, Math.min(1f, r[i] * gain));
            }
            for (int off = 0; off < frames * 2; ) {
                int w = track.write(mix, off, frames * 2 - off, AudioTrack.WRITE_BLOCKING);
                if (w <= 0) throw new IllegalStateException("audio output stopped taking sound (" + w + ")");
                off += w;
            }
            written += frames;
            fill = 0;
        }

        /** The end of the file: play what is left of the last block and let the output run out. */
        void finish() {
            if (fill > 0) {
                int n = fill;
                java.util.Arrays.fill(inL, n, block, 0f);
                java.util.Arrays.fill(inR, n, block, 0f);
                emit(n);
            }
            if (written < rate / 2) {                        // too short to have started on its own: stop() plays it out
                track.stop();
                shortUntil = android.os.SystemClock.elapsedRealtime() + written * 1000 / rate + 150;
            }
        }

        /** True once everything written has been played. */
        boolean drained() {
            if (shortUntil > 0) return android.os.SystemClock.elapsedRealtime() >= shortUntil;
            return (track.getPlaybackHeadPosition() & 0xFFFFFFFFL) >= written;
        }

        void pause() {
            if (paused) return;
            paused = true;
            track.pause();
        }

        void resume() {
            if (!paused) return;
            paused = false;
            track.play();
        }

        /** A seek: drop what is queued and what the filter remembers. */
        void flush() {
            track.pause();
            track.flush();
            written = 0;
            fill = 0;
            if (left != null) {
                left.reset();
                right.reset();
            }
            if (!paused) track.play();
        }

        void release() {
            try {
                track.pause();
                track.flush();
            } catch (RuntimeException ignored) { }
            track.release();
        }
    }

    // ---------------- finding the files ----------------

    private static boolean isAudio(String name, String mime) {
        String n = name.toLowerCase(Locale.US);
        for (String e : EXT) if (n.endsWith(e)) return true;
        return mime != null && mime.startsWith("audio/");
    }

    /** Every audio file under a folder the owner picked (a document tree), in path order. */
    static List<Track> scan(Context c, Uri tree) {
        List<Track> out = new ArrayList<>();
        try {
            walk(c, tree, DocumentsContract.getTreeDocumentId(tree), "", out, 0);
        } catch (RuntimeException e) {
            Diag.error(DiagLog.APP, "The music folder could not be read: " + e);
        }
        Collections.sort(out, (a, b) -> a.path.compareToIgnoreCase(b.path));
        return out;
    }

    private static void walk(Context c, Uri tree, String docId, String path, List<Track> out, int depth) {
        if (depth > 5 || out.size() >= 5000) return;
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor cur = c.getContentResolver().query(kids, cols, null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                String id = cur.getString(0), name = cur.getString(1), mime = cur.getString(2);
                if (id == null || name == null) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) walk(c, tree, id, path + name + "/", out, depth + 1);
                else if (isAudio(name, mime)) out.add(new Track(DocumentsContract.buildDocumentUriUsingTree(tree, id), path + name));
            }
        }
    }
}
