package com.strickland.jeepdash.core;

/** The music window's text, and which music app it follows. */
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

    public static void followsTheMostRecentlyPlayingSession() {
        T.eq(2, NowPlaying.pick(new boolean[]{false, true, true}, new long[]{900, 100, 500}), "latest playing one");
        T.eq(1, NowPlaying.pick(new boolean[]{false, true}, new long[]{900, 100}), "a playing one beats a paused one");
    }

    public static void withNothingPlayingTheTopSessionStaysSoPlayCanBePressed() {
        T.eq(0, NowPlaying.pick(new boolean[]{false, false}, new long[]{100, 900}), "the system's first (most recent)");
        T.eq(-1, NowPlaying.pick(new boolean[0], new long[0]), "no sessions");
    }

    public static void theWindowShowsTitleAndArtistOnTwoLines() {
        T.eq("Highway Star", NowPlaying.titleLine(true, true, "Highway Star", "Deep Purple", 40), "title on top");
        T.eq("Deep Purple", NowPlaying.artistLine(true, true, "Highway Star", "Deep Purple", 40), "artist under it");
        T.eq("Deep Purple", NowPlaying.titleLine(true, true, null, "Deep Purple", 40), "no title: the artist moves up");
        T.eq("", NowPlaying.artistLine(true, true, null, "Deep Purple", 40), "and is not shown twice");
        T.eq(NowPlaying.NO_ACCESS, NowPlaying.titleLine(false, true, "T", "A", 80), "no access");
        T.eq("", NowPlaying.artistLine(false, true, "T", "A", 40), "nothing under it");
        T.eq(NowPlaying.NOTHING, NowPlaying.titleLine(true, false, "T", "A", 40), "no music app");
        T.eq("Highway\u2026", NowPlaying.titleLine(true, true, "Highway Star", null, 8), "cut");
    }

    public static void anOldBarSettingTurnsTheButtonOn() {
        T.ok(NowPlaying.buttonOn(null, "top"), "old bar at the top");
        T.ok(NowPlaying.buttonOn(null, "bottom"), "old bar at the bottom");
        T.ok(!NowPlaying.buttonOn(null, "off"), "old bar off");
        T.ok(!NowPlaying.buttonOn(null, null), "nothing saved: off");
        T.ok(!NowPlaying.buttonOn(false, "top"), "the new setting wins once saved");
        T.ok(NowPlaying.buttonOn(true, null), "on");
    }
}
