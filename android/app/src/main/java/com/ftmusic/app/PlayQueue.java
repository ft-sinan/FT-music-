package com.ftmusic.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Simple queue state, kept independent of Android so its edge cases can be tested on the JVM. */
final class PlayQueue {
    private final List<Song> songs = new ArrayList<Song>();
    // A fresh install has no selected song; sample tracks must not appear by default.
    private int index = -1;

    void replace(List<Song> source, int selected) {
        if (source == null || source.isEmpty()) return;
        songs.clear();
        songs.addAll(source);
        index = Math.max(0, Math.min(selected, songs.size() - 1));
    }
    Song current() { return index >= 0 && index < songs.size() ? songs.get(index) : null; }
    List<Song> items() { return Collections.unmodifiableList(songs); }
    int index() { return index; }
    boolean next() {
        if (index < 0 || index + 1 >= songs.size()) return false;
        index++;
        return true;
    }
    boolean previous() {
        if (index <= 0) return false;
        index--;
        return true;
    }
    boolean select(int next) {
        if (next < 0 || next >= songs.size()) return false;
        index = next;
        return true;
    }
    void add(Song song) {
        if (song == null) return;
        songs.add(song);
        if (index < 0) index = 0;
    }
    void shuffleUpcoming() {
        if (index < 0) return;
        List<Song> upcoming = new ArrayList<Song>(songs.subList(index + 1, songs.size()));
        Collections.shuffle(upcoming);
        for (int i = 0; i < upcoming.size(); i++) songs.set(index + 1 + i, upcoming.get(i));
    }
}
