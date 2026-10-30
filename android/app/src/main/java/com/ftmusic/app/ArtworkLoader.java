package com.ftmusic.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ArtworkLoader {
    interface Listener { void onArtwork(Bitmap bitmap); }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService workers = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    ArtworkLoader(Context context) { this.context = context.getApplicationContext(); }
    void show(final ImageView view, final String path) { show(view, path, null); }
    void show(final ImageView view, final String path, final Listener listener) {
        view.setTag(path);
        view.setImageDrawable(null);
        if (path == null || path.isEmpty()) return;
        Bitmap cached = cache.get(path);
        if (cached != null) { view.setImageBitmap(cached); if (listener != null) listener.onArtwork(cached); return; }
        workers.execute(new Runnable() {
            @Override public void run() {
                Bitmap loaded = null;
                try {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = 2;
                    if (path.startsWith("images/")) {
                        try (InputStream stream = context.getAssets().open(path)) { loaded = BitmapFactory.decodeStream(stream, null, options); }
                    } else if (!YouTubeMusicClient.safeThumbnail(path).isEmpty()) {
                        HttpURLConnection connection = (HttpURLConnection) new URL(path).openConnection();
                        try {
                            connection.setConnectTimeout(8000); connection.setReadTimeout(8000); connection.setInstanceFollowRedirects(false);
                            if (connection.getResponseCode() == 200) try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                                byte[] buffer = new byte[8192]; int count, total = 0;
                                while ((count = stream.read(buffer)) != -1) { total += count; if (total > 2 * 1024 * 1024) break; bytes.write(buffer, 0, count); }
                                if (total <= 2 * 1024 * 1024) loaded = BitmapFactory.decodeByteArray(bytes.toByteArray(), 0, bytes.size(), options);
                            }
                        } finally { connection.disconnect(); }
                    }
                } catch (Exception ignored) { }
                final Bitmap result = loaded;
                if (result != null) {
                    cache.put(path, result);
                    main.post(new Runnable() { @Override public void run() {
                        if (path.equals(view.getTag())) { view.setImageBitmap(result); if (listener != null) listener.onArtwork(result); }
                    } });
                }
            }
        });
    }
    void close() { workers.shutdownNow(); cache.evictAll(); }
}
