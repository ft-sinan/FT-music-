package com.ftmusic.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Experimental anonymous client. YouTube Music's private endpoints are undocumented and can
 * change or refuse requests without notice. No cookies, account credentials or keys are used.
 */
final class YouTubeMusicClient {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final String MUSIC_ORIGIN = "https://music.youtube.com";
    private static final String MUSIC_VERSION = "1.20260928.13.00";
    private static final String VISION_VERSION = "1.02";
    static final String VISION_USER_AGENT = "com.google.visionos.youtube/1.02 (RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X)";
    private static final int MAX_RESPONSE = 3 * 1024 * 1024;
    private volatile String visitor = "";

    static boolean isVideoId(String value) { return value != null && ID.matcher(value).matches(); }

    static String safeThumbnail(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean allowed = host.equals("i.ytimg.com") || host.equals("yt3.ggpht.com") || host.equals("lh3.googleusercontent.com");
            if ("https".equalsIgnoreCase(uri.getScheme()) && allowed && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)) return value;
        } catch (IllegalArgumentException ignored) { }
        return "";
    }

    static boolean isSafeAudioUrl(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return "https".equalsIgnoreCase(uri.getScheme()) && (host.equals("googlevideo.com") || host.endsWith(".googlevideo.com"))
                    && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (IllegalArgumentException ignored) { return false; }
    }

    static String videoIdFromLink(String input) {
        String value = input == null ? "" : input.trim();
        if (!value.matches("(?i)^https?://.*")) {
            if (value.matches("(?i)^(?:(?:www|m|music)\\.)?(?:youtube\\.com|youtu\\.be)/.*")) value = "https://" + value;
            else return null;
        }
        try {
            URI uri = URI.create(value);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) return null;
            if (uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443)) return null;
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath();
            String id = null;
            if (host.equals("youtu.be") || host.equals("www.youtu.be")) {
                String[] parts = path.split("/");
                if (parts.length > 1) id = parts[1];
            } else if (host.equals("youtube.com") || host.equals("www.youtube.com") || host.equals("m.youtube.com") || host.equals("music.youtube.com")) {
                String[] parts = path.split("/");
                if (parts.length > 2 && (parts[1].equals("shorts") || parts[1].equals("embed") || parts[1].equals("live"))) id = parts[2];
                else if (path.equals("/watch") && uri.getRawQuery() != null) {
                    for (String part : uri.getRawQuery().split("&")) {
                        String[] pair = part.split("=", 2);
                        if (pair.length == 2 && pair[0].equals("v")) id = URLDecoder.decode(pair[1], "UTF-8");
                    }
                }
            }
            return isVideoId(id) ? id : null;
        } catch (Exception ignored) { return null; }
    }

    static Song fromLink(String videoId) {
        return new Song("yt:" + videoId, "YouTube video", "Opened from a link", "YouTube Music",
                "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg", "", videoId);
    }

    List<Song> search(String query) throws IOException {
        if (query == null || query.trim().isEmpty()) return new ArrayList<Song>();
        String id = videoIdFromLink(query);
        if (id != null) { List<Song> one = new ArrayList<Song>(); one.add(fromLink(id)); return one; }
        try {
            JSONObject body = new JSONObject();
            body.put("context", new JSONObject().put("client", new JSONObject()
                    .put("clientName", "WEB_REMIX").put("clientVersion", MUSIC_VERSION)
                    .put("hl", Locale.getDefault().getLanguage().isEmpty() ? "en" : Locale.getDefault().getLanguage())
                    .put("gl", country())));
            body.put("query", query.trim());
            // Ask the music search endpoint for its songs filter, rather than video-only search.
            body.put("params", "EgWKAQIIAWoMEA4QChADEAQQCRAF");
            JSONObject response = post(MUSIC_ORIGIN + "/youtubei/v1/search?prettyPrint=false", body,
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36", MUSIC_ORIGIN, "67", MUSIC_VERSION, "");
            return parseSearch(response);
        } catch (JSONException error) {
            throw new IOException("YouTube Music returned an unexpected response.", error);
        }
    }

    private static String country() {
        String country = Locale.getDefault().getCountry().toUpperCase(Locale.ROOT);
        return country.matches("[A-Z]{2}") ? country : "IN";
    }

    static List<Song> parseSearch(JSONObject root) {
        Map<String, Song> found = new LinkedHashMap<String, Song>();
        visitSearch(root, found, 0);
        return new ArrayList<Song>(found.values());
    }

    private static void visitSearch(Object node, Map<String, Song> found, int depth) {
        if (node == null || depth > 36 || found.size() >= 40) return;
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject renderer = object.optJSONObject("musicResponsiveListItemRenderer");
            if (renderer != null) {
                Song song = readSong(renderer);
                if (song != null && !found.containsKey(song.id)) found.put(song.id, song);
                return;
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) visitSearch(object.opt(keys.next()), found, depth + 1);
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length() && found.size() < 40; i++) visitSearch(array.opt(i), found, depth + 1);
        }
    }

    private static Song readSong(JSONObject renderer) {
        String id = "";
        JSONObject playlistData = renderer.optJSONObject("playlistItemData");
        if (playlistData != null) id = playlistData.optString("videoId", "");
        if (!isVideoId(id)) id = findVideoId(renderer, 0);
        if (!isVideoId(id)) return null;
        JSONArray columns = renderer.optJSONArray("flexColumns");
        String title = columnText(columns, 0);
        String artist = columnText(columns, 1);
        if (title.isEmpty()) return null;
        // The second column can include album and duration; show the first nonempty part as the artist.
        int separator = artist.indexOf(" • ");
        if (separator >= 0) artist = artist.substring(0, separator).trim();
        if (artist.isEmpty()) artist = "YouTube Music";
        String image = findThumbnail(renderer, 0);
        if (image.isEmpty()) image = "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg";
        return new Song("yt:" + id, title, artist, "YouTube Music", image, "", id);
    }

    private static String columnText(JSONArray columns, int index) {
        if (columns == null || index >= columns.length()) return "";
        JSONObject column = columns.optJSONObject(index);
        if (column == null) return "";
        JSONObject flex = column.optJSONObject("musicResponsiveListItemFlexColumnRenderer");
        if (flex == null) return "";
        JSONObject text = flex.optJSONObject("text");
        if (text == null) return "";
        String simple = text.optString("simpleText", "");
        if (!simple.isEmpty()) return simple;
        JSONArray runs = text.optJSONArray("runs");
        StringBuilder joined = new StringBuilder();
        if (runs != null) for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run != null) joined.append(run.optString("text", ""));
        }
        return joined.toString().trim();
    }

    private static String findVideoId(Object node, int depth) {
        if (depth > 20) return "";
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject watch = object.optJSONObject("watchEndpoint");
            if (watch != null && isVideoId(watch.optString("videoId", ""))) return watch.optString("videoId");
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) { String id = findVideoId(object.opt(keys.next()), depth + 1); if (!id.isEmpty()) return id; }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) { String id = findVideoId(array.opt(i), depth + 1); if (!id.isEmpty()) return id; }
        }
        return "";
    }

    private static String findThumbnail(Object node, int depth) {
        if (depth > 20) return "";
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONArray thumbs = object.optJSONArray("thumbnails");
            if (thumbs != null) for (int i = thumbs.length() - 1; i >= 0; i--) {
                JSONObject image = thumbs.optJSONObject(i);
                String safe = image == null ? "" : safeThumbnail(image.optString("url", ""));
                if (!safe.isEmpty()) return safe;
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) { String image = findThumbnail(object.opt(keys.next()), depth + 1); if (!image.isEmpty()) return image; }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) { String image = findThumbnail(array.opt(i), depth + 1); if (!image.isEmpty()) return image; }
        }
        return "";
    }

    /** Best-effort plain AAC stream only; encrypted/signature-ciphered streams are not handled. */
    String resolveAudio(String videoId) throws IOException {
        if (!isVideoId(videoId)) throw new IOException("Invalid video link.");
        try {
            if (visitor.isEmpty()) {
                JSONObject response = visionRequest("visitor_id", new JSONObject(), "");
                JSONObject context = response.optJSONObject("responseContext");
                visitor = context == null ? "" : context.optString("visitorData", "");
            }
            if (visitor.isEmpty()) throw new IOException("YouTube Music didn't provide a playback session.");
            JSONObject response = visionRequest("player", new JSONObject().put("videoId", videoId).put("contentCheckOk", true).put("racyCheckOk", true), visitor);
            JSONObject status = response.optJSONObject("playabilityStatus");
            if (status != null && !"OK".equals(status.optString("status", ""))) {
                throw new IOException("This track isn't available for anonymous playback. Open it in YouTube Music instead.");
            }
            return chooseAudio(response);
        } catch (JSONException error) {
            throw new IOException("YouTube Music returned an unexpected playback response.", error);
        }
    }

    static String chooseAudio(JSONObject response) throws IOException {
        JSONObject streaming = response.optJSONObject("streamingData");
        JSONArray formats = streaming == null ? null : streaming.optJSONArray("adaptiveFormats");
        String best = "";
        int bestRate = -1;
        if (formats != null) for (int i = 0; i < formats.length(); i++) {
            JSONObject format = formats.optJSONObject(i);
            if (format == null || !format.optString("mimeType", "").startsWith("audio/mp4")
                    || format.has("signatureCipher") || format.has("cipher") || format.has("drmFamilies")
                    || format.has("licenseInfos") || format.has("drmTrackType")) continue;
            String url = format.optString("url", "");
            int rate = format.optInt("bitrate", 0);
            if (isSafeAudioUrl(url) && rate > bestRate) { best = url; bestRate = rate; }
        }
        if (best.isEmpty()) throw new IOException("No compatible direct audio was returned. Open this track in YouTube Music instead.");
        return best;
    }

    private JSONObject visionRequest(String endpoint, JSONObject extras, String visitorData) throws IOException, JSONException {
        JSONObject client = new JSONObject().put("clientName", "VISIONOS").put("clientVersion", VISION_VERSION)
                .put("clientScreen", "WATCH").put("platform", "MOBILE").put("deviceMake", "Apple")
                .put("deviceModel", "RealityDevice14,1").put("osName", "visionOS").put("osVersion", "25.6.0.23O471")
                .put("hl", "en").put("gl", country()).put("utcOffsetMinutes", 0);
        if (!visitorData.isEmpty()) client.put("visitorData", visitorData);
        JSONObject body = new JSONObject().put("context", new JSONObject().put("client", client));
        Iterator<String> keys = extras.keys();
        while (keys.hasNext()) { String key = keys.next(); body.put(key, extras.opt(key)); }
        return post("https://www.youtube.com/youtubei/v1/" + endpoint + "?prettyPrint=false", body, VISION_USER_AGENT, "https://www.youtube.com", "101", VISION_VERSION, visitorData);
    }

    private static JSONObject post(String endpoint, JSONObject body, String userAgent, String origin, String clientName, String clientVersion, String visitorData) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("User-Agent", userAgent);
            connection.setRequestProperty("Origin", origin);
            connection.setRequestProperty("Referer", origin + "/");
            connection.setRequestProperty("X-YouTube-Client-Name", clientName);
            connection.setRequestProperty("X-YouTube-Client-Version", clientVersion);
            connection.setRequestProperty("X-Goog-Api-Format-Version", "2");
            if (!visitorData.isEmpty()) connection.setRequestProperty("X-Goog-Visitor-Id", visitorData);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream stream = connection.getOutputStream()) { stream.write(bytes); }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("YouTube Music is unavailable (HTTP " + code + "). Try again or open the song in YouTube Music.");
            try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read, total = 0;
                while ((read = stream.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_RESPONSE) throw new IOException("YouTube Music returned an unusually large response.");
                    output.write(buffer, 0, read);
                }
                return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally { connection.disconnect(); }
    }
}
