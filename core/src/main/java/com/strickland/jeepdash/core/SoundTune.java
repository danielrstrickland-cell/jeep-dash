package com.strickland.jeepdash.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The measuring-and-correcting sums behind Calibrate and the live RTA: third-octave bands from a spectrum,
 * the target curves, a measurement mic's calibration file, and the correction that moves what the mic heard
 * toward the target. Pure arithmetic on arrays, so the screen and audio code only move samples in and gains
 * out.
 */
public final class SoundTune {

    public enum Target { HARMAN, FLAT, CUSTOM }

    /** The 31 third-octave band centres, 20 Hz to 20 kHz. */
    public static double[] bandCentres() {
        double[] c = new double[31];
        for (int i = 0; i < 31; i++) c[i] = 1000 * Math.pow(2, (i - 17) / 3.0);
        return c;
    }

    /**
     * Folds an FFT's magnitudes (bin k is k * sampleRate / fftSize Hz) into the 31 bands, as dB. Each band
     * sums the power of the bins inside its third-octave; a band too narrow to hold a bin borrows the
     * nearest one. A silent band is -120 dB rather than minus infinity.
     */
    public static double[] bandLevelsDb(double[] mag, double sampleRate, int fftSize) {
        double[] c = bandCentres();
        double[] out = new double[c.length];
        double binHz = sampleRate / fftSize;
        int top = Math.min(mag.length, fftSize / 2) - 1;
        double edge = Math.pow(2, 1 / 6.0);
        for (int b = 0; b < c.length; b++) {
            int lo = (int) Math.ceil(c[b] / edge / binHz), hi = (int) Math.floor(c[b] * edge / binHz);
            double power = 0;
            if (hi < lo) {
                int k = (int) Math.round(c[b] / binHz);
                k = Math.max(1, Math.min(top, k));
                power = mag[k] * mag[k];
            } else {
                for (int k = Math.max(1, lo); k <= Math.min(top, hi); k++) power += mag[k] * mag[k];
            }
            out[b] = power > 1e-12 ? 10 * Math.log10(power) : -120;
        }
        return out;
    }

    /** Harman's in-car shape as this app draws it: bass shelf in dB, and the level at 20 kHz in dB. */
    public static final double HARMAN_BASS_DB = 7, HARMAN_TREBLE_DB = -6;

    /**
     * A target of the Harman shape with its two ends adjustable: bassDb of shelf under 60 Hz easing to level
     * by 200 Hz, level to 1 kHz, then a straight fall (on a log-pitch scale) to trebleDb at 20 kHz.
     */
    public static double shapedDb(double hz, double bassDb, double trebleDb) {
        if (hz <= 0) return 0;
        if (hz <= 60) return bassDb;
        if (hz < 200) return bassDb * (1 - Math.log(hz / 60.0) / Math.log(200.0 / 60.0));
        if (hz <= 1000) return 0;
        return trebleDb * Math.min(1, Math.log(hz / 1000.0) / Math.log(20.0));
    }

    /**
     * The target level in dB at a pitch. HARMAN approximates Harman's in-car curve: about 7 dB of bass shelf
     * under 60 Hz easing to level by 200 Hz, level to 1 kHz, then a gentle fall to -6 dB at 20 kHz. FLAT is
     * 0 everywhere. CUSTOM is the owner's own bass and treble on the same shape (see {@link #shapedDb}) and
     * reads as flat here.
     */
    public static double targetDb(Target t, double hz) {
        return t == Target.HARMAN ? shapedDb(hz, HARMAN_BASS_DB, HARMAN_TREBLE_DB) : 0;
    }

    /** The owner's custom target sampled at each band centre. */
    public static double[] customBands(double bassDb, double trebleDb) {
        double[] c = bandCentres();
        double[] out = new double[c.length];
        for (int i = 0; i < c.length; i++) out[i] = shapedDb(c[i], bassDb, trebleDb);
        return out;
    }

