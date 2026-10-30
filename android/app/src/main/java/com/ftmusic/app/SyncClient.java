package com.ftmusic.app;

import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** HTTPS only, no redirects, no certificate bypass. Password and token are never persisted. */
final class SyncClient {
    static final class RevisionConflict extends IOException { RevisionConflict() { super("Library changed on another device."); } }
    private volatile String server = "", username = "", accountId = "", token = "";
    static String normalizeServer(String input) throws IOException {
        try {
            URI uri = new URI(input.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/"))
                    || uri.getPort() == 0 || uri.getPort() > 65535 || input.length() > 512) throw new Exception();
            return new URI("https", null, uri.getHost().toLowerCase(Locale.ROOT), uri.getPort(), "", null, null).toASCIIString();
        } catch (Exception error) { throw new IOException("Enter an HTTPS server URL without a path, username, or query."); }
    }
    void signIn(String address, String name, String password, boolean register) throws IOException {
        String base = normalizeServer(address), user = name.trim().toLowerCase(Locale.ROOT);
        if ((!user.matches("[a-z0-9_-]{3,32}") || user.equals("__proto__"))) throw new IOException("Username must contain 3–32 letters, numbers, underscores or hyphens.");
        if (password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 128) throw new IOException("Use a password of at least 12 characters and at most 128 UTF-8 bytes.");
        try {
            JSONObject response = request(base, register ? "/v1/register" : "/v1/login", "POST", new JSONObject().put("username", user).put("password", password), "");
            String issued = response.optString("token", "");
            String account = response.optString("accountId", "");
            if (!issued.matches("[A-Za-z0-9_-]{43}") || !account.matches("[A-Za-z0-9_-]{22}")) throw new IOException("The server returned an invalid sign-in response.");
            server = base; username = user; accountId = account; token = issued;
        } catch (JSONException error) { throw new IOException("Could not encode sign-in request.", error); }
    }
    boolean signedIn() { return !token.isEmpty(); }
    String identity() { return server + "\n" + username + "\n" + accountId; }
    String username() { return username; }
    String server() { return server; }
    void signOut() {
        String previousToken = token, previousServer = server; token = ""; server = ""; username = ""; accountId = "";
        if (!previousToken.isEmpty()) try { request(previousServer, "/v1/logout", "POST", null, previousToken); } catch (IOException ignored) { }
    }
    SyncLibrary sync(SyncLibrary local, SyncLibrary base) throws IOException {
        if (!signedIn()) throw new IOException("Please sign in first.");
        for (int attempt = 0; attempt < 3; attempt++) {
            JSONObject current = request(server, "/v1/library", "GET", null, token);
            long revision = current.optLong("revision", -1);
            if (revision < 0) throw new IOException("The server returned an invalid revision.");
            SyncLibrary remote = SyncLibrary.fromJson(current.optJSONObject("library"));
            SyncLibrary merged = SyncLibrary.merge(base, local, remote);
            // Validate the resulting size/schema before upload, instead of truncating a library.
            try { merged = SyncLibrary.fromJson(merged.toJson()); } catch (JSONException error) { throw new IOException("Could not encode the library.", error); }
            if (SyncLibrary.same(merged, remote)) return merged;
            try {
                request(server, "/v1/library", "PUT", new JSONObject().put("revision", revision).put("library", merged.toJson()), token);
                return merged;
            } catch (RevisionConflict retry) {
                if (attempt == 2) throw new IOException("The library kept changing on another device. Try syncing again.");
            } catch (JSONException error) { throw new IOException("Could not encode the library.", error); }
        }
        throw new IOException("Could not sync the library.");
    }
    private static JSONObject request(String base, String path, String method, JSONObject body, String token) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(base + path).openConnection();
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(12000); connection.setReadTimeout(18000); connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json"); connection.setRequestProperty("Cache-Control", "no-store");
            if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                if (bytes.length > 2 * 1024 * 1024) throw new IOException("Your library is too large to sync.");
                connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json; charset=utf-8"); connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            InputStream source = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            String text = "";
            if (source != null) try (InputStream input = source; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int read, total = 0;
                while ((read = input.read(buffer)) != -1) { total += read; if (total > 2 * 1024 * 1024) throw new IOException("The sync response was too large."); output.write(buffer, 0, read); }
                text = new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
            JSONObject response;
            try { response = new JSONObject(text); } catch (JSONException error) { throw new IOException("The sync server returned an invalid response."); }
            if (status == 409 && path.equals("/v1/library")) throw new RevisionConflict();
            if (status < 200 || status >= 300) throw new IOException(response.optString("error", "Sync server error (HTTP " + status + ")."));
            return response;
        } finally { if (connection != null) connection.disconnect(); }
    }
}
