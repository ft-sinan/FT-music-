package com.ftmusic.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.io.IOException;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native background playback for the bundled catalog and best-effort direct streams. */
public final class PlaybackService extends Service {
    interface Listener { void onPlaybackChanged(); }
    final class LocalBinder extends Binder { PlaybackService service() { return PlaybackService.this; } }

    private static final String CHANNEL = "ft_playback";
    private static final int NOTIFICATION = 7;
    private static final String ACTION_KEEP = "com.ftmusic.app.KEEP";
    private static final String ACTION_TOGGLE = "com.ftmusic.app.TOGGLE";
    private static final String ACTION_NEXT = "com.ftmusic.app.NEXT";
    private static final String ACTION_PREVIOUS = "com.ftmusic.app.PREVIOUS";
    private static final String ACTION_STOP = "com.ftmusic.app.STOP";
    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final YouTubeMusicClient youtube = new YouTubeMusicClient();
    private final PlayQueue queue = new PlayQueue();
    private MediaPlayer mediaPlayer;
    private MediaSession mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private Listener listener;
    private boolean prepared;
    private boolean wantsPlay;
    private boolean playing;
    private boolean buffering;
    private boolean foregroundStarted;
    private boolean resumeAfterFocus;
    private int repeatMode; // 0 off, 1 all, 2 one
    private int generation;
    private String error = "";

