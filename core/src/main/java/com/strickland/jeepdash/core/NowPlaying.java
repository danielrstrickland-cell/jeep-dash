package com.strickland.jeepdash.core;

/**
 * The small decisions behind the now-playing bar: the one line of text it shows, and which of the music apps
 * that are open it follows. The Android side only reads the music app's session and passes the pieces in.
 */
public final class NowPlaying {
    private NowPlaying() { }

    public static final String NOTHING = "Nothing playing";
    public static final String NO_ACCESS = "Tap here to allow notification access, to show what is playing";
    static final String BETWEEN = " - ";
    static final String ELLIPSIS = "…";

    /**
     * What the bar says: why it cannot show anything (no access, no music app), or the track. Old text is never
     * kept: with no session the plain message replaces it.
     */
    public static String text(boolean access, boolean hasSession, String title, String artist, int maxChars) {
        if (!access) return NO_ACCESS;
        if (!hasSession) return NOTHING;
        return line(title, artist, maxChars);
    }

    /** "Title - Artist", or whichever of the two there is, on one line, cut with an ellipsis past maxChars. */
    public static String line(String title, String artist, int maxChars) {
        String t = tidy(title), a = tidy(artist);
        String s = t.isEmpty() ? a : a.isEmpty() ? t : t + BETWEEN + a;
        if (s.isEmpty()) return NOTHING;
        if (maxChars > 1 && s.length() > maxChars) s = s.substring(0, maxChars - 1).trim() + ELLIPSIS;
        return s;
    }

    /** One line with single spaces: some apps send line breaks or padding in their track names. */
    private static String tidy(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    /**
     * Which session to follow, from the system's list (most recent first): the playing one that started or
     * changed most lately; with nothing playing, the first one, so a paused song still shows and play can be
     * pressed. -1 when there are none.
     *
     * @param since when each session's playback state last changed (any one clock)
     */
    public static int pick(boolean[] playing, long[] since) {
        int best = -1;
        for (int i = 0; i < playing.length; i++)
            if (playing[i] && (best < 0 || since[i] > since[best])) best = i;
        if (best >= 0) return best;
        return playing.length > 0 ? 0 : -1;
    }
}
