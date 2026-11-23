package com.strickland.jeepdash.core;

/** The now-playing bar's line of text, and which music app it follows. */
public class NowPlayingTest {

    public static void titleAndArtistShareTheLine() {
        T.eq("Highway Star - Deep Purple", NowPlaying.line("Highway Star", "Deep Purple", 60), "both");
    }

    public static void aMissingArtistOrTitleLeavesTheOther() {
        T.eq("Highway Star", NowPlaying.line("Highway Star", null, 60), "no artist");
        T.eq("Highway Star", NowPlaying.line("Highway Star", "  ", 60), "blank artist");
        T.eq("Deep Purple", NowPlaying.line(null, "Deep Purple", 60), "no title");
        T.eq("Deep Purple", NowPlaying.line("", "Deep Purple", 60), "empty title");
    }

    public static void nothingToShowGivesThePlainMessage() {
        T.eq(NowPlaying.NOTHING, NowPlaying.line(null, null, 60), "nothing");
        T.eq(NowPlaying.NOTHING, NowPlaying.line(" ", "\n", 60), "only spaces");
    }

    public static void spacesAndLineBreaksAreTidied() {
        T.eq("A B - C", NowPlaying.line("  A\n B ", "\tC ", 60), "one line, single spaces");
    }

    public static void longTextIsCutWithAnEllipsis() {
        String s = NowPlaying.line("A very long title that goes on", "and a long artist name", 20);
        T.eq(20, s.length(), "fits");
        T.ok(s.endsWith("…"), "ends with an ellipsis: " + s);
        T.eq("A very long title t…", s, "cut at the end");
        T.eq("Short", NowPlaying.line("Short", null, 5), "exactly the limit is not cut");
    }

    public static void textSaysWhyWhenThereIsNoAccessOrNoMusicApp() {
        T.eq(NowPlaying.NO_ACCESS, NowPlaying.text(false, true, "T", "A", 60), "no access beats old text");
        T.eq(NowPlaying.NOTHING, NowPlaying.text(true, false, "T", "A", 60), "no session: no stale text");
        T.eq("T - A", NowPlaying.text(true, true, "T", "A", 60), "playing");
    }

    public static void followsTheMostRecentlyPlayingSession() {
        T.eq(2, NowPlaying.pick(new boolean[]{false, true, true}, new long[]{900, 100, 500}), "latest playing one");
        T.eq(1, NowPlaying.pick(new boolean[]{false, true}, new long[]{900, 100}), "a playing one beats a paused one");
    }

    public static void withNothingPlayingTheTopSessionStaysSoPlayCanBePressed() {
        T.eq(0, NowPlaying.pick(new boolean[]{false, false}, new long[]{100, 900}), "the system's first (most recent)");
        T.eq(-1, NowPlaying.pick(new boolean[0], new long[0]), "no sessions");
    }
}
