package com.strickland.jeepdash.core;

import java.util.Arrays;

public final class MilkPlaylistTest {
    private static MilkPlaylist list() {
        MilkPlaylist p = new MilkPlaylist(1);
        p.setPresets(Arrays.asList("/m/c.milk", "/m/a.milk", "/m/b.milk", "/m/d.milk", "/m/e.milk"));
        return p;
    }

    public static void prevAndNextWalkTheHistory() {
        MilkPlaylist p = list();
        String one = p.next(MilkPlaylist.ALL), two = p.next(MilkPlaylist.ALL), three = p.next(MilkPlaylist.ALL);
        T.ok(!one.equals(two) && !two.equals(three), "never the same twice in a row");
        T.eq(two, p.prev(), "back one");
        T.eq(one, p.prev(), "back two");
        T.eq(one, p.prev(), "stops at the start");
        T.eq(two, p.next(MilkPlaylist.ALL), "forward again replays the history");
        T.eq(three, p.next(MilkPlaylist.ALL), "and again");
        T.ok(p.number() >= 1 && p.number() <= 5, "a place in the list");
    }

    public static void onlyStarredPlaysStars() {
        MilkPlaylist p = list();
        p.star("/m/b.milk", true);
        p.star("/m/d.milk", true);
        for (int i = 0; i < 30; i++) {
            String n = p.next(MilkPlaylist.ONLY);
            T.ok(n.equals("/m/b.milk") || n.equals("/m/d.milk"), "only starred: " + n);
        }
        MilkPlaylist q = list();
        T.ok(q.next(MilkPlaylist.ONLY) != null, "nothing starred: plays them all");
    }

    public static void favourLeansToStars() {
        MilkPlaylist p = list();
        p.star("/m/e.milk", true);
        int hits = 0;
        for (int i = 0; i < 400; i++) if (p.next(MilkPlaylist.FAVOUR).equals("/m/e.milk")) hits++;
        T.ok(hits > 100, "a starred preset comes up much more than 1 in 5: " + hits);
    }

    public static void starsSaveByName() {
        MilkPlaylist p = list();
        p.star("/m/c.milk", true);
        String saved = p.saveStars();
        MilkPlaylist q = new MilkPlaylist(2);
        q.setPresets(Arrays.asList("/other/place/c.milk", "/other/place/a.milk"));
        q.loadStars(saved);
        T.ok(q.starred("/other/place/c.milk"), "a star survives a re-download to a new folder");
        T.eq("c", MilkPlaylist.name("/other/place/c.milk"), "name without folder or .milk");
    }

    public static void slowPresetsComeUpLess() {
        MilkPlaylist p = list();
        p.markSlow("/m/a.milk");
        int hits = 0;
        for (int i = 0; i < 1000; i++) if ("/m/a.milk".equals(p.next(MilkPlaylist.ALL))) hits++;
        T.ok(hits > 10 && hits < 120, "a slow preset still plays, but well under 1 in 5: " + hits);
        MilkPlaylist q = new MilkPlaylist(3);
        q.setPresets(java.util.Arrays.asList("/x/a.milk", "/x/b.milk"));
        q.loadSlow(p.saveSlow());
        T.ok(q.isSlow("/x/a.milk") && !q.isSlow("/x/b.milk"), "remembered by name");
    }
}
