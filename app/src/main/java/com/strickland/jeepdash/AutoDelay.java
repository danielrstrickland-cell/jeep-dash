package com.strickland.jeepdash;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import com.strickland.jeepdash.core.DelayEstimator;
import com.strickland.jeepdash.core.DiagLog;

import java.util.Arrays;
import java.util.Locale;

/**
 * Auto speaker delay: measures how far the Bluetooth speakers lag behind the sound the visualizer sees. Every
 * 20 seconds while music plays from the tablet it listens with the microphone for 6 seconds, compares the
 * bass it hears with the bass that went out (see {@link DelayEstimator}), and reports the delay. Three good
 * measurements are kept and the middle one is used, so one bad reading cannot throw it off. Only loudness
 * readings are kept, never the sound itself.
 */
final class AutoDelay {
    interface Listener {
        void onDelay(float seconds);
    }

    private static final int RATE = 16000, CHUNK = 160;                // 10 ms of mic sound
    private static final long EVERY_MS = 20_000, LISTEN_MS = 6_000;
    private final Context context;
    private final Listener listener;
    private final DelayEstimator estimator = new DelayEstimator();
    private final float[] found = new float[3];
    private int foundN;
    private volatile boolean enabled;
    private volatile long tapSeenMs;
    private volatile Thread thread;
    private float tapLow;                                              // the out-going sound's low-pass state

    AutoDelay(Context c, Listener l) {
        context = c.getApplicationContext();
        listener = l;
    }

    void setEnabled(boolean on) {
        if (on == enabled) return;
        enabled = on;
        if (on) {
            Thread t = new Thread(this::run, "auto-delay");
            thread = t;
            t.start();
        } else {
            Thread t = thread;
            thread = null;
            if (t != null) t.interrupt();
        }
    }

    void stop() { setEnabled(false); }

    /** The sound going out to Bluetooth, as the visualizer's tap sees it. */
    void tap(short[] samples, int n) {
        if (!enabled || n <= 0) return;
        tapSeenMs = System.currentTimeMillis();
        estimator.add(false, System.nanoTime(), bass(samples, n, true));
    }

    private float micLow;

    /** The loudness of the bass in a chunk: a gentle low-pass (about 150 Hz), then RMS. */
    private double bass(short[] s, int n, boolean out) {
        float k = out ? 0.021f : 0.057f;                               // the tap runs at 44.1 kHz, the mic at 16 kHz
        float y = out ? tapLow : micLow;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            y += k * (s[i] - y);
            sum += (double) y * y;
        }
        if (out) tapLow = y;
        else micLow = y;
        return Math.sqrt(sum / n);
    }

    @SuppressLint("MissingPermission")
    private void run() {
        short[] buf = new short[CHUNK];
        try {
            while (thread == Thread.currentThread()) {
                Thread.sleep(2000);
                boolean playing = System.currentTimeMillis() - tapSeenMs < 1000;
                if (!playing) continue;
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    Thread.sleep(EVERY_MS);
                    continue;
                }
                AudioRecord rec;
                try {
                    int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    rec = new AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, RATE, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, Math.max(min, CHUNK * 8));
                    if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                        rec.release();
                        rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT, Math.max(min, CHUNK * 8));
                    }
                } catch (RuntimeException e) {
                    Thread.sleep(EVERY_MS);
                    continue;
                }
                long stopAt = System.currentTimeMillis() + LISTEN_MS;
                try {
                    rec.startRecording();
                    while (System.currentTimeMillis() < stopAt && thread == Thread.currentThread()) {
                        int got = 0;
                        while (got < CHUNK) {
                            int n = rec.read(buf, got, CHUNK - got);
                            if (n <= 0) break;
                            got += n;
                        }
                        if (got > 0) estimator.add(true, System.nanoTime(), bass(buf, got, false));
                    }
                } finally {
                    rec.stop();
                    rec.release();
                }
                double[] d = estimator.estimate(System.nanoTime(), 5, 0.5);
                if (d != null) {
                    found[foundN % 3] = (float) d[0];
                    foundN++;
                    float[] last = Arrays.copyOf(found, Math.min(3, foundN));
                    Arrays.sort(last);
                    float use = last[last.length / 2];
                    Diag.info(DiagLog.GFX, String.format(Locale.US, "auto delay measured %.0f ms (match %.2f), using %.0f ms",
                            d[0] * 1000, d[1], use * 1000));
                    listener.onDelay(use);
                } else {
                    Diag.info(DiagLog.GFX, "auto delay: no clear match this time (quiet, or too much road noise)");
                }
                estimator.clear();
                Thread.sleep(EVERY_MS - LISTEN_MS);
            }
        } catch (InterruptedException ignored) {
            // switched off, or the screen closed
        }
    }
}