    private final BroadcastReceiver becomingNoisy = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) pause(); }
    };

    @Override public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                    @Override public void onAudioFocusChange(int change) {
                        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                            resumeAfterFocus = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && playing;
                            pauseInternal(false);
                        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                            if (mediaPlayer != null && prepared) mediaPlayer.setVolume(.2f, .2f);
                        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
                            if (mediaPlayer != null && prepared) mediaPlayer.setVolume(1f, 1f);
                            if (resumeAfterFocus) { resumeAfterFocus = false; resume(); }
                        }
                    }
                }, main).build();
        NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "Music playback", NotificationManager.IMPORTANCE_LOW));
        mediaSession = new MediaSession(this, "FT Music");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        Intent sessionActivity = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        mediaSession.setSessionActivity(PendingIntent.getActivity(this, 6, sessionActivity, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { previous(); }
            @Override public void onSeekTo(long position) { seek((int) Math.min(Integer.MAX_VALUE, position)); }
            @Override public void onStop() { stopPlayback(); }
        });
        mediaSession.setActive(true);
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(becomingNoisy, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(becomingNoisy, filter);
        publishSession();
    }

    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_TOGGLE.equals(action)) toggle();
        else if (ACTION_NEXT.equals(action)) next();
        else if (ACTION_PREVIOUS.equals(action)) previous();
        else if (ACTION_STOP.equals(action)) stopPlayback();
        return START_NOT_STICKY;
    }

    void setListener(Listener listener) { this.listener = listener; }
    Song current() { return queue.current(); }
    List<Song> queue() { return queue.items(); }
    int queueIndex() { return queue.index(); }
    boolean isPlaying() { return playing; }
    boolean isBuffering() { return buffering; }
    int repeatMode() { return repeatMode; }
    void cycleRepeat() { repeatMode = (repeatMode + 1) % 3; publish(); }
    void shuffleUpcoming() { queue.shuffleUpcoming(); publish(); }
    String takeError() { String value = error; error = ""; return value; }
    int position() { try { return mediaPlayer != null && prepared ? mediaPlayer.getCurrentPosition() : 0; } catch (IllegalStateException ignored) { return 0; } }
    int duration() { try { return mediaPlayer != null && prepared ? Math.max(0, mediaPlayer.getDuration()) : 0; } catch (IllegalStateException ignored) { return 0; } }

    void play(List<Song> songs, int selected) { if (songs == null || songs.isEmpty()) return; queue.replace(songs, selected); startCurrent(); }
    void playQueueIndex(int index) { if (queue.select(index)) startCurrent(); }
    void addToQueue(Song song) { queue.add(song); publish(); }

    private void startCurrent() {
        final Song song = current();
        if (song == null) return;
        // Record tracks started from the UI, notifications, and automatic queue advancement.
        new LibraryStore(this).record(song);
        generation++;
        final int request = generation;
        releasePlayer();
        error = "";
        wantsPlay = true;
        playing = false;
        buffering = true;
        try {
            // Called after an explicit user action; promote before resolving a network stream.
            if (!foregroundStarted) {
                startForegroundService(new Intent(this, PlaybackService.class).setAction(ACTION_KEEP));
                startForeground(NOTIFICATION, notification());
                foregroundStarted = true;
            }
        } catch (RuntimeException problem) {
            fail("Android could not start background playback. Try again while the app is open.");
            return;
        }
        publish();
        main.postDelayed(new Runnable() { @Override public void run() {
            if (request == generation && !prepared && buffering) fail("This track is taking too long to load. Check your connection and try again.");
        } }, 30000);
        File offline = OfflineDownloads.get(this).fileFor(song);
        if (offline != null) {
            prepare(song, null, offline, request);
        } else if (!song.isYouTube()) {
            prepare(song, null, null, request);
        } else {
            executor.execute(new Runnable() {
                @Override public void run() {
                    try {
                        final String url = youtube.resolveAudio(song.videoId);
                        main.post(new Runnable() { @Override public void run() { if (request == generation) prepare(song, url, null, request); } });
                    } catch (final IOException failure) {
                        main.post(new Runnable() { @Override public void run() { if (request == generation) fail(failure.getMessage()); } });
                    }
                }
            });
        }
    }

    private void prepare(Song song, String url, File offline, final int request) {
        if (request != generation) return;
        try {
            final MediaPlayer player = new MediaPlayer();
            mediaPlayer = player;
            player.setWakeMode(this, android.os.PowerManager.PARTIAL_WAKE_LOCK);
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer ready) {
                    if (request != generation || ready != mediaPlayer) return;
                    prepared = true;
                    buffering = false;
                    if (wantsPlay) {
                        if (audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                            ready.start(); playing = true;
                        } else { wantsPlay = false; error = "Audio focus is unavailable. Try playing again."; }
                    }
                    publish();
                }
            });
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer finished) {
                    if (finished != mediaPlayer) return;
                    if (repeatMode == 2) {
                        try { finished.seekTo(0); finished.start(); playing = true; publish(); }
                        catch (IllegalStateException failure) { fail("Could not repeat this track."); }
                    } else if (queue.next() || (repeatMode == 1 && queue.select(0))) startCurrent();
                    else { playing = false; wantsPlay = false; audioManager.abandonAudioFocusRequest(focusRequest); publish(); }
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer failed, int what, int extra) {
                    if (failed == mediaPlayer) fail(current().isYouTube()
                            ? "This YouTube stream could not be played. Open it in YouTube Music instead."
                            : "This track could not be played. Please try another one.");
                    return true;
                }
            });
            player.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                @Override public boolean onInfo(MediaPlayer source, int what, int extra) {
                    if (source != mediaPlayer) return false;
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) { buffering = true; publish(); }
                    else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) { buffering = false; publish(); }
                    return false;
                }
            });
            if (offline != null) {
                player.setDataSource(offline.getAbsolutePath());
            } else if (url == null) {
                try (AssetFileDescriptor asset = getAssets().openFd(song.audioAsset)) {
                    player.setDataSource(asset.getFileDescriptor(), asset.getStartOffset(), asset.getLength());
                }
            } else {
                if (!YouTubeMusicClient.isSafeAudioUrl(url)) throw new IOException("Invalid audio source.");
                Map<String, String> headers = new HashMap<String, String>();
                headers.put("User-Agent", YouTubeMusicClient.VISION_USER_AGENT);
                player.setDataSource(this, Uri.parse(url), headers);
            }
            player.prepareAsync();
        } catch (IOException | IllegalArgumentException | SecurityException | IllegalStateException failure) {
            fail(song.isYouTube() ? "This YouTube stream could not be loaded. Open it in YouTube Music instead." : "This track could not be loaded.");
        }
    }

    void toggle() { if (playing || (buffering && wantsPlay)) pause(); else resume(); }
    void resume() {
        if (current() == null) return;
        wantsPlay = true;
        if (mediaPlayer == null) { startCurrent(); return; }
        if (!prepared) { publish(); return; }
        try {
            if (audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                mediaPlayer.start(); playing = true; buffering = false; publish();
            } else { wantsPlay = false; fail("Audio focus is unavailable. Try playing again."); }
        } catch (IllegalStateException failure) { fail("Playback could not be resumed."); }
    }
    void pause() { resumeAfterFocus = false; pauseInternal(true); }
    private void pauseInternal(boolean abandonFocus) {
        wantsPlay = false;
        try { if (mediaPlayer != null && prepared && playing) mediaPlayer.pause(); } catch (IllegalStateException ignored) { }
        playing = false;
        if (abandonFocus) audioManager.abandonAudioFocusRequest(focusRequest);
        publish();
    }
    void next() { if (queue.next() || (repeatMode == 1 && queue.select(0))) startCurrent(); }
    void previous() { if (position() > 3000) seek(0); else if (queue.previous()) startCurrent(); else seek(0); }
    void seek(int position) {
        try { if (mediaPlayer != null && prepared) { mediaPlayer.seekTo(Math.max(0, Math.min(position, duration()))); publish(); } }
        catch (IllegalStateException ignored) { }
    }
    private void fail(String message) {
        generation++; // Cancel any in-flight stream resolution or delayed preparation callback.
        releasePlayer();
        wantsPlay = false; playing = false; buffering = false;
        audioManager.abandonAudioFocusRequest(focusRequest);
        error = message == null || message.isEmpty() ? "Playback is unavailable. Try again." : message;
        publish();
    }
    private void stopPlayback() {
        generation++;
        releasePlayer();
        wantsPlay = false; playing = false; buffering = false;
        audioManager.abandonAudioFocusRequest(focusRequest);
        stopForeground(true);
        foregroundStarted = false;
        stopSelf();
        publishSession();
        notifyListener();
    }
    private void releasePlayer() {
        prepared = false;
        if (mediaPlayer != null) {
            mediaPlayer.reset(); mediaPlayer.release(); mediaPlayer = null;
        }
    }

    private PendingIntent action(String action, int request) {
        Intent intent = new Intent(this, PlaybackService.class).setAction(action);
        return PendingIntent.getService(this, request, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private Notification notification() {
        Intent intent = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(current().title).setContentText(buffering ? "Buffering…" : current().artist)
                .setContentIntent(open).setDeleteIntent(action(ACTION_STOP, 5)).setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(playing || buffering).setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_media_previous, "Previous", action(ACTION_PREVIOUS, 2))
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, playing ? "Pause" : "Play", action(ACTION_TOGGLE, 3))
                .addAction(android.R.drawable.ic_media_next, "Next", action(ACTION_NEXT, 4))
                .setStyle(new Notification.MediaStyle().setMediaSession(mediaSession.getSessionToken()).setShowActionsInCompactView(0, 1, 2))
                .build();
    }
    private void publishSession() {
        Song song = current();
        if (song == null) {
            mediaSession.setActive(false);
            mediaSession.setMetadata(null);
            mediaSession.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_NONE, 0, 0f).build());
            return;
        }
        mediaSession.setActive(true);
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE |
                PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP;
        int state = buffering ? PlaybackState.STATE_BUFFERING : playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        mediaSession.setPlaybackState(new PlaybackState.Builder().setActions(actions).setState(state, position(), playing ? 1f : 0f).build());
        mediaSession.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_MEDIA_ID, song.id).putString(MediaMetadata.METADATA_KEY_TITLE, song.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, song.artist).putString(MediaMetadata.METADATA_KEY_ALBUM, song.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration()).build());
    }
    private void publish() {
        publishSession();
        if (foregroundStarted) ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION, notification());
        notifyListener();
    }
    private void notifyListener() { if (listener != null) listener.onPlaybackChanged(); }

    @Override public void onDestroy() {
        generation++;
        listener = null;
        executor.shutdownNow();
        unregisterReceiver(becomingNoisy);
        releasePlayer();
        audioManager.abandonAudioFocusRequest(focusRequest);
        mediaSession.setActive(false);
        mediaSession.release();
        super.onDestroy();
    }
}
