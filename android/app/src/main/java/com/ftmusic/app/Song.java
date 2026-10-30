package com.ftmusic.app;

import org.json.JSONException;
import org.json.JSONObject;

/** A bundled recording or an anonymous YouTube Music search result. */
final class Song {
    final String id;
    final String title;
    final String artist;
    final String album;
    final String artwork;
    final String audioAsset;
    final String videoId;

    Song(String id, String title, String artist, String album, String artwork, String audioAsset, String videoId) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.artwork = artwork;
        this.audioAsset = audioAsset;
        this.videoId = videoId;
    }

    boolean isYouTube() { return videoId != null && !videoId.isEmpty(); }

    JSONObject toJson() throws JSONException {
        JSONObject item = new JSONObject();
        item.put("id", id);
        item.put("title", title);
        item.put("artist", artist);
        item.put("album", album);
        item.put("artwork", artwork);
        item.put("audioAsset", audioAsset);
        item.put("videoId", videoId);
        return item;
    }

    static Song fromJson(JSONObject item) {
        if (item == null) return null;
        String id = item.optString("id", "");
        String title = item.optString("title", "");
        String artist = item.optString("artist", "");
        String video = item.optString("videoId", "");
        if (id.isEmpty() || title.isEmpty()) return null;
        if (!video.isEmpty()) {
            if (!YouTubeMusicClient.isVideoId(video) || !id.equals("yt:" + video)) return null;
            return new Song(id, title, artist, item.optString("album", ""),
                    YouTubeMusicClient.safeThumbnail(item.optString("artwork", "")), "", video);
        }
        // A persisted local record is resolved against the immutable catalog, never trusted as a file path.
        return Catalog.find(id);
    }
}
