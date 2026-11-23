package com.strickland.jeepdash;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.Visualizer;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import com.strickland.jeepdash.core.LiveValues;
import com.strickland.jeepdash.core.Spectrum;

import java.util.Arrays;
import java.util.LinkedHashSet;

/**
 * Music visualizer. It listens to the tablet's own sound output (whatever a music app on this tablet is
 * playing), and only when that is silent or not allowed does it fall back to the microphone. Nothing is
 * recorded or sent anywhere. When the Jeep is connected it also reads engine speed for the RPM colours.
 */
public class VisualizerActivity extends Activity
        implements ServiceConnection, LinkService.Listener, VisualizerView.Host {
    private static final int RATE = 44100, SIZE = 2048, HOP = 1024;
    private static final long QUIET_BEFORE_MIC_MS = 6000, NOTE_MS = 4000;

    private VisualizerView view;
    private LinkService service;
    private boolean bound;
    private volatile Thread mic;
    private NowPlayingBar nowPlaying;          // null while the now-playing bar is off (set on the dash's menu)

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        view = new VisualizerView(this, this);
        setContentView(view);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) return;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController ic = getWindow().getInsetsController();
            if (ic != null) {
                ic.hide(WindowInsets.Type.systemBars());
                ic.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            hideBarsOld();
        }
    }

    @SuppressWarnings("deprecation")
    private void hideBarsOld() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
    }

    @Override
    protected void onStart() {
        super.onStart();
        view.setRunning(true);
        nowPlaying = NowPlayingBar.sync(this, nowPlaying);
        if (nowPlaying != null) nowPlaying.start();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startMic();
        else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        try {
            startService(new Intent(this, LinkService.class));
        } catch (RuntimeException ignored) {
            // not allowed right now; the visualizer works without the Jeep
        }
        bound = bindService(new Intent(this, LinkService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        if (nowPlaying != null) nowPlaying.stop();
        view.setRunning(false);
        Thread t = mic;
        mic = null;
        if (t != null) t.interrupt();
        if (service != null) service.removeListener(this);
        service = null;
        if (bound) {
            unbindService(this);
            bound = false;
        }
        super.onStop();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startMic();
        else view.painter.message = "Allow the microphone so the visualizer can hear the music";
    }

    // ---- microphone ----

    private void startMic() {
        if (mic != null) return;
        view.painter.message = null;
        Thread t = new Thread(this::listen, "viz-mic");
        t.setDaemon(true);
        mic = t;
        t.start();
    }

    /** The tablet's own sound output, or null when this tablet does not allow tapping it. */
    private static Visualizer openTap() {
        Visualizer v = null;
        try {
            v = new Visualizer(0);                           // 0 = everything the tablet is playing
            int size = Visualizer.getCaptureSizeRange()[1];
            if (Integer.bitCount(size) != 1) size = Integer.highestOneBit(size);
            v.setCaptureSize(size);
            v.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);   // same picture at any volume
            v.setEnabled(true);
            return v;
        } catch (RuntimeException e) {
            if (v != null) v.release();
            return null;
        }
    }

    private static AudioRecord openMic() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bytes = Math.max(min, SIZE * 4);
        // "Unprocessed" skips the phone-call clean-up that would flatten music; plain mic is the fallback.
        for (int source : new int[]{MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC}) {
            try {
                AudioRecord rec = new AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bytes);
                if (rec.getState() == AudioRecord.STATE_INITIALIZED) {
                    rec.startRecording();
                    return rec;
                }
                rec.release();
            } catch (RuntimeException ignored) {
                // try the next source
            }
        }
        return null;
    }

    private void say(String text, long[] until) {
        view.painter.message = text;
        until[0] = System.currentTimeMillis() + NOTE_MS;
    }

    private void listen() {
        Visualizer tap = openTap();
        AudioRecord rec = null;
        try {
            int tapSize = tap == null ? 0 : tap.getCaptureSize();
            Spectrum tapSpectrum = tap == null ? null : new Spectrum(tap.getSamplingRate() / 1000, tapSize, VizPainter.BANDS);
            Spectrum micSpectrum = new Spectrum(RATE, SIZE, VizPainter.BANDS);
            byte[] wave = new byte[Math.max(1, tapSize)];
            short[] tapSamples = new short[Math.max(1, tapSize)], window = new short[SIZE], hop = new short[HOP];
            long[] noteUntil = {0};
            long lastSound = System.currentTimeMillis(), lastFeed = System.nanoTime();
            boolean onTap = false, started = false;

            while (mic == Thread.currentThread()) {
                long now = System.currentTimeMillis();
                if (noteUntil[0] != 0 && now > noteUntil[0]) {
                    view.painter.message = null;
                    noteUntil[0] = 0;
                }
                // is the tablet itself playing anything?
                if (tap != null && tap.getWaveForm(wave) == Visualizer.SUCCESS) {
                    boolean sound = false;
                    for (int i = 0; i < tapSize; i++) {
                        int v = (wave[i] & 0xff) - 128;
                        tapSamples[i] = (short) (v << 8);
                        if (v > 1 || v < -1) sound = true;
                    }
                    if (sound) lastSound = now;
                }
                boolean wantTap = tap != null && now - lastSound < QUIET_BEFORE_MIC_MS;

                if (wantTap) {
                    if (!onTap || !started) {
                        if (rec != null) {
                            rec.release();
                            rec = null;
                        }
                        onTap = true;
                        started = true;
                        view.setSpectrum(tapSpectrum);
                        say("Sound: straight from the tablet", noteUntil);
                    }
                    long t = System.nanoTime();
                    tapSpectrum.feed(tapSamples, Math.min(0.1f, (t - lastFeed) / 1e9f));
                    lastFeed = t;
                    Thread.sleep(20);
                    continue;
                }

                if (onTap || !started || rec == null) {
                    if (rec == null) rec = openMic();
                    onTap = false;
                    started = true;
                    if (rec == null) {
                        view.painter.message = "The microphone is busy or not available";
                        Thread.sleep(1000);
                        continue;
                    }
                    view.setSpectrum(micSpectrum);
                    say(tap == null ? "Sound: microphone" : "Nothing playing on the tablet: using the microphone", noteUntil);
                }
                int got = 0;
                while (got < HOP) {
                    int n = rec.read(hop, got, HOP - got);
                    if (n <= 0) return;
                    got += n;
                }
                System.arraycopy(window, HOP, window, 0, SIZE - HOP);
                System.arraycopy(hop, 0, window, SIZE - HOP, HOP);
                micSpectrum.feed(window, (float) HOP / RATE);
                lastFeed = System.nanoTime();
            }
        } catch (InterruptedException ignored) {
            // the screen is closing
        } catch (RuntimeException e) {
            view.painter.message = "The sound could not be opened";
        } finally {
            if (tap != null) {
                try { tap.setEnabled(false); } catch (RuntimeException ignored) { /* already gone */ }
                tap.release();
            }
            if (rec != null) {
                try { rec.stop(); } catch (IllegalStateException ignored) { /* never started */ }
                rec.release();
            }
            if (mic == Thread.currentThread()) mic = null;
        }
    }

    // ---- the Jeep, for the RPM colours ----

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((LinkService.LocalBinder) binder).service();
        service.setActiveParams(new LinkedHashSet<>(Arrays.asList("rpm", "throttle")));
        service.addListener(this);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) { service = null; }

    @Override
    public void onValues(LiveValues v) { view.painter.rpm = v.get("rpm", LinkService.now()); }

    @Override
    public void onStatus(LinkService.Status s, String detail) { }

    @Override
    public void onDashButton() {
        startActivity(new Intent(this, DashActivity.class));
        finish();
    }
}
