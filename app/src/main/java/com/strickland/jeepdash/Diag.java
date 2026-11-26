package com.strickland.jeepdash;

import android.os.SystemClock;
import com.strickland.jeepdash.core.DiagLog;

/**
 * The app's one diagnostics log, shared by every screen and the link service: errors and issues from the
 * sound engine, calibration, the stereo link and anything else that wants to report itself. Kept in memory
 * for the life of the process; the Sound hub shows and shares it.
 */
final class Diag {
    static final DiagLog LOG = new DiagLog(1000);

    private Diag() { }

    static long now() { return SystemClock.elapsedRealtime(); }

    static void info(String tag, String msg) { LOG.info(now(), tag, msg); }

    static void warn(String tag, String msg) { LOG.warn(now(), tag, msg); }

    static void error(String tag, String msg) { LOG.error(now(), tag, msg); }
}
