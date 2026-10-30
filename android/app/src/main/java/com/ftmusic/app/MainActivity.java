package com.ftmusic.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A native, phone-first listening experience, independent of the web implementation. */
public final class MainActivity extends Activity implements PlaybackService.Listener, OfflineDownloads.Listener {
    private static final int BG = Color.rgb(246, 244, 240);
    private static final int INK = Color.rgb(35, 33, 44);
    private static final int MUTED = Color.rgb(116, 113, 125);
    private static final int WHITE = Color.WHITE;
    private static final int LINE = Color.rgb(230, 226, 232);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService searchWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService syncWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService updateWorker = Executors.newSingleThreadExecutor();
    private boolean checkingUpdates;
    private final SyncClient syncClient = new SyncClient();
    private OfflineDownloads downloads;
    private LyricsDialog lyricsDialog;
    private String pendingLyricsSongId = "";
    private boolean syncBusy;
    private final YouTubeMusicClient youtube = new YouTubeMusicClient();
    private LibraryStore library;
    private ArtworkLoader artwork;
    private PlaybackService playback;
    private boolean bindingActive;
    private String tab = "home";
    private String librarySection = "liked";
    private String selectedPlaylist = "";
    private String searchQuery = "";
    private String searchError = "";
    private boolean searching;
    private boolean searchedOnline;
    private int searchGeneration;
    private List<Song> onlineResults = new ArrayList<Song>();
    private FrameLayout pageFrame;
    private LinearLayout appRoot;
    private LinearLayout nav;
    private TextView miniTitle, miniArtist;
    private ImageView miniCover;
    private IconView miniPlay;
    private Dialog playerDialog;
    private LinearLayout playerRoot;
    private TextView playerTitle, playerArtist, playerTime, playerDuration, playerSource;
    private ImageView playerCover;
    private IconView playerPlay, playerHeart;
    private SeekBar seekBar;
    private View playerOpenYouTube, repeatButton;
    private boolean seeking;
    private String displayedPlayerId = "";
    private LinearLayout searchResultContainer;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            playback = ((PlaybackService.LocalBinder) binder).service();
            playback.setListener(MainActivity.this);
            onPlaybackChanged();
            if (tab.equals("queue")) renderPage();
        }
        @Override public void onServiceDisconnected(ComponentName name) { playback = null; updateMini(); }
    };
    private final Runnable ticker = new Runnable() {
        @Override public void run() { updateProgress(); main.postDelayed(this, 750); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        library = new LibraryStore(this);
        downloads = OfflineDownloads.get(this);
        artwork = new ArtworkLoader(this);
        if (state != null) { tab = state.getString("tab", "home"); librarySection = state.getString("library", "liked"); searchQuery = state.getString("query", ""); pendingLyricsSongId = state.getString("lyricsImport", ""); }
        buildShell();
        renderPage();
    }
    @Override protected void onStart() {
        super.onStart();
        bindingActive = bindService(new Intent(this, PlaybackService.class), connection, Context.BIND_AUTO_CREATE);
        downloads.setListener(this);
        main.post(ticker);
    }
    @Override protected void onStop() {
        main.removeCallbacks(ticker);
        downloads.setListener(null);
        if (bindingActive) { if (playback != null) playback.setListener(null); unbindService(connection); bindingActive = false; playback = null; }
        super.onStop();
    }
    @Override protected void onDestroy() {
        searchGeneration++;
        searchWorker.shutdownNow();
        syncWorker.shutdownNow();
        updateWorker.shutdownNow();
        if (lyricsDialog != null) lyricsDialog.dismiss();
        artwork.close();
        if (playerDialog != null) playerDialog.dismiss();
        super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("tab", tab); out.putString("library", librarySection); out.putString("query", searchQuery); out.putString("lyricsImport", pendingLyricsSongId);
        super.onSaveInstanceState(out);
    }
    @Override public void onBackPressed() {
        if (playerDialog != null && playerDialog.isShowing()) { playerDialog.dismiss(); return; }
        if (!selectedPlaylist.isEmpty()) { selectedPlaylist = ""; renderPage(); return; }
        if (!tab.equals("home")) { showTab("home"); return; }
        super.onBackPressed();
    }

    private int dp(float size) { return Math.round(size * getResources().getDisplayMetrics().density); }
    private GradientDrawable shape(int color, float radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private GradientDrawable outline(int color, float radius, int stroke) { GradientDrawable d = shape(color, radius); d.setStroke(dp(1), stroke); return d; }
    private GradientDrawable gradient(int first, int second, float radius) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {first, second}); d.setCornerRadius(dp(radius)); return d;
    }
    private TextView text(String value, float sp, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextColor(color); t.setTextSize(sp);
        t.setFontFeatureSettings("kern"); if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private void addGap(LinearLayout container, int height) { View gap = new View(this); container.addView(gap, new LinearLayout.LayoutParams(1, dp(height))); }
    private void padding(View view, int left, int top, int right, int bottom) { view.setPadding(dp(left), dp(top), dp(right), dp(bottom)); }
    private ImageView cover(String source, int radius) {
        ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(shape(Color.rgb(221, 217, 225), radius)); image.setClipToOutline(true); artwork.show(image, source); return image;
    }
    private FrameLayout iconButton(String icon, String description, int color, int bg, int size, final Runnable action) {
        FrameLayout button = new FrameLayout(this); button.setContentDescription(description); button.setFocusable(true); button.setClickable(true);
        if (bg != Color.TRANSPARENT) button.setBackground(shape(bg, size / 2f));
        IconView image = new IconView(this, icon, color);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(size <= 36 ? 19 : 22), dp(size <= 36 ? 19 : 22), Gravity.CENTER);
        button.addView(image, params); button.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { action.run(); } });
        return button;
    }
    private TextView pill(String label, int fg, int background, final Runnable action) {
        TextView view = text(label, 13, fg, true); view.setGravity(Gravity.CENTER); padding(view, 15, 9, 15, 9); view.setBackground(shape(background, 40));
        view.setFocusable(true); view.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { action.run(); } }); return view;
    }

    private void buildShell() {
        appRoot = column(); appRoot.setBackgroundColor(BG);
        if (Build.VERSION.SDK_INT >= 35) {
            appRoot.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
                    return insets.consumeSystemWindowInsets();
                }
            });
        }
        pageFrame = new FrameLayout(this); appRoot.addView(pageFrame, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout.LayoutParams miniParams = new LinearLayout.LayoutParams(-1, dp(68)); miniParams.setMargins(dp(12), 0, dp(12), dp(4));
        appRoot.addView(buildMini(), miniParams);
        nav = row(); padding(nav, 8, 4, 8, 7); appRoot.addView(nav, new LinearLayout.LayoutParams(-1, dp(69)));
        setContentView(appRoot); renderNavigation();
    }

    private View buildMini() {
        LinearLayout outer = row(); padding(outer, 9, 7, 9, 7); outer.setBackground(outline(Color.rgb(255,255,255), 19, LINE));
        miniCover = cover(Catalog.SONGS.get(0).artwork, 9); outer.addView(miniCover, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout info = column(); padding(info, 10, 0, 0, 0);
        miniTitle = text("Blue Skies", 14, INK, true); miniTitle.setSingleLine(true); miniArtist = text("HoliznaCC0", 11, MUTED, false); miniArtist.setSingleLine(true);
        info.addView(miniTitle); info.addView(miniArtist); outer.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        FrameLayout play = iconButton("play", "Play", INK, Color.TRANSPARENT, 40, new Runnable() { @Override public void run() { if (playback != null) playback.toggle(); } });
        miniPlay = (IconView) play.getChildAt(0); outer.addView(play, new LinearLayout.LayoutParams(dp(40), dp(40)));
        outer.addView(iconButton("next", "Next track", INK, Color.TRANSPARENT, 40, new Runnable() { @Override public void run() { if (playback != null) playback.next(); } }), new LinearLayout.LayoutParams(dp(40), dp(40)));
        outer.setContentDescription("Open now playing"); outer.setFocusable(true);
        outer.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openPlayer(); } });
        return outer;
    }
    private void renderNavigation() {
        nav.removeAllViews();
        nav.addView(navItem("home", "Home", "home"), new LinearLayout.LayoutParams(0, -1, 1));
        nav.addView(navItem("search", "Search", "search"), new LinearLayout.LayoutParams(0, -1, 1));
        nav.addView(navItem("library", "Library", "library"), new LinearLayout.LayoutParams(0, -1, 1));
        nav.addView(navItem("queue", "Queue", "queue"), new LinearLayout.LayoutParams(0, -1, 1));
    }
    private View navItem(final String target, String label, String icon) {
        LinearLayout item = column(); item.setGravity(Gravity.CENTER); item.setContentDescription(label); item.setFocusable(true);
        boolean active = tab.equals(target); if (active) item.setBackground(shape(WHITE, 22));
        IconView image = new IconView(this, icon, active ? INK : MUTED); item.addView(image, new LinearLayout.LayoutParams(dp(21), dp(21)));
        TextView caption = text(label, 10, active ? INK : MUTED, active); caption.setGravity(Gravity.CENTER); item.addView(caption);
        item.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showTab(target); } }); return item;
    }
    private void showTab(String target) { tab = target; selectedPlaylist = ""; renderNavigation(); renderPage(); }
    private LinearLayout scrollingPage() {
        pageFrame.removeAllViews();
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout content = column(); padding(content, 20, 22, 20, 28); scroll.addView(content); pageFrame.addView(scroll);
        return content;
    }
    private void renderPage() {
        searchResultContainer = null;
        LinearLayout content = scrollingPage();
        if (tab.equals("home")) renderHome(content);
        else if (tab.equals("search")) renderSearch(content);
        else if (tab.equals("library")) renderLibrary(content);
        else renderQueue(content);
    }
    private void heading(LinearLayout content, String eyebrow, String title, String subtitle) {
        TextView tiny = text(eyebrow.toUpperCase(Locale.getDefault()), 10, MUTED, true); tiny.setLetterSpacing(.13f); content.addView(tiny);
        TextView h = text(title, 32, INK, false); h.setLetterSpacing(-.03f); content.addView(h);
        if (subtitle != null && !subtitle.isEmpty()) { TextView p = text(subtitle, 13, MUTED, false); padding(p, 0, 3, 0, 0); content.addView(p); }
    }
    private void sectionTitle(LinearLayout content, String title, String subtitle) {
        TextView h = text(title, 23, INK, false); h.setLetterSpacing(-.02f); content.addView(h);
        if (subtitle != null) { TextView p = text(subtitle, 12, MUTED, false); padding(p, 0, 4, 0, 0); content.addView(p); }
    }
    private String greeting() { int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY); return hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening"; }

    private void renderHome(LinearLayout content) {
        String date = new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Calendar.getInstance().getTime());
        heading(content, date, greeting(), "A little closer to the music you love.");
        addGap(content, 18);
        LinearLayout hero = column(); padding(hero, 22, 21, 22, 21); hero.setBackground(gradient(Color.rgb(112,83,172), Color.rgb(213,139,118), 22));
        TextView kicker = text("YOUR LISTENING SPACE", 10, Color.rgb(244,232,252), true); kicker.setLetterSpacing(.13f); hero.addView(kicker);
        TextView title = text("Find your\nfrequency.", 29, WHITE, false); title.setLetterSpacing(-.025f); padding(title, 0, 9, 0, 8); hero.addView(title);
        TextView copy = text("A soundtrack for wherever today takes you.", 12, Color.rgb(251,239,244), false); hero.addView(copy);
        addGap(hero, 15);
        TextView listen = pill("Play the mix  ›", INK, WHITE, new Runnable() { @Override public void run() { playSongs(Catalog.SONGS, 0); } });
        hero.addView(listen, new LinearLayout.LayoutParams(-2, -2)); content.addView(hero, new LinearLayout.LayoutParams(-1, dp(225)));
        addGap(content, 20);
        LinearLayout quick1 = row(); quick1.addView(shortcut("heart-filled", "Liked songs", Color.rgb(236, 219, 246), new Runnable() { @Override public void run() { librarySection = "liked"; showTab("library"); } }), new LinearLayout.LayoutParams(0, dp(68), 1));
        View gap = new View(this); quick1.addView(gap, new LinearLayout.LayoutParams(dp(9), 1));
        quick1.addView(shortcut("clock", "History", Color.rgb(235, 229, 216), new Runnable() { @Override public void run() { librarySection = "recent"; showTab("library"); } }), new LinearLayout.LayoutParams(0, dp(68), 1)); content.addView(quick1);
        addGap(content, 9);
        LinearLayout quick2 = row(); quick2.addView(shortcut("library", "Playlists", Color.rgb(225, 235, 231), new Runnable() { @Override public void run() { librarySection = "playlists"; showTab("library"); } }), new LinearLayout.LayoutParams(0, dp(68), 1));
        View gap2 = new View(this); quick2.addView(gap2, new LinearLayout.LayoutParams(dp(9), 1));
        quick2.addView(shortcut("search", "Find music", Color.rgb(247, 224, 219), new Runnable() { @Override public void run() { showTab("search"); } }), new LinearLayout.LayoutParams(0, dp(68), 1)); content.addView(quick2);
        addGap(content, 29); sectionTitle(content, "Made for your day", "A few mixes from the bundled collection."); addGap(content, 14);
        HorizontalScrollView horizontal = new HorizontalScrollView(this); horizontal.setHorizontalScrollBarEnabled(false);
        LinearLayout mixes = row();
        mixes.addView(mixCard("Slow\nmornings", "images/cover-slow-mornings.webp", Catalog.mix(2,0,4,8,1)));
        mixes.addView(mixCard("Night\ndrive", "images/cover-night-drive.webp", Catalog.mix(1,5,3,4,8)));
        mixes.addView(mixCard("Good\nenergy", "images/cover-good-energy.webp", Catalog.mix(7,6,0,9,3)));
        mixes.addView(mixCard("Focus\nflow", "images/cover-focus-flow.webp", Catalog.mix(8,4,0,2,1)));
        horizontal.addView(mixes); content.addView(horizontal);
        addGap(content, 28); sectionTitle(content, "Quick picks", "Full-length CC0 tracks, ready to play."); addGap(content, 11);
        for (int i = 0; i < Math.min(5, Catalog.SONGS.size()); i++) content.addView(songRow(Catalog.SONGS.get(i), Catalog.SONGS, i, false));
    }
    private View shortcut(String icon, String label, int tint, final Runnable action) {
        LinearLayout tile = row(); tile.setBackground(shape(WHITE, 15)); tile.setContentDescription(label); tile.setFocusable(true);
        FrameLayout square = new FrameLayout(this); square.setBackground(shape(tint, 14)); IconView glyph = new IconView(this, icon, INK);
        square.addView(glyph, new FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER)); tile.addView(square, new LinearLayout.LayoutParams(dp(55), -1));
        TextView name = text(label, 13, INK, true); padding(name, 11, 0, 4, 0); name.setMaxLines(2); tile.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        tile.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { action.run(); } }); return tile;
    }
    private View mixCard(String name, String art, final List<Song> mix) {
        FrameLayout card = new FrameLayout(this); card.setBackground(shape(Color.rgb(61,56,78), 17)); card.setClipToOutline(true); card.setContentDescription("Play " + name.replace('\n', ' ')); card.setFocusable(true);
        card.addView(cover(art, 17), new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this); shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {Color.TRANSPARENT, Color.argb(220, 20, 18, 27)})); card.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        TextView title = text(name, 18, WHITE, true); padding(title, 13, 0, 10, 13); FrameLayout.LayoutParams pos = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM); card.addView(title, pos);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(145), dp(173)); lp.setMargins(0, 0, dp(11), 0); card.setLayoutParams(lp);
        card.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { playSongs(mix, 0); } }); return card;
    }

    private View songRow(final Song song, final List<Song> tracks, final int index, final boolean inPlaylist) {
        LinearLayout row = row(); padding(row, 0, 5, 0, 5); row.setMinimumHeight(dp(66)); row.setContentDescription("Play " + song.title); row.setFocusable(true);
        ImageView image = cover(song.artwork, 10); row.addView(image, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout info = column(); padding(info, 11, 0, 0, 0); TextView title = text(song.title, 14, INK, true); title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView artist = text(song.artist + (song.isYouTube() ? "  ·  YouTube Music" : ""), 11, MUTED, false); artist.setSingleLine(true); artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(title); info.addView(artist); row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        FrameLayout more = iconButton("more", "Options for " + song.title, MUTED, Color.TRANSPARENT, 38, new Runnable() { @Override public void run() { songOptions(song, inPlaylist); } });
        row.addView(more, new LinearLayout.LayoutParams(dp(38), dp(38)));
        row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View view) { playSongs(tracks, index); } }); return row;
    }
    private void songOptions(final Song song, boolean inPlaylist) {
        final List<String> choices = new ArrayList<String>();
        choices.add(library.isLiked(song) ? "Remove from liked songs" : "Add to liked songs");
        choices.add("Add to playlist"); choices.add("Add to queue");
        if (inPlaylist) choices.add("Remove from this playlist");
        if (song.isYouTube()) {
            if (downloads.fileFor(song) != null) choices.add("Remove download");
            else if (downloads.isPending(song)) choices.add("Download queued");
            else choices.add("Download for offline");
            choices.add("Open in YouTube Music");
        }
        new AlertDialog.Builder(this).setTitle(song.title).setItems(choices.toArray(new String[0]), new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface dialog, int which) {
            String selected = choices.get(which);
            if (selected.contains("liked songs")) { boolean liked = library.toggleLike(song); toast(liked ? "Added to liked songs" : "Removed from liked songs"); renderPage(); updatePlayer(); }
            else if (selected.equals("Add to playlist")) choosePlaylist(song);
            else if (selected.equals("Add to queue")) { if (playback != null) { playback.addToQueue(song); toast("Added to queue"); } else toast("Player is starting"); }
            else if (selected.equals("Remove from this playlist")) { library.removeFromPlaylist(selectedPlaylist, song.id); renderPage(); }
            else if (selected.equals("Download for offline")) confirmDownload(song);
            else if (selected.equals("Download queued")) toast("This download is already queued.");
            else if (selected.equals("Remove download")) confirmRemoveDownload(song);
            else if (selected.equals("Open in YouTube Music")) openYouTube(song);
        }}).setNegativeButton("Cancel", null).show();
    }
    private void choosePlaylist(final Song song) {
        final List<LibraryStore.Playlist> playlists = library.playlists();
        if (playlists.isEmpty()) { createPlaylist(song); return; }
        String[] names = new String[playlists.size() + 1];
        for (int i = 0; i < playlists.size(); i++) names[i] = playlists.get(i).name;
        names[playlists.size()] = "+ Create playlist";
        new AlertDialog.Builder(this).setTitle("Add to playlist").setItems(names, new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface dialog, int which) {
            if (which == playlists.size()) createPlaylist(song);
            else toast(library.addToPlaylist(playlists.get(which).id, song) ? "Added to playlist" : "Already in this playlist");
        }}).setNegativeButton("Cancel", null).show();
    }
    private void createPlaylist(final Song firstSong) {
        final EditText input = new EditText(this); input.setSingleLine(true); input.setHint("Playlist name"); input.setMaxLines(1);
        LinearLayout wrap = column(); padding(wrap, 22, 0, 22, 0); wrap.addView(input);
        new AlertDialog.Builder(this).setTitle("Create a playlist").setView(wrap).setNegativeButton("Cancel", null)
                .setPositiveButton("Create", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface dialog, int which) {
                    String name = input.getText().toString().trim();
                    if (name.length() > 60) name = name.substring(0, 60);
                    LibraryStore.Playlist playlist = library.create(name);
                    if (playlist == null) { toast("Enter a playlist name"); return; }
                    if (firstSong != null) { library.addToPlaylist(playlist.id, firstSong); toast("Added to " + playlist.name); }
                    else { tab = "library"; librarySection = "playlists"; selectedPlaylist = playlist.id; renderNavigation(); renderPage(); }
                }}).show();
    }

    private void renderLibrary(LinearLayout content) {
        if (!selectedPlaylist.isEmpty()) {
            final LibraryStore.Playlist playlist = library.playlist(selectedPlaylist);
            if (playlist.id.isEmpty()) { selectedPlaylist = ""; renderLibrary(content); return; }
            LinearLayout top = row();
            top.addView(iconButton("back", "Back to playlists", INK, WHITE, 40, new Runnable() { @Override public void run() { selectedPlaylist = ""; renderPage(); } }), new LinearLayout.LayoutParams(dp(40), dp(40)));
            TextView back = text("YOUR PLAYLIST", 10, MUTED, true); back.setLetterSpacing(.12f); padding(back, 11, 0, 0, 0); top.addView(back, new LinearLayout.LayoutParams(0, -2, 1));
            top.addView(iconButton("more", "Playlist options", INK, WHITE, 40, new Runnable() { @Override public void run() {
                new AlertDialog.Builder(MainActivity.this).setTitle("Delete “" + playlist.name + "”?").setMessage("This removes the playlist locally. It will also be removed from your account the next time you sync.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Delete", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface dialog, int which) { library.deletePlaylist(playlist.id); selectedPlaylist = ""; renderPage(); } }).show();
            } }), new LinearLayout.LayoutParams(dp(40), dp(40))); content.addView(top);
            addGap(content, 20); heading(content, "YOUR MIX", playlist.name, playlist.songs.size() + (playlist.songs.size() == 1 ? " song" : " songs")); addGap(content, 18);
            if (!playlist.songs.isEmpty()) content.addView(pill("Play playlist", WHITE, INK, new Runnable() { @Override public void run() { playSongs(playlist.songs, 0); } }), new LinearLayout.LayoutParams(-2, -2));
            addGap(content, 15);
            if (playlist.songs.isEmpty()) empty(content, "Make it yours", "Add songs using the ••• menu next to a track.");
            for (int i = 0; i < playlist.songs.size(); i++) content.addView(songRow(playlist.songs.get(i), playlist.songs, i, true));
            return;
        }
        heading(content, "YOUR MUSIC", "Your library", "Saved on this device. Account sync is optional."); addGap(content, 12);
        content.addView(pill(syncClient.signedIn() ? "FT account · " + syncClient.username() : "FT account & sync", INK, WHITE, new Runnable() { @Override public void run() { accountMenu(); } }), new LinearLayout.LayoutParams(-2, -2));
        addGap(content, 8);
        content.addView(pill("Check for updates", INK, WHITE, new Runnable() { @Override public void run() { checkForUpdates(); } }), new LinearLayout.LayoutParams(-2, -2));
        addGap(content, 18);
        HorizontalScrollView scroll = new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false); LinearLayout chips = row();
        chips.addView(libraryChip("liked", "Liked")); chips.addView(libraryChip("playlists", "Playlists")); chips.addView(libraryChip("recent", "History")); chips.addView(libraryChip("downloads", "Downloads")); chips.addView(libraryChip("all", "Songs"));
        scroll.addView(chips); content.addView(scroll); addGap(content, 26);
        if (librarySection.equals("playlists")) {
            LinearLayout top = row(); TextView label = text("Your playlists", 22, INK, false); top.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            top.addView(iconButton("plus", "Create playlist", INK, WHITE, 40, new Runnable() { @Override public void run() { createPlaylist(null); } }), new LinearLayout.LayoutParams(dp(40), dp(40))); content.addView(top); addGap(content, 10);
            List<LibraryStore.Playlist> playlists = library.playlists();
            if (playlists.isEmpty()) empty(content, "Start a playlist", "Collect your favorite tracks in a mix of your own.");
            for (final LibraryStore.Playlist playlist : playlists) {
                LinearLayout tile = row(); padding(tile, 0, 6, 0, 6); ImageView art = cover(playlist.songs.isEmpty() ? "images/cover-electric-dreams.webp" : playlist.songs.get(0).artwork, 10);
                tile.addView(art, new LinearLayout.LayoutParams(dp(52), dp(52))); LinearLayout info = column(); padding(info, 12, 0, 0, 0);
                info.addView(text(playlist.name, 14, INK, true)); info.addView(text(playlist.songs.size() + " songs", 11, MUTED, false)); tile.addView(info);
                tile.setFocusable(true); tile.setContentDescription("Open " + playlist.name); tile.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View view) { selectedPlaylist = playlist.id; renderPage(); } }); content.addView(tile);
            }
        } else {
            List<Song> songs = librarySection.equals("liked") ? library.liked() : librarySection.equals("recent") ? library.recent() : librarySection.equals("downloads") ? downloads.list() : Catalog.SONGS;
            String label = librarySection.equals("liked") ? "Liked songs" : librarySection.equals("recent") ? "Recently opened" : librarySection.equals("downloads") ? "Downloaded audio" : "Bundled songs";
            sectionTitle(content, label, songs.size() + (songs.size() == 1 ? " song" : " songs")); addGap(content, 12);
            if (songs.isEmpty()) empty(content, librarySection.equals("liked") ? "The good ones go here" : "Nothing here yet", librarySection.equals("liked") ? "Like a track to keep it close." : librarySection.equals("downloads") ? "Use a YouTube track's options to download permitted audio." : "Play or open a song and it will show up here.");
            for (int i = 0; i < songs.size(); i++) content.addView(songRow(songs.get(i), songs, i, false));
        }
    }
    private View libraryChip(final String section, String label) {
        boolean active = librarySection.equals(section);
        TextView chip = pill(label, active ? WHITE : INK, active ? INK : WHITE, new Runnable() { @Override public void run() { librarySection = section; renderPage(); } });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2); params.setMargins(0, 0, dp(7), 0); chip.setLayoutParams(params); return chip;
    }
    private void empty(LinearLayout content, String title, String message) {
        LinearLayout box = column(); box.setGravity(Gravity.CENTER); padding(box, 20, 30, 20, 30); box.setBackground(shape(WHITE, 17));
        TextView h = text(title, 16, INK, true); h.setGravity(Gravity.CENTER); box.addView(h); TextView p = text(message, 12, MUTED, false); p.setGravity(Gravity.CENTER); padding(p, 0, 7, 0, 0); box.addView(p); content.addView(box);
    }
    private void renderQueue(LinearLayout content) {
        heading(content, "UP NEXT", "Your queue", "The order you're listening in."); addGap(content, 22);
        if (playback == null) { empty(content, "Getting things ready", "The player is starting up."); return; }
        List<Song> queue = new ArrayList<Song>(playback.queue());
        for (int i = 0; i < queue.size(); i++) {
            final int index = i;
            View item = songRow(queue.get(i), queue, i, false);
            item.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (playback != null) { library.record(playback.queue().get(index)); playback.playQueueIndex(index); } } });
            if (i == playback.queueIndex()) item.setBackground(shape(Color.rgb(236, 230, 244), 12)); content.addView(item);
        }
    }

    private void renderSearch(LinearLayout content) {
        heading(content, "FIND YOUR NEXT FAVORITE", "Search", "Songs from your device and YouTube Music."); addGap(content, 21);
        LinearLayout search = row(); search.setBackground(outline(WHITE, 15, LINE)); padding(search, 12, 0, 6, 0);
        IconView icon = new IconView(this, "search", MUTED); search.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        final EditText input = new EditText(this); input.setSingleLine(true); input.setTextSize(14); input.setTextColor(INK); input.setHintTextColor(MUTED); input.setHint("Song, artist, or video link");
        input.setBackgroundColor(Color.TRANSPARENT); input.setImeOptions(EditorInfo.IME_ACTION_SEARCH); input.setText(searchQuery); padding(input, 10, 0, 4, 0);
        search.addView(input, new LinearLayout.LayoutParams(0, dp(52), 1));
        search.addView(iconButton("next", "Search YouTube Music", INK, Color.rgb(237,232,246), 38, new Runnable() { @Override public void run() { runSearch(input); } }), new LinearLayout.LayoutParams(dp(38), dp(38)));
        content.addView(search, new LinearLayout.LayoutParams(-1, dp(56)));
        TextView disclaimer = text("YouTube Music search and playback are experimental, use an unofficial interface, and may stop working. No sign-in is required.", 11, MUTED, false);
        padding(disclaimer, 2, 11, 2, 0); content.addView(disclaimer); addGap(content, 22);
        searchResultContainer = column(); content.addView(searchResultContainer); updateSearchResults();
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s.toString(); searchGeneration++; searching = false; searchedOnline = false; searchError = ""; onlineResults = new ArrayList<Song>(); updateSearchResults();
            }
            @Override public void afterTextChanged(Editable editable) { }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView view, int actionId, android.view.KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) { runSearch(input); return true; } return false;
            }
        });
    }
    private void runSearch(EditText input) {
        final String query = input.getText().toString().trim();
        if (query.isEmpty()) { toast("Enter a song, artist, or YouTube link"); return; }
        if (query.matches("(?i)^(https?://|(?:www\\.|m\\.|music\\.)?youtube\\.com/|youtu\\.be/).*") && YouTubeMusicClient.videoIdFromLink(query) == null) { searchError = "Paste a valid YouTube video link; playlist-only links are not supported."; searchedOnline = false; updateSearchResults(); return; }
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
        final int request = ++searchGeneration; searching = true; searchedOnline = false; searchError = ""; onlineResults = new ArrayList<Song>(); updateSearchResults();
        searchWorker.execute(new Runnable() { @Override public void run() {
            try {
                final List<Song> songs = youtube.search(query);
                main.post(new Runnable() { @Override public void run() { if (request != searchGeneration || isFinishing()) return; onlineResults = songs; searching = false; searchedOnline = true; updateSearchResults(); } });
            } catch (final IOException | RuntimeException failure) {
                main.post(new Runnable() { @Override public void run() { if (request != searchGeneration || isFinishing()) return; searching = false; searchError = failure.getMessage() == null ? "Search is unavailable. Try again later." : failure.getMessage(); updateSearchResults(); } });
            }
        } });
    }
    private void updateSearchResults() {
        if (searchResultContainer == null) return;
        searchResultContainer.removeAllViews();
        if (searchQuery.trim().isEmpty()) {
            sectionTitle(searchResultContainer, "Start with a song", "Search locally, or press search to explore YouTube Music."); addGap(searchResultContainer, 12);
            for (int i = 0; i < Math.min(4, Catalog.SONGS.size()); i++) searchResultContainer.addView(songRow(Catalog.SONGS.get(i), Catalog.SONGS, i, false)); return;
        }
        List<Song> local = Catalog.search(searchQuery);
        if (!local.isEmpty()) {
            sectionTitle(searchResultContainer, "Your collection", local.size() + (local.size() == 1 ? " match" : " matches")); addGap(searchResultContainer, 8);
            for (int i = 0; i < local.size(); i++) searchResultContainer.addView(songRow(local.get(i), local, i, false));
            addGap(searchResultContainer, 20);
        }
        sectionTitle(searchResultContainer, "YouTube Music", searching ? "Searching…" : !searchError.isEmpty() ? searchError : onlineResults.isEmpty() ? (searchedOnline ? "No songs found. Try a different search." : "Press the search button to explore.") : onlineResults.size() + " results");
        addGap(searchResultContainer, 8);
        for (int i = 0; i < onlineResults.size(); i++) searchResultContainer.addView(songRow(onlineResults.get(i), onlineResults, i, false));
    }

    private void confirmDownload(final Song song) {
        new AlertDialog.Builder(this).setTitle("Download for offline?")
                .setMessage("Download only content you have permission to save. YouTube's terms apply. This experimental feature uses only direct, unencrypted audio and may be unavailable. Keep the app open until it finishes; downloads are private to this device.")
                .setNegativeButton("Cancel", null).setPositiveButton("I have permission", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface dialog, int which) {
                    if (!downloads.enqueue(song)) toast("Already downloaded, queued, or the download limit was reached.");
                } }).show();
    }
    private void confirmRemoveDownload(final Song song) {
        new AlertDialog.Builder(this).setTitle("Remove download?").setMessage("The offline audio will be deleted from this device. Your likes and playlists stay.")
                .setNegativeButton("Cancel", null).setPositiveButton("Remove", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface dialog, int which) {
                    if (!downloads.remove(song)) toast("Could not remove the download."); else { renderPage(); updatePlayer(); }
                } }).show();
    }
    @Override public void onDownloadsChanged(String message) {
        if (!isFinishing()) {
            if (message != null && !message.isEmpty()) toast(message);
            if (tab.equals("library") && librarySection.equals("downloads")) renderPage();
            updatePlayer();
        }
    }
    private void openLyrics() {
        if (playback == null) { toast("The player is starting."); return; }
        if (lyricsDialog != null && lyricsDialog.isShowing()) return;
        lyricsDialog = new LyricsDialog(this, new LyricsDialog.Player() {
            @Override public Song song() { return playback == null ? null : playback.current(); }
            @Override public int position() { return playback == null ? 0 : playback.position(); }
            @Override public int duration() { return playback == null ? 0 : playback.duration(); }
            @Override public void seek(int millis) { if (playback != null) playback.seek(millis); }
        }, new Runnable() { @Override public void run() { importLyrics(); } });
        lyricsDialog.show();
    }
    private void importLyrics() {
        if (playback == null) return;
        pendingLyricsSongId = playback.current().id;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("*/*");
        try { startActivityForResult(intent, 301); } catch (Exception failure) { pendingLyricsSongId = ""; toast("No file picker is available."); }
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 301) return;
        final String target = pendingLyricsSongId; pendingLyricsSongId = "";
        if (resultCode != RESULT_OK || data == null || data.getData() == null || target.isEmpty()) return;
        final Uri uri = data.getData();
        searchWorker.execute(new Runnable() { @Override public void run() {
            String message;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("Could not open the file.");
                new ImportedLyricsStore(MainActivity.this).save(target, input); message = "LRC file imported";
            } catch (IOException | SecurityException failure) { message = failure.getMessage() == null ? "Could not import lyrics." : failure.getMessage(); }
            final String status = message;
            main.post(new Runnable() { @Override public void run() {
                if (isFinishing() || isDestroyed()) return;
                toast(status); if (lyricsDialog != null && lyricsDialog.isShowing()) lyricsDialog.reload();
            } });
        } });
    }
    private void openReleasePage(String address) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(address))); }
        catch (Exception failure) { toast("No browser is available to open GitHub."); }
    }
    private void checkForUpdates() {
        if (checkingUpdates) { toast("Already checking for updates."); return; }
        final String currentVersion;
        try { currentVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException failure) { toast("Could not read the installed version."); return; }
        checkingUpdates = true; toast("Checking GitHub Releases…");
        updateWorker.execute(new Runnable() { @Override public void run() {
            try {
                final GithubUpdates.Update update = GithubUpdates.check(currentVersion);
                main.post(new Runnable() { @Override public void run() {
                    checkingUpdates = false; if (isFinishing() || isDestroyed()) return;
                    if (update == null) {
                        new AlertDialog.Builder(MainActivity.this).setTitle("No update found").setMessage("No newer published FT Music release with an APK was found. Installed version: " + currentVersion + ".")
                            .setNegativeButton("Close", null).setPositiveButton("Open releases", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface dialog, int which) { openReleasePage(GithubUpdates.RELEASES_PAGE); } }).show();
                    } else {
                        new AlertDialog.Builder(MainActivity.this).setTitle("Update available · " + update.version)
                            .setMessage((update.prerelease ? "This is a pre-release.\n\n" : "") + "Open GitHub and download " + update.assetName + ". Android will ask you to confirm installation and check the app signature. Do not uninstall the current app if you want to keep its data.")
                            .setNegativeButton("Later", null).setPositiveButton("Open GitHub", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface dialog, int which) { openReleasePage(update.releaseUrl()); } }).show();
                    }
                } });
            } catch (final IOException | RuntimeException failure) {
                main.post(new Runnable() { @Override public void run() {
                    checkingUpdates = false; if (isFinishing() || isDestroyed()) return;
                    new AlertDialog.Builder(MainActivity.this).setTitle("Update check unavailable").setMessage(failure.getMessage() == null ? "Could not reach GitHub." : failure.getMessage())
                        .setNegativeButton("Close", null).setPositiveButton("Open releases", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface dialog, int which) { openReleasePage(GithubUpdates.RELEASES_PAGE); } }).show();
                } });
            }
        } });
    }
    private void accountMenu() {
        if (syncBusy) { toast("A sync is already in progress."); return; }
        if (!syncClient.signedIn()) { accountForm(); return; }
        new AlertDialog.Builder(this).setTitle("FT account · " + syncClient.username())
                .setItems(new String[] {"Sync now", "Sign out", "Sign in to another account"}, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) beginSync(null, null, null, false);
                        else if (which == 1) {
                            syncBusy = true; syncWorker.execute(new Runnable() { @Override public void run() {
                                syncClient.signOut(); main.post(new Runnable() { @Override public void run() { syncBusy = false; if (!isFinishing() && !isDestroyed()) { toast("Signed out"); renderPage(); } } });
                            } });
                        } else accountForm();
                    }
                }).setNegativeButton("Close", null).show();
    }
    private void accountForm() {
        final android.content.SharedPreferences settings = getSharedPreferences("ft_music_account_settings", MODE_PRIVATE);
        LinearLayout form = column(); padding(form, 22, 2, 22, 2);
        TextView info = text("Use your own private HTTPS sync server. First sync merges the local library with this account. Passwords and session tokens are not saved by the app; history, downloads and lyrics do not sync.", 12, MUTED, false); form.addView(info);
        final EditText address = new EditText(this); address.setSingleLine(true); address.setHint("HTTPS server, e.g. https://music.example.com"); address.setText(settings.getString("server", "")); form.addView(address);
        final EditText name = new EditText(this); name.setSingleLine(true); name.setHint("FT username"); name.setText(settings.getString("username", "")); form.addView(name);
        final EditText password = new EditText(this); password.setSingleLine(true); password.setHint("Password (12+ characters)");
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD); password.setSaveEnabled(false);
        if (Build.VERSION.SDK_INT >= 26) password.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); form.addView(password);
        final AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Separate FT Music account").setView(form)
                .setNegativeButton("Cancel", null).setNeutralButton("Create account", null).setPositiveButton("Sign in & sync", null).create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() { @Override public void onShow(DialogInterface ignored) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                if (beginSync(address.getText().toString(), name.getText().toString(), password.getText().toString(), false)) { password.setText(""); dialog.dismiss(); }
            } });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                if (beginSync(address.getText().toString(), name.getText().toString(), password.getText().toString(), true)) { password.setText(""); dialog.dismiss(); }
            } });
        } });
        dialog.show();
    }
    private boolean beginSync(final String address, final String name, final String password, final boolean register) {
        if (syncBusy) { toast("A sync is already in progress."); return false; }
        try {
            if (address != null) {
                SyncClient.normalizeServer(address); String user = name.trim().toLowerCase(Locale.ROOT);
                if ((!user.matches("[a-z0-9_-]{3,32}") || user.equals("__proto__"))) throw new IOException("Username must have 3–32 letters, numbers, underscores or hyphens.");
                if (password.length() < 12 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 128) throw new IOException("Use a password of at least 12 characters and at most 128 UTF-8 bytes.");
            } else if (!syncClient.signedIn()) throw new IOException("Please sign in first.");
            final SyncLibrary captured = library.snapshot();
            syncBusy = true; toast(register ? "Creating account and syncing…" : "Syncing your library…");
            syncWorker.execute(new Runnable() { @Override public void run() {
                try {
                    if (address != null) {
                        syncClient.signIn(address, name, password, register);
                        getSharedPreferences("ft_music_account_settings", MODE_PRIVATE).edit().putString("server", syncClient.server()).putString("username", syncClient.username()).apply();
                    }
                    // Include the server-issued account incarnation so a reset/recreated account
                    // cannot mistake an older baseline for the current account and delete local data.
                    final String identity = syncClient.identity();
                    final SyncLibrary base = library.syncBase(identity);
                    final SyncLibrary merged = syncClient.sync(captured, base);
                    main.post(new Runnable() { @Override public void run() {
                        syncBusy = false; if (isFinishing() || isDestroyed()) return;
                        try {
                            boolean applied = library.applySynced(identity, captured, merged);
                            toast(applied ? "Library synced" : "Local edits were kept. Sync again to merge them.");
                            if (tab.equals("library")) renderPage(); updatePlayer();
                        } catch (IOException error) { new AlertDialog.Builder(MainActivity.this).setTitle("Sync needs attention").setMessage(error.getMessage()).setPositiveButton("OK", null).show(); }
                    } });
                } catch (final IOException | RuntimeException failure) {
                    main.post(new Runnable() { @Override public void run() {
                        syncBusy = false; if (isFinishing() || isDestroyed()) return;
                        new AlertDialog.Builder(MainActivity.this).setTitle("Sync unavailable").setMessage(failure.getMessage() == null ? "Please check your server and try again." : failure.getMessage()).setPositiveButton("OK", null).show();
                        if (tab.equals("library")) renderPage();
                    } });
                }
            } });
            return true;
        } catch (IOException problem) { toast(problem.getMessage()); return false; }
    }

    private void playSongs(List<Song> songs, int index) {
        if (playback == null) { toast("The player is starting. Try again in a moment."); return; }
        if (songs.isEmpty() || index < 0 || index >= songs.size()) return;
        library.record(songs.get(index));
        playback.play(songs, index);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 100);
        }
    }
    private void openYouTube(Song song) {
        if (song == null || !song.isYouTube() || !YouTubeMusicClient.isVideoId(song.videoId)) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/watch?v=" + song.videoId))); }
        catch (Exception failure) { toast("No app is available to open this link."); }
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    @Override public void onPlaybackChanged() {
        updateMini(); updatePlayer();
        if (tab.equals("queue") && playerDialog == null) renderPage();
        if (playback != null) {
            final String problem = playback.takeError();
            if (!problem.isEmpty() && !isFinishing()) {
                final Song song = playback.current();
                AlertDialog.Builder alert = new AlertDialog.Builder(this).setTitle("Playback unavailable").setMessage(problem).setNegativeButton("Close", null);
                if (song.isYouTube()) alert.setPositiveButton("Open YouTube Music", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface dialog, int which) { openYouTube(song); } });
                alert.show();
            }
        }
    }
    private void updateMini() {
        Song song = playback == null ? Catalog.SONGS.get(0) : playback.current();
        miniTitle.setText(song.title);
        miniArtist.setText(playback != null && playback.isBuffering() ? "Buffering…" : song.artist);
        artwork.show(miniCover, song.artwork);
        miniPlay.setKind(playback != null && playback.isPlaying() ? "pause" : "play");
        View parent = (View) miniPlay.getParent(); parent.setContentDescription(playback != null && playback.isPlaying() ? "Pause" : "Play");
    }
    private int[] colorsFor(Song song) {
        String art = song.artwork.toLowerCase(Locale.ROOT);
        if (art.contains("night") || art.contains("electric")) return new int[] {Color.rgb(54, 47, 91), Color.rgb(25, 25, 42)};
        if (art.contains("slow") || art.contains("blue")) return new int[] {Color.rgb(137, 106, 75), Color.rgb(53, 42, 47)};
        if (art.contains("energy")) return new int[] {Color.rgb(137, 74, 92), Color.rgb(53, 35, 59)};
        if (art.contains("focus")) return new int[] {Color.rgb(51, 99, 98), Color.rgb(27, 40, 51)};
        return new int[] {Color.rgb(105, 78, 125), Color.rgb(35, 31, 50)};
    }
    private void openPlayer() {
        if (playback == null) { toast("The player is starting."); return; }
        if (playerDialog != null && playerDialog.isShowing()) return;
        playerDialog = new Dialog(this, android.R.style.Theme_Material_NoActionBar);
        playerDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = playerDialog.getWindow();
        if (window != null) { window.setStatusBarColor(Color.rgb(42, 37, 50)); window.setNavigationBarColor(Color.rgb(42, 37, 50)); window.getDecorView().setSystemUiVisibility(0); }
        playerRoot = column(); playerRoot.setBackground(gradient(colorsFor(playback.current())[0], colorsFor(playback.current())[1], 0));
        if (Build.VERSION.SDK_INT >= 35) playerRoot.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
                return insets.consumeSystemWindowInsets();
            }
        });
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); playerRoot.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout body = column(); padding(body, 24, 18, 24, 25); scroll.addView(body);
        LinearLayout top = row();
        top.addView(iconButton("down", "Close now playing", WHITE, Color.argb(35,255,255,255), 42, new Runnable() { @Override public void run() { if (playerDialog != null) playerDialog.dismiss(); } }), new LinearLayout.LayoutParams(dp(42), dp(42)));
        playerSource = text("NOW PLAYING", 10, Color.rgb(233,224,238), true); playerSource.setGravity(Gravity.CENTER); playerSource.setLetterSpacing(.12f); top.addView(playerSource, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(iconButton("more", "Song options", WHITE, Color.argb(35,255,255,255), 42, new Runnable() { @Override public void run() { if (playback != null) songOptions(playback.current(), false); } }), new LinearLayout.LayoutParams(dp(42), dp(42)));
        body.addView(top); addGap(body, 25);
        int width = getResources().getDisplayMetrics().widthPixels - dp(48);
        int height = getResources().getDisplayMetrics().heightPixels;
        int artSize = Math.max(dp(200), Math.min(Math.min(width, dp(430)), (int) (height * .43f)));
        playerCover = cover(playback.current().artwork, 20); LinearLayout.LayoutParams coverParams = new LinearLayout.LayoutParams(artSize, artSize); coverParams.gravity = Gravity.CENTER_HORIZONTAL; body.addView(playerCover, coverParams);
        addGap(body, 27);
        LinearLayout titleRow = row(); LinearLayout details = column(); playerTitle = text("", 26, WHITE, false); playerTitle.setLetterSpacing(-.025f); playerTitle.setMaxLines(2);
        playerArtist = text("", 14, Color.rgb(226,216,228), false); padding(playerArtist, 0, 5, 0, 0); details.addView(playerTitle); details.addView(playerArtist); titleRow.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
        FrameLayout heart = iconButton("heart", "Like current song", WHITE, Color.argb(35,255,255,255), 46, new Runnable() { @Override public void run() { if (playback != null) { library.toggleLike(playback.current()); updatePlayer(); } } });
        playerHeart = (IconView) heart.getChildAt(0); titleRow.addView(heart, new LinearLayout.LayoutParams(dp(46), dp(46))); body.addView(titleRow);
        addGap(body, 22);
        seekBar = new SeekBar(this); seekBar.setMax(1000); seekBar.setProgressTintList(ColorStateList.valueOf(Color.rgb(244, 171, 154)));
        seekBar.setThumbTintList(ColorStateList.valueOf(WHITE)); seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.argb(70,255,255,255))); seekBar.setContentDescription("Playback position"); body.addView(seekBar, new LinearLayout.LayoutParams(-1, dp(26)));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { if (fromUser && playback != null && playerTime != null) playerTime.setText(formatTime((long) playback.duration() * progress / 1000)); }
            @Override public void onStartTrackingTouch(SeekBar bar) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) { if (playback != null) playback.seek((int) ((long) playback.duration() * bar.getProgress() / 1000)); seeking = false; }
        });
        LinearLayout times = row(); playerTime = text("0:00", 11, Color.rgb(227,218,228), false); playerDuration = text("0:00", 11, Color.rgb(227,218,228), false); times.addView(playerTime, new LinearLayout.LayoutParams(0, -2, 1)); times.addView(playerDuration); body.addView(times);
        addGap(body, 20);
        LinearLayout controls = row(); controls.setGravity(Gravity.CENTER);
        FrameLayout previous = iconButton("previous", "Previous track", WHITE, Color.TRANSPARENT, 54, new Runnable() { @Override public void run() { if (playback != null) playback.previous(); } });
        controls.addView(previous, new LinearLayout.LayoutParams(dp(54), dp(54)));
        View left = new View(this); controls.addView(left, new LinearLayout.LayoutParams(0, 1, 1));
        FrameLayout play = iconButton("play", "Play", INK, WHITE, 72, new Runnable() { @Override public void run() { if (playback != null) playback.toggle(); } });
        playerPlay = (IconView) play.getChildAt(0); FrameLayout.LayoutParams playGlyph = (FrameLayout.LayoutParams) playerPlay.getLayoutParams(); playGlyph.width = dp(30); playGlyph.height = dp(30); playerPlay.setLayoutParams(playGlyph); controls.addView(play, new LinearLayout.LayoutParams(dp(72), dp(72)));
        View right = new View(this); controls.addView(right, new LinearLayout.LayoutParams(0, 1, 1));
        controls.addView(iconButton("next", "Next track", WHITE, Color.TRANSPARENT, 54, new Runnable() { @Override public void run() { if (playback != null) playback.next(); } }), new LinearLayout.LayoutParams(dp(54), dp(54))); body.addView(controls);
        addGap(body, 28);
        LinearLayout footer = row(); footer.setGravity(Gravity.CENTER);
        footer.addView(iconButton("shuffle", "Shuffle upcoming tracks", WHITE, Color.argb(45,255,255,255), 42, new Runnable() { @Override public void run() { if (playback != null) { playback.shuffleUpcoming(); toast("Upcoming tracks shuffled"); } } }), new LinearLayout.LayoutParams(dp(42), dp(42)));
        repeatButton = iconButton("repeat", "Repeat off", WHITE, Color.argb(45,255,255,255), 42, new Runnable() { @Override public void run() { if (playback != null) { playback.cycleRepeat(); toast(playback.repeatMode() == 0 ? "Repeat off" : playback.repeatMode() == 1 ? "Repeat all" : "Repeat one"); } } });
        LinearLayout.LayoutParams repeatParams = new LinearLayout.LayoutParams(dp(42), dp(42)); repeatParams.setMargins(dp(8), 0, dp(8), 0); footer.addView(repeatButton, repeatParams);
        footer.addView(pill("View queue", WHITE, Color.argb(45,255,255,255), new Runnable() { @Override public void run() { if (playerDialog != null) playerDialog.dismiss(); showTab("queue"); } })); body.addView(footer);
        addGap(body, 12);
        TextView lyrics = pill("View lyrics", WHITE, Color.argb(45,255,255,255), new Runnable() { @Override public void run() { openLyrics(); } });
        LinearLayout.LayoutParams lyricsParams = new LinearLayout.LayoutParams(-2, -2); lyricsParams.gravity = Gravity.CENTER_HORIZONTAL; body.addView(lyrics, lyricsParams);
        addGap(body, 9);
        playerOpenYouTube = pill("Open in YouTube Music ↗", WHITE, Color.argb(45,255,255,255), new Runnable() { @Override public void run() { if (playback != null) openYouTube(playback.current()); } });
        LinearLayout.LayoutParams ytParams = new LinearLayout.LayoutParams(-2, -2); ytParams.gravity = Gravity.CENTER_HORIZONTAL; body.addView(playerOpenYouTube, ytParams);
        displayedPlayerId = "";
        playerDialog.setContentView(playerRoot);
        playerDialog.setOnDismissListener(new DialogInterface.OnDismissListener() { @Override public void onDismiss(DialogInterface dialog) { playerDialog = null; playerRoot = null; playerTitle = null; playerArtist = null; playerPlay = null; playerHeart = null; seekBar = null; } });
        playerDialog.show(); updatePlayer(); updateProgress();
    }
    private void updatePlayer() {
        if (playerDialog == null || !playerDialog.isShowing() || playback == null || playerTitle == null) return;
        Song song = playback.current();
        if (!song.id.equals(displayedPlayerId)) {
            displayedPlayerId = song.id; artwork.show(playerCover, song.artwork);
            int[] colors = colorsFor(song); playerRoot.setBackground(gradient(colors[0], colors[1], 0));
        }
        playerTitle.setText(song.title); playerArtist.setText(playback.isBuffering() ? "Buffering…" : song.artist);
        playerSource.setText(song.isYouTube() ? (downloads.fileFor(song) != null ? "OFFLINE DOWNLOAD" : "YOUTUBE MUSIC · EXPERIMENTAL") : "FROM YOUR COLLECTION");
        playerPlay.setKind(playback.isPlaying() ? "pause" : "play"); ((View) playerPlay.getParent()).setContentDescription(playback.isPlaying() ? "Pause" : "Play");
        boolean liked = library.isLiked(song); playerHeart.setKind(liked ? "heart-filled" : "heart"); ((View) playerHeart.getParent()).setContentDescription(liked ? "Remove from liked songs" : "Like current song");
        playerOpenYouTube.setVisibility(song.isYouTube() ? View.VISIBLE : View.GONE);
        repeatButton.setContentDescription(playback.repeatMode() == 0 ? "Repeat off" : playback.repeatMode() == 1 ? "Repeat all" : "Repeat one");
        repeatButton.setBackground(shape(playback.repeatMode() == 0 ? Color.argb(45,255,255,255) : Color.argb(100,255,255,255), 21));
        updateProgress();
    }
    private void updateProgress() {
        if (playback == null || playerDialog == null || !playerDialog.isShowing() || seekBar == null) return;
        int duration = playback.duration(), position = playback.position();
        if (!seeking) seekBar.setProgress(duration > 0 ? (int) Math.min(1000, (long) position * 1000 / duration) : 0);
        seekBar.setEnabled(duration > 0); playerTime.setText(formatTime(position)); playerDuration.setText(formatTime(duration));
    }
    private String formatTime(long millis) {
        long seconds = Math.max(0, millis / 1000); return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60);
    }
}
