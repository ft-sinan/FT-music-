package com.ftmusic.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned sync data: likes and playlists only. History, audio and lyrics stay on-device. */
final class SyncLibrary {
    static final class Playlist {
        final String id, name;
        final List<Song> songs;
        Playlist(String id, String name, List<Song> songs) { this.id = id; this.name = name; this.songs = new ArrayList<Song>(songs); }
    }
    final List<Song> liked;
    final List<Playlist> playlists;
    SyncLibrary(List<Song> liked, List<Playlist> playlists) { this.liked = new ArrayList<Song>(liked); this.playlists = new ArrayList<Playlist>(playlists); }

    JSONObject toJson() throws JSONException {
        JSONArray likes = new JSONArray(), mixes = new JSONArray();
        for (Song song : liked) likes.put(song.toJson());
        for (Playlist playlist : playlists) {
            JSONArray items = new JSONArray(); for (Song song : playlist.songs) items.put(song.toJson());
            mixes.put(new JSONObject().put("id", playlist.id).put("name", playlist.name).put("songs", items));
        }
        return new JSONObject().put("liked", likes).put("playlists", mixes);
    }
    static SyncLibrary fromJson(JSONObject object) throws IOException {
        if (object == null) throw new IOException("Invalid synced library.");
        JSONArray likes = object.optJSONArray("liked"), mixes = object.optJSONArray("playlists");
        if (likes == null || mixes == null || mixes.length() > 100) throw new IOException("Invalid synced library.");
        int[] count = new int[] {0};
        List<Song> liked = parseSongs(likes, count); List<Playlist> playlists = new ArrayList<Playlist>();
        Map<String, Boolean> ids = new LinkedHashMap<String, Boolean>();
        for (int i = 0; i < mixes.length(); i++) {
            JSONObject item = mixes.optJSONObject(i); if (item == null) throw new IOException("Invalid synced playlist.");
            String id = strictString(item, "id", 80, true), name = strictString(item, "name", 60, true);
            if (!id.matches("[A-Za-z0-9_-]+") || ids.containsKey(id)) throw new IOException("Invalid or duplicate synced playlist.");
            ids.put(id, true);
            playlists.add(new Playlist(id, name, parseSongs(item.optJSONArray("songs"), count)));
        }
        return new SyncLibrary(liked, playlists);
    }
    private static List<Song> parseSongs(JSONArray array, int[] count) throws IOException {
        if (array == null || array.length() > 500) throw new IOException("Invalid synced songs.");
        List<Song> songs = new ArrayList<Song>(); Map<String, Boolean> ids = new LinkedHashMap<String, Boolean>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i); if (item == null) throw new IOException("Invalid synced song.");
            String id = strictString(item, "id", 100, true);
            strictString(item, "title", 200, true); strictString(item, "artist", 200, false);
            strictString(item, "album", 200, false); strictString(item, "artwork", 2048, false);
            strictString(item, "audioAsset", 300, false); strictString(item, "videoId", 11, false);
            Song song = Song.fromJson(item);
            // Never overwrite a remote library that this app version cannot completely read.
            if (song == null || ids.containsKey(id) || ++count[0] > 2000) throw new IOException("This library contains unsupported or duplicate tracks; update the app before syncing.");
            ids.put(id, true); songs.add(song);
        }
        return songs;
    }
    private static String strictString(JSONObject object, String key, int max, boolean required) throws IOException {
        Object value = object.opt(key);
        if (!(value instanceof String)) throw new IOException("Invalid synced library field.");
        String text = (String) value;
        if (text.length() > max || (required && text.trim().isEmpty())) throw new IOException("Invalid synced library field.");
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) < 32) throw new IOException("Invalid synced library field.");
        return text;
    }
    static boolean same(SyncLibrary a, SyncLibrary b) {
        return sameSongs(a.liked, b.liked) && samePlaylists(a.playlists, b.playlists);
    }
    private static boolean samePlaylists(List<Playlist> a, List<Playlist> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Playlist x = a.get(i), y = b.get(i);
            if (!x.id.equals(y.id) || !x.name.equals(y.name) || !sameSongs(x.songs, y.songs)) return false;
        }
        return true;
    }
    private static boolean sameSongs(List<Song> a, List<Song> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).id.equals(b.get(i).id)) return false;
        return true;
    }
    private static boolean samePlaylist(Playlist a, Playlist b) { return a.name.equals(b.name) && sameSongs(a.songs, b.songs); }
    static final class Conflict extends IOException { Conflict(String message) { super(message); } }

    /** First sync is additive. Thereafter a common base preserves removals and detects destructive conflicts. */
    static SyncLibrary merge(SyncLibrary base, SyncLibrary local, SyncLibrary remote) throws Conflict {
        List<Song> likes = mergeSongs(base == null ? null : base.liked, local.liked, remote.liked);
        if (base != null && samePlaylists(local.playlists, base.playlists)) return new SyncLibrary(likes, remote.playlists);
        if (base != null && samePlaylists(remote.playlists, base.playlists)) return new SyncLibrary(likes, local.playlists);
        Map<String, Playlist> b = mapPlaylists(base == null ? new ArrayList<Playlist>() : base.playlists);
        Map<String, Playlist> l = mapPlaylists(local.playlists), r = mapPlaylists(remote.playlists);
        LinkedHashMap<String, Playlist> order = new LinkedHashMap<String, Playlist>(); order.putAll(l);
        for (Map.Entry<String, Playlist> entry : r.entrySet()) if (!order.containsKey(entry.getKey())) order.put(entry.getKey(), entry.getValue());
        List<Playlist> result = new ArrayList<Playlist>();
        for (String id : order.keySet()) {
            Playlist old = b.get(id), left = l.get(id), right = r.get(id);
            if (old != null && (left == null || right == null)) {
                Playlist survivor = left == null ? right : left;
                if (survivor != null && !samePlaylist(old, survivor)) throw new Conflict("The playlist “" + survivor.name + "” was deleted on one device and edited on another. Review it before syncing.");
                continue;
            }
            if (left == null) { if (right != null) result.add(right); continue; }
            if (right == null) { result.add(left); continue; }
            String name;
            if (left.name.equals(right.name)) name = left.name;
            else if (old != null && left.name.equals(old.name)) name = right.name;
            else if (old != null && right.name.equals(old.name)) name = left.name;
            else throw new Conflict("Both devices changed the name of a playlist. Review it before syncing.");
            result.add(new Playlist(id, name, mergeSongs(old == null ? null : old.songs, left.songs, right.songs)));
        }
        return new SyncLibrary(likes, result);
    }
    private static Map<String, Playlist> mapPlaylists(List<Playlist> items) {
        Map<String, Playlist> result = new LinkedHashMap<String, Playlist>(); for (Playlist p : items) result.put(p.id, p); return result;
    }
    private static List<Song> mergeSongs(List<Song> base, List<Song> local, List<Song> remote) {
        // If only one side changed, preserve its order rather than endlessly reordering on each device.
        if (base != null && sameSongs(local, base)) return new ArrayList<Song>(remote);
        if (base != null && sameSongs(remote, base)) return new ArrayList<Song>(local);
        Map<String, Song> b = mapSongs(base == null ? new ArrayList<Song>() : base), l = mapSongs(local), r = mapSongs(remote);
        LinkedHashMap<String, Song> order = new LinkedHashMap<String, Song>(); order.putAll(l);
        for (Map.Entry<String, Song> entry : r.entrySet()) if (!order.containsKey(entry.getKey())) order.put(entry.getKey(), entry.getValue());
        List<Song> result = new ArrayList<Song>();
        for (Map.Entry<String, Song> entry : order.entrySet()) {
            String id = entry.getKey();
            if (base != null && b.containsKey(id) && (!l.containsKey(id) || !r.containsKey(id))) continue;
            result.add(entry.getValue());
        }
        return result;
    }
    private static Map<String, Song> mapSongs(List<Song> songs) {
        Map<String, Song> result = new LinkedHashMap<String, Song>(); for (Song s : songs) result.put(s.id, s); return result;
    }
}
