package com.ftmusic.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** App-private, bounded best-effort downloads. Jobs can be interrupted by Android process death. */
final class OfflineDownloads {
    interface Listener { void onDownloadsChanged(String message); }
    private static final long MAX_BYTES = 200L * 1024 * 1024;
    private static OfflineDownloads instance;
    static synchronized OfflineDownloads get(Context context) {
        if (instance == null) instance = new OfflineDownloads(context.getApplicationContext());
        return instance;
    }
    private final File directory;
    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> pending = new HashSet<String>();
    private Listener listener;
    private OfflineDownloads(Context context) {
        directory = new File(context.getFilesDir(), "offline-audio");
        prefs = context.getSharedPreferences("ft_music_downloads", Context.MODE_PRIVATE);
        if (!directory.exists()) directory.mkdirs();
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) if (file.getName().endsWith(".part")) file.delete();
    }
    synchronized void setListener(Listener value) { listener = value; }
    private void notifyChange(final String message) {
        main.post(new Runnable() { @Override public void run() {
            Listener observer; synchronized (OfflineDownloads.this) { observer = listener; }
            if (observer != null) observer.onDownloadsChanged(message);
        } });
    }
    synchronized boolean isPending(Song song) { return pending.contains(song.id); }
    synchronized File fileFor(Song song) {
        if (song == null || !song.isYouTube() || !YouTubeMusicClient.isVideoId(song.videoId)) return null;
        File file = new File(directory, song.videoId + ".m4a");
        // A file is only considered complete if it is recorded in the manifest.
        if (!file.isFile() || file.length() == 0) return null;
        for (Song saved : list()) if (saved.id.equals(song.id)) return file;
        return null;
    }
    synchronized List<Song> list() {
        List<Song> result = new ArrayList<Song>();
        try {
            JSONArray array = new JSONArray(prefs.getString("songs", "[]"));
            for (int i = 0; i < array.length() && i < 500; i++) {
                Song song = Song.fromJson(array.optJSONObject(i));
                if (song == null || !song.isYouTube()) continue;
                File file = new File(directory, song.videoId + ".m4a");
                if (file.isFile() && file.length() > 0) result.add(song);
            }
        } catch (JSONException ignored) { }
        return result;
    }
    synchronized boolean remove(Song song) {
        File file = fileFor(song); if (file == null || !file.delete()) return false;
        List<Song> saved = list();
        for (int i = saved.size() - 1; i >= 0; i--) if (saved.get(i).id.equals(song.id)) saved.remove(i);
        try { write(saved); } catch (IOException ignored) { }
        notifyChange("Download removed"); return true;
    }
    synchronized boolean enqueue(final Song song) {
        if (song == null || !song.isYouTube() || !YouTubeMusicClient.isVideoId(song.videoId) || pending.contains(song.id) || fileFor(song) != null || pending.size() >= 10 || list().size() >= 500) return false;
        pending.add(song.id); notifyChange("Download queued. Keep FT Music open until it finishes.");
        worker.execute(new Runnable() { @Override public void run() {
            String message;
            try { download(song); message = "Downloaded “" + song.title + "”"; }
            catch (IOException | RuntimeException failure) { message = failure.getMessage() == null ? "Download failed. Try again." : "Download failed: " + failure.getMessage(); }
            synchronized (OfflineDownloads.this) { pending.remove(song.id); }
            notifyChange(message);
        } });
        return true;
    }
    private void download(Song song) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Could not create private storage.");
        String address = new YouTubeMusicClient().resolveAudio(song.videoId);
        File part = new File(directory, song.videoId + ".part"), target = new File(directory, song.videoId + ".m4a");
        HttpURLConnection connection = null;
        try {
            for (int redirects = 0; ; redirects++) {
                if (!YouTubeMusicClient.isSafeAudioUrl(address)) throw new IOException("The audio source was not allowed.");
                connection = (HttpURLConnection) new URL(address).openConnection();
                connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(12000); connection.setReadTimeout(20000);
                connection.setRequestProperty("User-Agent", YouTubeMusicClient.VISION_USER_AGENT);
                connection.setRequestProperty("Accept-Encoding", "identity");
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || redirects >= 3) throw new IOException("Too many audio redirects.");
                    address = new URL(new URL(address), location).toString(); connection.disconnect(); connection = null; continue;
                }
                if (status != 200 || connection.getHeaderField("Content-Range") != null) throw new IOException("The source did not allow a complete download (HTTP " + status + ").");
                long length = connection.getContentLengthLong();
                if (length <= 0 || length > MAX_BYTES) throw new IOException("The audio size is unknown or exceeds 200 MB.");
                String encoding = connection.getHeaderField("Content-Encoding");
                if (encoding != null && !encoding.equalsIgnoreCase("identity")) throw new IOException("Unsupported audio encoding.");
                if (directory.getUsableSpace() < length + 2 * 1024 * 1024) throw new IOException("Not enough device storage.");
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(part)) {
                    byte[] buffer = new byte[32768]; long total = 0; int read;
                    while ((read = input.read(buffer)) != -1) {
                        total += read; if (total > length || total > MAX_BYTES) throw new IOException("Audio response exceeded its expected size.");
                        output.write(buffer, 0, read);
                    }
                    if (total != length) throw new IOException("The download was incomplete.");
                    output.getFD().sync();
                }
                break;
            }
            validateAudio(part);
            if (!part.renameTo(target)) throw new IOException("Could not save the audio file.");
            synchronized (this) {
                List<Song> songs = list(); songs.add(0, song);
                try { write(songs); } catch (IOException problem) { target.delete(); throw problem; }
            }
        } finally { if (connection != null) connection.disconnect(); if (part.exists()) part.delete(); }
    }
    private static void validateAudio(File file) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            if (extractor.getDrmInitData() != null) throw new IOException("Protected streams are not supported.");
            boolean audio = false;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i); String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) audio = true;
            }
            if (!audio) throw new IOException("The download was not a compatible audio file.");
        } catch (RuntimeException problem) { throw new IOException("The downloaded audio could not be validated.", problem); }
        finally { extractor.release(); }
    }
    private void write(List<Song> songs) throws IOException {
        JSONArray items = new JSONArray();
        try { for (Song song : songs) items.put(song.toJson()); }
        catch (JSONException error) { throw new IOException("Could not save the download list.", error); }
        if (!prefs.edit().putString("songs", items.toString()).commit()) throw new IOException("Could not save the download list.");
    }
}
