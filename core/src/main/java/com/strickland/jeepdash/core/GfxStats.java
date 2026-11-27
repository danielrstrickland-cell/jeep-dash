package com.strickland.jeepdash.core;

import java.util.Locale;

/**
 * Frame timing for one stretch of the visualizer (one look, one setting): how many frames, the average and
 * the 1% low frame rate, how long the GPU and the main thread spent on each frame, and the 3D detail the look
 * settled at. Fed every frame; read as one log line.
 */
public final class GfxStats {
    private static final int BUCKETS = 101;                       // frame times in whole milliseconds, 0..100+
    private final int[] hist = new int[BUCKETS];
    private int frames, gpuFrames;
    private double frameMs, gpuMs, cpuMs, detail;

    public synchronized void frame(double ms, double detailNow) {
        if (ms <= 0) return;
        frames++;
        frameMs += ms;
        detail += detailNow;
        hist[Math.min(BUCKETS - 1, (int) Math.round(ms))]++;
    }

    /** The GPU's and the main thread's time for a frame, from Android's frame metrics. */
    public synchronized void metrics(double gpu, double cpu) {
        gpuFrames++;
        gpuMs += gpu;
        cpuMs += cpu;
    }

    public synchronized int frames() { return frames; }

    public synchronized double fps() { return frames == 0 ? 0 : 1000 * frames / frameMs; }

    /** The frame rate of the slowest 1% of frames. */
    public synchronized double low1() {
        if (frames == 0) return 0;
        int want = Math.max(1, frames / 100), seen = 0;
        for (int ms = BUCKETS - 1; ms >= 0; ms--) {
            seen += hist[ms];
            if (seen >= want) return 1000.0 / Math.max(1, ms);
        }
        return 0;
    }

    public synchronized double gpu() { return gpuFrames == 0 ? -1 : gpuMs / gpuFrames; }

    public synchronized double cpu() { return gpuFrames == 0 ? -1 : cpuMs / gpuFrames; }

    public synchronized double detail() { return frames == 0 ? 0 : detail / frames; }

    public synchronized void clear() {
        java.util.Arrays.fill(hist, 0);
        frames = gpuFrames = 0;
        frameMs = gpuMs = cpuMs = detail = 0;
    }

    /** "fps 47.3  low1% 31  gpu 18.2 ms  cpu 3.1 ms  detail 62%" */
    public synchronized String line() {
        String gpuPart = gpuFrames == 0 ? "gpu n/a" : String.format(Locale.US, "gpu %.1f ms  cpu %.1f ms", gpu(), cpu());
        return String.format(Locale.US, "fps %.1f  low1%% %.0f  %s  detail %.0f%%  frames %d",
                fps(), low1(), gpuPart, detail() * 100, frames);
    }
}
