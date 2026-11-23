package com.strickland.jeepdash.core;

/**
 * The small decisions behind the music button's window: the lines of text it shows, and which of the music
 * apps that are open it follows. The Android side only reads the music app's session and passes the pieces in.
 */
public final class NowPlaying {
    private NowPlaying() { }

    public static final String NOTHING = "Nothing playing";
    public static final String NO_ACCESS = "Tap here to allow notification access, to show what is playing";
    static final String BETWEEN = " - ";
    static final String ELLIPSIS = "…";

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

    /** The music window's top line: the title (the artist when there is no title), or why there is nothing. */
    public static String titleLine(boolean access, boolean hasSession, String title, String artist, int maxChars) {
        if (!access) return NO_ACCESS;
        if (!hasSession) return NOTHING;
        String t = tidy(title);
        return line(t.isEmpty() ? null : t, t.isEmpty() ? artist : null, maxChars);
    }

    /** The music window's second line: the artist, when the title is on the line above; otherwise empty. */
    public static String artistLine(boolean access, boolean hasSession, String title, String artist, int maxChars) {
        if (!access || !hasSession || tidy(title).isEmpty() || tidy(artist).isEmpty()) return "";
        return line(null, artist, maxChars);
    }

    /**
     * Whether the music button is on. The always-on bar it replaced was saved as off / top / bottom; until the
     * new setting is saved, a bar that was on turns the button on.
     */
    public static boolean buttonOn(Boolean saved, String oldBar) {
        if (saved != null) return saved;
        return "top".equals(oldBar) || "bottom".equals(oldBar);
    }
}
