package com.ftmusic.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** Optional LRCLIB lookup. Only the song title and artist are sent to the provider. */
final class LyricsClient {
    static final class Result {
        final String lrc, plain, note;
        Result(String lrc, String plain, String note) { this.lrc = lrc; this.plain = plain; this.note = note; }
    }
    static Result lookup(Song song, int durationMillis) throws IOException {
        if (song.title.equals("YouTube video") || song.artist.equals("Opened from a link")) return new Result("", "", "Search needs a song title and artist. Import an LRC file instead.");
        HttpURLConnection connection = null;
        try {
            String endpoint = "https://lrclib.net/api/search?track_name=" + URLEncoder.encode(song.title, "UTF-8") + "&artist_name=" + URLEncoder.encode(song.artist, "UTF-8");
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setConnectTimeout(10000); connection.setReadTimeout(12000); connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "FTMusic/0.2 (Android; synced lyrics)");
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("Lyrics lookup is unavailable (HTTP " + status + ").");
            try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count, total = 0;
                while ((count = stream.read(buffer)) != -1) { total += count; if (total > 1024 * 1024) throw new IOException("Lyrics response was too large."); output.write(buffer, 0, count); }
                return choose(new JSONArray(new String(output.toByteArray(), StandardCharsets.UTF_8)), song, durationMillis);
            }
        } catch (JSONException error) { throw new IOException("The lyrics provider returned an invalid response.", error); }
        finally { if (connection != null) connection.disconnect(); }
    }
    static Result choose(JSONArray items, Song song, int durationMillis) {
        JSONObject best = null; int bestScore = Integer.MIN_VALUE;
        String title = normalize(song.title), artist = normalize(song.artist);
        if (title.isEmpty() || artist.isEmpty()) return new Result("", "", "Search needs a song title and artist. Import an LRC file instead.");
        for (int i = 0; i < items.length() && i < 50; i++) {
            JSONObject item = items.optJSONObject(i); if (item == null) continue;
            String candidateTitle = normalize(item.optString("trackName", ""));
            String candidateArtist = normalize(item.optString("artistName", ""));
            if (candidateTitle.isEmpty() || candidateArtist.isEmpty()) continue;
            int score = candidateTitle.equals(title) ? 8 : candidateTitle.contains(title) || title.contains(candidateTitle) ? 3 : -4;
            score += candidateArtist.equals(artist) ? 8 : candidateArtist.contains(artist) || artist.contains(candidateArtist) ? 3 : -4;
            if (durationMillis > 0 && item.optDouble("duration", 0) > 0) {
                double difference = Math.abs(item.optDouble("duration", 0) - durationMillis / 1000.0);
                score += difference <= 3 ? 3 : difference > 15 ? -5 : 0;
            }
            if (score > bestScore) { bestScore = score; best = item; }
        }
        if (best == null || bestScore < 6) return new Result("", "", "No matching lyrics found. You can import an LRC file.");
        if (best.optBoolean("instrumental", false)) return new Result("", "", "This track is marked instrumental.");
        String synced = best.isNull("syncedLyrics") ? "" : best.optString("syncedLyrics", "");
        String plain = best.isNull("plainLyrics") ? "" : best.optString("plainLyrics", "");
        List<LrcParser.Line> parsed = LrcParser.parse(synced);
        if (!parsed.isEmpty()) return new Result(synced, "", "Line-synced lyrics from LRCLIB");
        if (!plain.isEmpty() && plain.length() <= 1024 * 1024) return new Result("", plain, "Unsynced lyrics from LRCLIB");
        return new Result("", "", "No lyrics found. You can import an LRC file.");
    }
    private static String normalize(String text) { return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", ""); }
    private LyricsClient() { }
}
