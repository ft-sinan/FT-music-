package com.ftmusic.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Public-domain recordings already included in the web app. See the root README for credits. */
final class Catalog {
    static final List<Song> SONGS = Collections.unmodifiableList(Arrays.asList(
        local("blue-skies", "Blue Skies", "HoliznaCC0", "Happy Lo-Fi", "cover-blue-skies.webp"),
        local("city-in-the-rearview", "City In The Rearview", "HoliznaCC0", "Chill Beats", "cover-night-drive.webp"),
        local("morning-coffee", "Morning Coffee", "HoliznaCC0", "Lo-Fi And Chill", "cover-slow-mornings.webp"),
        local("day-dreams", "Day Dreams", "HoliznaCC0", "Retro Wave", "cover-electric-dreams.webp"),
        local("deja-vu", "Deja Vu", "HoliznaCC0", "Chillout Instrumentals", "cover-after-hours.webp"),
        local("night-life", "Night Life", "HoliznaCC0", "Retro Wave", "cover-electric-dreams.webp"),
        local("breath", "Breath", "HoliznaCC0", "Funk", "cover-good-energy.webp"),
        local("happy-dance", "Happy Dance", "HoliznaCC0", "Happy Pop Electronic", "cover-good-energy.webp"),
        local("duru-roomscene-lofi", "Roomscene Lo-fi", "DURU-AI", "Roomscene Lo-fi", "cover-focus-flow.webp"),
        local("duru-rhythm-fever", "Rhythm Fever", "DURU-AI", "Rhythm Fever", "cover-after-hours.webp")
    ));

    private static Song local(String id, String title, String artist, String album, String artwork) {
        return new Song(id, title, artist, album, "images/" + artwork, "audio/" + id + ".mp3", "");
    }

    static Song find(String id) {
        for (Song song : SONGS) if (song.id.equals(id)) return song;
        return null;
    }

    static List<Song> search(String query) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<Song> result = new ArrayList<Song>();
        if (needle.isEmpty()) return result;
        for (Song song : SONGS) {
            if ((song.title + " " + song.artist + " " + song.album).toLowerCase(Locale.ROOT).contains(needle)) result.add(song);
        }
        return result;
    }

    static List<Song> mix(int... indices) {
        List<Song> result = new ArrayList<Song>();
        for (int index : indices) if (index >= 0 && index < SONGS.size()) result.add(SONGS.get(index));
        return result;
    }

    private Catalog() { }
}
