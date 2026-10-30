package com.ftmusic.app;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Home reflects the listener's history, but never promotes the bundled demo catalog. */
final class HomeFeed {
    static List<Song> recent(List<Song> history, int limit) {
        List<Song> result = new ArrayList<Song>();
        if (history == null || limit <= 0) return result;
        Set<String> seen = new HashSet<String>();
        for (Song song : history) {
            if (song == null || Catalog.find(song.id) != null || !seen.add(song.id)) continue;
            result.add(song);
            if (result.size() >= limit) break;
        }
        return result;
    }

    private HomeFeed() { }
}
