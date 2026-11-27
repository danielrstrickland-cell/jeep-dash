package com.strickland.jeepdash.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The MilkDrop preset list: every preset found, the starred favourites, and the order they were shown in so
 * Prev can step back and Next can step forward again. Next picks at random: from all of them, from all with
 * starred ones three times as likely ("Favour"), or from starred ones only ("Only", which falls back to all
 * when nothing is starred).
 */
public final class MilkPlaylist {
    public static final int ALL = 0, FAVOUR = 1, ONLY = 2;
    private static final int HISTORY = 100;

    private final List<String> presets = new ArrayList<>();
    private final Set<String> stars = new LinkedHashSet<>();
    private final List<String> history = new ArrayList<>();
    private final Random random;
    private int at = -1;                                         // where in the history the current preset is

    public MilkPlaylist(long seed) { random = new Random(seed); }

    /** Replaces the list (for example once the full pack is downloaded), keeping the current preset if it is there. */
    public void setPresets(List<String> paths) {
        presets.clear();
        presets.addAll(paths);
        Collections.sort(presets);
        history.retainAll(presets);
        at = history.isEmpty() ? -1 : Math.min(at, history.size() - 1);
    }

    public int size() { return presets.size(); }

    public String current() { return at < 0 ? null : history.get(at); }

    /** 1-based place of the current preset in the sorted list, or 0. */
    public int number() { return current() == null ? 0 : presets.indexOf(current()) + 1; }

    public String next(int mode) {
        if (presets.isEmpty()) return null;
        if (at + 1 < history.size()) return history.get(++at);         // stepping forward again after Prev
        String pick = pick(mode);
        history.add(pick);
        if (history.size() > HISTORY) history.remove(0);
        at = history.size() - 1;
        return pick;
    }

    public String prev() {
        if (at > 0) at--;
        return current();
    }

    private String pick(int mode) {
        List<String> starred = new ArrayList<>();
        for (String s : stars) if (presets.contains(s)) starred.add(s);
        String now = current();
        for (int tries = 0; tries < 8; tries++) {
            String p;
            if (mode == ONLY && !starred.isEmpty()) p = starred.get(random.nextInt(starred.size()));
            else if (mode == FAVOUR && !starred.isEmpty() && random.nextInt(presets.size() + 3 * starred.size()) < 3 * starred.size())
                p = starred.get(random.nextInt(starred.size()));
            else p = presets.get(random.nextInt(presets.size()));
            if (!p.equals(now) || presets.size() == 1) return p;
        }
        return presets.get(random.nextInt(presets.size()));
    }

    public boolean starred(String path) { return path != null && stars.contains(path); }

    public void star(String path, boolean on) {
        if (path == null) return;
        if (on) stars.add(path);
        else stars.remove(path);
    }

    /** Starred presets as one line for saving, by preset file name so they survive a re-download. */
    public String saveStars() {
        StringBuilder out = new StringBuilder();
        for (String s : stars) {
            if (out.length() > 0) out.append('\n');
            out.append(name(s));
        }
        return out.toString();
    }

    /** Restores stars saved by {@link #saveStars}, matched by file name against the current list. */
    public void loadStars(String saved) {
        stars.clear();
        if (saved == null || saved.isEmpty()) return;
        Set<String> names = new LinkedHashSet<>(java.util.Arrays.asList(saved.split("\n")));
        for (String p : presets) if (names.contains(name(p))) stars.add(p);
    }

    /** "Dancer - glowsticks 02" from a path, for the name flash and the log. */
    public static String name(String path) {
        if (path == null) return "";
        String n = path.substring(path.lastIndexOf('/') + 1);
        return n.endsWith(".milk") ? n.substring(0, n.length() - 5) : n;
    }
}