    /**
     * The magnitude spectrum of a block of 16-bit samples (a power-of-two count), Hann-windowed so a steady
     * tone does not smear across bands. Bin k is k * sampleRate / samples.length Hz; half the block comes back.
     */
    public static double[] magnitudes(short[] samples) {
        int n = samples.length;
        float[] re = new float[n], im = new float[n];
        for (int i = 0; i < n; i++)
            re[i] = (float) (samples[i] / 32768.0 * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1))));
        Spectrum.fft(re, im);
        double[] mag = new double[n / 2];
        for (int k = 0; k < n / 2; k++) mag[k] = Math.hypot(re[k], im[k]) / (n / 4.0);
        return mag;
    }

    /** The target sampled at each band centre. */
    public static double[] targetBands(Target t) {
        double[] c = bandCentres();
        double[] out = new double[c.length];
        for (int i = 0; i < c.length; i++) out[i] = targetDb(t, c[i]);
        return out;
    }

    static final double REACH_DB = 6;

    /**
     * The bands a system actually plays, as {first, last}: out from the middle to the last band at each end
     * that is within 6 dB of the system's level through the mids (against the target). Small speakers stop
     * well short of the bottom; a full system reaches both ends. The level is taken from the stronger mid
     * bands, so a roll-off that starts inside the mids does not drag it down.
     */
    public static int[] reach(double[] measuredDb, double[] targetDb) {
        double[] c = bandCentres();
        int n = Math.min(c.length, Math.min(measuredDb.length, targetDb.length));
        double[] mids = new double[n];
        int count = 0;
        double strong = -1e9;
        for (int i = 0; i < n; i++) {
            double d = measuredDb[i] - targetDb[i];
            if (c[i] >= 300 && c[i] <= 3000) mids[count++] = d;
            strong = Math.max(strong, d);
        }
        if (count > 0) {
            java.util.Arrays.sort(mids, 0, count);
            strong = mids[Math.max(0, count - 3)];              // the third strongest: one peak does not set it
        }
        int lo = 0, hi = n - 1;
        while (lo < hi && measuredDb[lo] - targetDb[lo] < strong - REACH_DB) lo++;
        while (hi > lo && measuredDb[hi] - targetDb[hi] < strong - REACH_DB) hi--;
        return new int[]{lo, hi};
    }

    /**
     * The correction per band, in dB, that moves a measurement toward a target. The two are first lined up
     * on their average between 300 Hz and 3 kHz, over the bands the system actually plays (see
     * {@link #reach}), so overall loudness does not count as an error and neither does a small speaker's
     * missing low end. Each band's gain is then averaged with its neighbours, and held to at most maxBoost
     * up and maxCut down: a deep dip is usually a cancellation in the cabin that more power cannot fill, so
     * boost is kept small. Outside what the system plays nothing is boosted at all: power sent where a
     * speaker cannot play only costs headroom and adds distortion.
     */
    public static double[] correction(double[] measuredDb, double[] targetDb, double maxBoost, double maxCut) {
        double[] c = bandCentres();
        int n = Math.min(c.length, Math.min(measuredDb.length, targetDb.length));
        int[] reach = reach(measuredDb, targetDb);
        int lo = reach[0], hi = reach[1];
        double sum = 0;
        int count = 0;
        for (int i = lo; i <= hi; i++) if (c[i] >= 300 && c[i] <= 3000) { sum += measuredDb[i] - targetDb[i]; count++; }
        if (count == 0) for (int i = lo; i <= hi; i++) { sum += measuredDb[i] - targetDb[i]; count++; }
        double shift = count > 0 ? sum / count : 0;
        double[] raw = new double[n];
        for (int i = 0; i < n; i++) raw[i] = targetDb[i] - (measuredDb[i] - shift);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            boolean in = i >= lo && i <= hi;
            double total = raw[i];
            int k = 1;
            if (i > 0 && (!in || i - 1 >= lo)) { total += raw[i - 1]; k++; }
            if (i < n - 1 && (!in || i + 1 <= hi)) { total += raw[i + 1]; k++; }
            double v = SoundAdapt.clamp(total / k, -Math.abs(maxCut), Math.abs(maxBoost));
            out[i] = in ? v : Math.min(v, 0);
        }
        return out;
    }

    /** A correction at a strength of 0 to 1: the owner's "how much of it" slider. Null stays null. */
    public static double[] scaled(double[] correctionDb, double strength) {
        if (correctionDb == null) return null;
        double k = SoundAdapt.clamp(strength, 0, 1);
        double[] out = new double[correctionDb.length];
        for (int i = 0; i < out.length; i++) out[i] = correctionDb[i] * k;
        return out;
    }

    /** A band curve read at any pitch: straight lines between band centres on a log-pitch scale, flat past the ends. */
    public static double at(double[] bandDb, double hz) {
        double[] c = bandCentres();
        int n = Math.min(c.length, bandDb.length);
        if (n == 0) return 0;
        if (hz <= c[0]) return bandDb[0];
        if (hz >= c[n - 1]) return bandDb[n - 1];
        for (int i = 1; i < n; i++) {
            if (hz <= c[i]) {
                double f = Math.log(hz / c[i - 1]) / Math.log(c[i] / c[i - 1]);
                return bandDb[i - 1] + f * (bandDb[i] - bandDb[i - 1]);
            }
        }
        return bandDb[n - 1];
    }

    /** A measurement mic's calibration: what the mic adds at each pitch, to be taken off what it reads. */
    public static final class MicCal {
        public final double[] hz, db;
        MicCal(double[] hz, double[] db) { this.hz = hz; this.db = db; }

        /** The mic's own error at a pitch, by straight lines between the file's points; 0 with no points. */
        public double at(double f) {
            if (hz.length == 0) return 0;
            if (f <= hz[0]) return db[0];
            if (f >= hz[hz.length - 1]) return db[db.length - 1];
            for (int i = 1; i < hz.length; i++) {
                if (f <= hz[i]) {
                    double t = (f - hz[i - 1]) / (hz[i] - hz[i - 1]);
                    return db[i - 1] + t * (db[i] - db[i - 1]);
                }
            }
            return db[db.length - 1];
        }
    }

    /**
     * Reads a calibration file as measurement mics ship them (miniDSP UMIK and the like): one "frequency
     * level" pair per line, split by spaces, tabs or commas, with any header or comment lines skipped.
     * Lines that do not start with two numbers are ignored; an empty result means no usable file.
     */
    public static MicCal parseMicCal(String text) {
        List<double[]> rows = new ArrayList<>();
        if (text != null) {
            for (String line : text.split("\\r?\\n")) {
                String[] p = line.trim().split("[\\s,;]+");
                if (p.length < 2) continue;
                try {
                    double f = Double.parseDouble(p[0]), d = Double.parseDouble(p[1]);
                    if (f > 0 && (rows.isEmpty() || f > rows.get(rows.size() - 1)[0])) rows.add(new double[]{f, d});
                } catch (NumberFormatException ignored) { }
            }
        }
        double[] hz = new double[rows.size()], db = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) { hz[i] = rows.get(i)[0]; db[i] = rows.get(i)[1]; }
        return new MicCal(hz, db);
    }

    /** Takes a mic's own error off a band measurement, band by band. */
    public static double[] applyMicCal(double[] bandDb, MicCal cal) {
        double[] c = bandCentres();
        double[] out = new double[bandDb.length];
        for (int i = 0; i < bandDb.length; i++) out[i] = bandDb[i] - (i < c.length ? cal.at(c[i]) : 0);
        return out;
    }

    private SoundTune() { }
}
