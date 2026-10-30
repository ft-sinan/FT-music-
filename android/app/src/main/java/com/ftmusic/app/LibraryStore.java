package com.ftmusic.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Local library; likes and playlists are sent only when the user explicitly syncs. */
final class LibraryStore {
    static final class Playlist {
        final String id;
        final String name;
        final List<Song> songs;
        Playlist(String id, String name, List<Song> songs) { this.id = id; this.name = name; this.songs = songs; }
    }

    private final SharedPreferences prefs;
    LibraryStore(Context context) { prefs = context.getSharedPreferences("ft_music_library", Context.MODE_PRIVATE); }

    private List<Song> readSongs(String key) {
        List<Song> songs = new ArrayList<Song>();
        try {
            JSONArray array = new JSONArray(prefs.getString(key, "[]"));
            for (int i = 0; i < array.length() && i < 500; i++) {
                Song song = Song.fromJson(array.optJSONObject(i));
                if (song != null) songs.add(song);
            }
        } catch (JSONException ignored) { }
        return songs;
    }

    private void writeSongs(String key, List<Song> songs) {
        JSONArray array = new JSONArray();
        for (Song song : songs) {
            try { array.put(song.toJson()); } catch (JSONException ignored) { }
        }
        prefs.edit().putString(key, array.toString()).apply();
    }

    List<Song> liked() { return readSongs("liked"); }
    List<Song> recent() { return readSongs("recent"); }
    boolean isLiked(Song song) { for (Song liked : liked()) if (liked.id.equals(song.id)) return true; return false; }
    boolean toggleLike(Song song) {
        List<Song> liked = liked();
        for (int i = 0; i < liked.size(); i++) {
            if (liked.get(i).id.equals(song.id)) { liked.remove(i); writeSongs("liked", liked); return false; }
        }
        liked.add(0, song);
        writeSongs("liked", liked);
        return true;
    }
    void record(Song song) {
        List<Song> recent = recent();
        for (int i = recent.size() - 1; i >= 0; i--) if (recent.get(i).id.equals(song.id)) recent.remove(i);
        recent.add(0, song);
        if (recent.size() > 50) recent = new ArrayList<Song>(recent.subList(0, 50));
        writeSongs("recent", recent);
    }

    List<Playlist> playlists() {
        List<Playlist> result = new ArrayList<Playlist>();
        try {
            JSONArray array = new JSONArray(prefs.getString("playlists", "[]"));
            for (int i = 0; i < array.length() && i < 100; i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", ""), name = item.optString("name", "");
                if (id.isEmpty() || name.isEmpty()) continue;
                List<Song> songs = new ArrayList<Song>();
                JSONArray items = item.optJSONArray("songs");
                if (items != null) for (int j = 0; j < items.length() && j < 500; j++) {
                    Song song = Song.fromJson(items.optJSONObject(j));
                    if (song != null) songs.add(song);
                }
                result.add(new Playlist(id, name, songs));
            }
        } catch (JSONException ignored) { }
        return result;
    }
    private void writePlaylists(List<Playlist> playlists) {
        JSONArray array = new JSONArray();
        for (Playlist playlist : playlists) {
            try {
                JSONObject item = new JSONObject();
                item.put("id", playlist.id);
                item.put("name", playlist.name);
                JSONArray tracks = new JSONArray();
                for (Song song : playlist.songs) tracks.put(song.toJson());
                item.put("songs", tracks);
                array.put(item);
            } catch (JSONException ignored) { }
        }
        prefs.edit().putString("playlists", array.toString()).apply();
    }
    Playlist create(String title) {
        String name = title.trim();
        if (name.isEmpty()) return null;
        List<Playlist> playlists = playlists();
        Playlist playlist = new Playlist(UUID.randomUUID().toString(), name, new ArrayList<Song>());
        playlists.add(0, playlist);
        writePlaylists(playlists);
        return playlist;
    }
    boolean addToPlaylist(String id, Song song) {
        List<Playlist> playlists = playlists();
        for (Playlist playlist : playlists) if (playlist.id.equals(id)) {
            for (Song existing : playlist.songs) if (existing.id.equals(song.id)) return false;
            playlist.songs.add(song);
            writePlaylists(playlists);
            return true;
        }
        return false;
    }
    void removeFromPlaylist(String id, String songId) {
        List<Playlist> playlists = playlists();
        for (Playlist playlist : playlists) if (playlist.id.equals(id)) {
            for (int i = playlist.songs.size() - 1; i >= 0; i--) if (playlist.songs.get(i).id.equals(songId)) playlist.songs.remove(i);
        }
        writePlaylists(playlists);
    }
    void deletePlaylist(String id) {
        List<Playlist> playlists = playlists();
        for (int i = playlists.size() - 1; i >= 0; i--) if (playlists.get(i).id.equals(id)) playlists.remove(i);
        writePlaylists(playlists);
    }
    Playlist playlist(String id) {
        for (Playlist playlist : playlists()) if (playlist.id.equals(id)) return playlist;
        return new Playlist("", "Playlist", Collections.<Song>emptyList());
    }

    SyncLibrary snapshot() {
        List<SyncLibrary.Playlist> mixes = new ArrayList<SyncLibrary.Playlist>();
        for (Playlist playlist : playlists()) mixes.add(new SyncLibrary.Playlist(playlist.id, playlist.name, playlist.songs));
        return new SyncLibrary(liked(), mixes);
    }
    private String baseKey(String identity) {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256").digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder("sync_base_");
            for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return value.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    SyncLibrary syncBase(String identity) throws java.io.IOException {
        String value = prefs.getString(baseKey(identity), "");
        if (value.isEmpty()) return null;
        try { return SyncLibrary.fromJson(new JSONObject(value)); }
        catch (JSONException problem) { throw new java.io.IOException("The local sync baseline is invalid. No changes were uploaded.", problem); }
    }
    /** Commit the baseline with the library, so interrupted sync cannot silently lose changes. */
    boolean applySynced(String identity, SyncLibrary captured, SyncLibrary merged) throws java.io.IOException {
        try {
            boolean unchanged = SyncLibrary.same(snapshot(), captured);
            SharedPreferences.Editor edit = prefs.edit();
            JSONObject baseline = (unchanged ? merged : captured).toJson();
            edit.putString(baseKey(identity), baseline.toString());
            if (unchanged) {
                JSONObject next = merged.toJson();
                edit.putString("liked", next.getJSONArray("liked").toString());
                edit.putString("playlists", next.getJSONArray("playlists").toString());
            }
            if (!edit.commit()) throw new java.io.IOException("Could not save the local sync state. Try syncing again.");
            return unchanged;
        } catch (JSONException problem) { throw new java.io.IOException("Could not save the synced library.", problem); }
    }
}
