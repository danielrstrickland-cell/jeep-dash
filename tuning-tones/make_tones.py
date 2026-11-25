#!/usr/bin/env python3
"""Generates the tuning tones: 44.1 kHz, 16-bit, stereo (same signal both channels) WAV files.

Sine 40 Hz, 50 Hz, 1 kHz, 30 s each, at 0 dBFS and at -10 dBFS; pink noise 60 s with its PEAK at -10 dBFS.
Usage: python3 make_tones.py OUTPUT_DIR      (needs numpy)
"""
import sys, wave
import numpy as np

RATE = 44100

def write(path, mono):
    pcm = np.clip(np.round(mono * 32767), -32768, 32767).astype('<i2')
    stereo = np.repeat(pcm[:, None], 2, axis=1)
    with wave.open(path, 'wb') as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes(stereo.tobytes())

def sine(freq, seconds, dbfs):
    t = np.arange(int(RATE * seconds)) / RATE
    return np.sin(2 * np.pi * freq * t) * 10 ** (dbfs / 20)

def pink(seconds, peak_dbfs):
    n = int(RATE * seconds)
    spec = np.fft.rfft(np.random.default_rng(1).standard_normal(n))
    f = np.fft.rfftfreq(n, 1 / RATE)
    f[0] = f[1]
    x = np.fft.irfft(spec / np.sqrt(f), n)          # 1/f power = -3 dB/octave
    return x / np.max(np.abs(x)) * 10 ** (peak_dbfs / 20)

if __name__ == '__main__':
    out = sys.argv[1]
    for level in (0, -10):
        for hz in (40, 50, 1000):
            write(f'{out}/sine_{hz}hz_{"0" if level == 0 else "minus10"}dBFS_30s.wav', sine(hz, 30, level))
    write(f'{out}/pink_noise_peak_minus10dBFS_60s.wav', pink(60, -10))
