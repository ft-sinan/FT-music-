package com.ftmusic.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A user-initiated check of public releases. Installation is always handled by Android. */
final class GithubUpdates {
    static final String RELEASES_PAGE = "https://github.com/ft-sinan/FT-music-/releases";
    private static final String RELEASES_API = "https://api.github.com/repos/ft-sinan/FT-music-/releases?per_page=30";
    private static final Pattern VERSION = Pattern.compile("(?:v)?(0|[1-9][0-9]{0,3})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})");
    private static final int MAX_RESPONSE = 2 * 1024 * 1024;

    static final class Update {
        final String tag, version, assetName;
        final int versionCode;
        final boolean prerelease;
        Update(String tag, String version, int versionCode, boolean prerelease) {
            this.tag = tag; this.version = version; this.versionCode = versionCode;
            this.prerelease = prerelease; this.assetName = "FT-Music-" + tag + ".apk";
        }
        String releaseUrl() { return RELEASES_PAGE + "/tag/" + tag; }
    }
    /** Matches the versionCode formula in app/build.gradle.kts. */
    static int versionCode(String version) {
        if (version == null) return -1;
        Matcher matcher = VERSION.matcher(version);
        if (!matcher.matches()) return -1;
        try {
            int major = Integer.parseInt(matcher.group(1)), minor = Integer.parseInt(matcher.group(2)), patch = Integer.parseInt(matcher.group(3));
            if (major > 2099 || minor > 999 || patch > 999) return -1;
            int code = major * 1000000 + minor * 1000 + patch;
            return code > 0 ? code : -1;
        } catch (NumberFormatException error) { return -1; }
    }
    static Update findNewer(JSONArray releases, String currentVersion) throws IOException {
        int current = versionCode(currentVersion);
        if (current < 0) throw new IOException("This app version cannot be compared. Open GitHub Releases to check manually.");
        Update best = null;
        for (int i = 0; i < releases.length() && i < 30; i++) {
            JSONObject release = releases.optJSONObject(i);
            if (release == null || release.optBoolean("draft", true)) continue;
            String tag = release.optString("tag_name", "");
            // Release tags must start with v; never trust a URL provided by the response.
            if (!tag.startsWith("v")) continue;
            int code = versionCode(tag);
            if (code <= current || (best != null && code <= best.versionCode)) continue;
            JSONArray assets = release.optJSONArray("assets");
            String expectedName = "FT-Music-" + tag + ".apk";
            boolean available = false;
            if (assets != null) for (int j = 0; j < assets.length() && j < 100; j++) {
                JSONObject asset = assets.optJSONObject(j);
                if (asset != null && expectedName.equals(asset.optString("name", "")) && "uploaded".equals(asset.optString("state", ""))) { available = true; break; }
            }
            if (available) best = new Update(tag, tag.substring(1), code, release.optBoolean("prerelease", false));
        }
        return best;
    }
    static Update check(String currentVersion) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(RELEASES_API).openConnection();
            connection.setConnectTimeout(12000); connection.setReadTimeout(15000); connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "FT-Music-Android");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("GitHub update check failed (HTTP " + status + "). You can open Releases manually.");
            try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count, total = 0;
                while ((count = stream.read(buffer)) != -1) {
                    total += count; if (total > MAX_RESPONSE) throw new IOException("GitHub returned too much release data.");
                    output.write(buffer, 0, count);
                }
                return findNewer(new JSONArray(new String(output.toByteArray(), StandardCharsets.UTF_8)), currentVersion);
            }
        } catch (JSONException error) { throw new IOException("GitHub returned an invalid release list.", error); }
        finally { if (connection != null) connection.disconnect(); }
    }
    private GithubUpdates() { }
}
