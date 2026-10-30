package com.ftmusic.app;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Imported LRC files are kept private to the app and identified by a hash, never a user file path. */
final class ImportedLyricsStore {
    private static final int MAX_BYTES = 1024 * 1024;
    private final File directory;
    ImportedLyricsStore(Context context) { directory = new File(context.getFilesDir(), "lyrics"); }
    private File fileFor(String songId) throws IOException {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(songId.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder(); for (byte b : hash) name.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return new File(directory, name + ".lrc");
        } catch (NoSuchAlgorithmException error) { throw new IOException("Could not identify lyric file.", error); }
    }
    String read(String songId) throws IOException {
        File file = fileFor(songId); if (!file.isFile()) return "";
        try (InputStream input = new FileInputStream(file)) { return readLimited(input); }
    }
    void save(String songId, InputStream input) throws IOException {
        String text = readLimited(input);
        if (LrcParser.parse(text).isEmpty()) throw new IOException("This file has no timed LRC lines.");
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Couldn't create the lyrics folder.");
        File target = fileFor(songId), temp = new File(directory, target.getName() + ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(temp)) { output.write(text.getBytes(StandardCharsets.UTF_8)); output.getFD().sync(); }
            if (!temp.renameTo(target)) throw new IOException("Couldn't save the LRC file.");
        } finally { if (temp.exists()) temp.delete(); }
    }
    private static String readLimited(InputStream input) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int read, total = 0;
            while ((read = input.read(buffer)) != -1) { total += read; if (total > MAX_BYTES) throw new IOException("LRC file is too large."); output.write(buffer, 0, read); }
            String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
            return text.startsWith("\ufeff") ? text.substring(1) : text;
        }
    }
}
