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
 * Music visualizer. Listens through the tablet's microphone (nothing is recorded or sent anywhere) and,
 * when the Jeep is connected, reads engine speed for the RPM colour profile.
 */
public class VisualizerActivity extends Activity
        implements ServiceConnection, LinkService.Listener, VisualizerView.Host {
    private static final int RATE = 44100, SIZE = 2048, HOP = 1024;

    private VisualizerView view;
    private LinkService service;
    private boolean bound;
    private volatile Thread mic;

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

    private void listen() {
        AudioRecord rec = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bytes = Math.max(min, SIZE * 4);
            // "Unprocessed" skips the phone-call clean-up that would flatten music; plain mic is the fallback.
            for (int source : new int[]{MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC}) {
                rec = new AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bytes);
                if (rec.getState() == AudioRecord.STATE_INITIALIZED) break;
                rec.release();
                rec = null;
            }
            if (rec == null) {
                view.painter.message = "The microphone is busy or not available";
                return;
            }
            Spectrum spectrum = new Spectrum(RATE, SIZE, VizPainter.BANDS);
            view.setSpectrum(spectrum);
            short[] window = new short[SIZE], hop = new short[HOP];
            rec.startRecording();
            while (mic == Thread.currentThread()) {
                int got = 0;
                while (got < HOP) {
                    int n = rec.read(hop, got, HOP - got);
                    if (n <= 0) return;
                    got += n;
                }
                System.arraycopy(window, HOP, window, 0, SIZE - HOP);
                System.arraycopy(hop, 0, window, SIZE - HOP, HOP);
                spectrum.feed(window, (float) HOP / RATE);
            }
        } catch (SecurityException | IllegalArgumentException | IllegalStateException e) {
            view.painter.message = "The microphone could not be opened";
        } finally {
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
