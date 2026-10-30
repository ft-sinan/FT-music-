package com.ftmusic.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Playback-position synchronized lyric lines, with explicit unsynced/unavailable states. */
final class LyricsDialog extends Dialog {
    interface Player { Song song(); int position(); int duration(); void seek(int millis); }
    private final Activity activity;
    private final Player player;
    private final Runnable importLrc;
    private final ImportedLyricsStore imported;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LinearLayout linesBox;
    private final ScrollView scroll;
    private final TextView title, status;
    private final List<TextView> views = new ArrayList<TextView>();
    private List<LrcParser.Line> lines = new ArrayList<LrcParser.Line>();
    private String songId = "";
    private int request, active = -2;
    private final Runnable ticker = new Runnable() { @Override public void run() {
        Song song = player.song();
        if (song != null && !song.id.equals(songId)) load(song);
        highlight(); if (isShowing()) main.postDelayed(this, 300);
    } };
    LyricsDialog(Activity activity, Player player, Runnable importLrc) {
        super(activity, android.R.style.Theme_Material_NoActionBar);
        this.activity = activity; this.player = player; this.importLrc = importLrc; imported = new ImportedLyricsStore(activity);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = getWindow(); if (window != null) { window.setStatusBarColor(Color.rgb(38, 32, 50)); window.setNavigationBarColor(Color.rgb(38, 32, 50)); window.getDecorView().setSystemUiVisibility(0); }
        LinearLayout root = new LinearLayout(activity); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.rgb(38, 32, 50));
        if (Build.VERSION.SDK_INT >= 35) root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) { v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom()); return insets.consumeSystemWindowInsets(); }
        });
        LinearLayout top = new LinearLayout(activity); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(dp(20), dp(16), dp(20), dp(6));
        TextView close = button("‹  Close"); close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { dismiss(); } }); top.addView(close);
        TextView heading = label("LYRICS", 12, Color.WHITE, true); heading.setGravity(Gravity.CENTER); top.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        TextView add = button("Import LRC"); add.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { LyricsDialog.this.importLrc.run(); } }); top.addView(add);
        root.addView(top);
        title = label("", 20, Color.WHITE, true); title.setPadding(dp(24), dp(15), dp(24), dp(4)); root.addView(title);
        status = label("", 12, Color.rgb(205, 192, 210), false); status.setPadding(dp(24), 0, dp(24), dp(12)); root.addView(status);
        scroll = new ScrollView(activity); scroll.setFillViewport(false); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        linesBox = new LinearLayout(activity); linesBox.setOrientation(LinearLayout.VERTICAL); linesBox.setPadding(dp(24), dp(18), dp(24), dp(80)); scroll.addView(linesBox);
        setContentView(root);
        setOnDismissListener(new android.content.DialogInterface.OnDismissListener() { @Override public void onDismiss(android.content.DialogInterface dialog) { request++; main.removeCallbacks(ticker); worker.shutdownNow(); } });
    }
    // No lambdas: the standalone fallback APK builder uses legacy dx.
    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private TextView label(String value, float size, int color, boolean bold) {
        TextView view = new TextView(activity); view.setText(value); view.setTextColor(color); view.setTextSize(size);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return view;
    }
    private TextView button(String text) { TextView view = label(text, 13, Color.WHITE, true); view.setPadding(dp(6), dp(10), dp(6), dp(10)); view.setFocusable(true); return view; }
    @Override public void show() { super.show(); main.post(ticker); }
    void reload() { songId = ""; }
    private void load(final Song song) {
        songId = song.id; final int generation = ++request; active = -2; views.clear(); lines.clear(); linesBox.removeAllViews();
        title.setText(song.title + " · " + song.artist); status.setText("Checking saved lyrics, then searching LRCLIB using the song title and artist…");
        final int duration = player.duration();
        worker.execute(new Runnable() { @Override public void run() {
            LyricsClient.Result result;
            try {
                String local = imported.read(song.id);
                if (!LrcParser.parse(local).isEmpty()) result = new LyricsClient.Result(local, "", "Imported LRC · tap a line to seek");
                else result = LyricsClient.lookup(song, duration);
            } catch (IOException | RuntimeException problem) { result = new LyricsClient.Result("", "", "Lyrics unavailable. Check your connection or import an LRC file."); }
            final LyricsClient.Result found = result;
            main.post(new Runnable() { @Override public void run() { if (generation == request && isShowing()) display(found); } });
        } });
    }
    private void display(LyricsClient.Result result) {
        status.setText(result.note); linesBox.removeAllViews(); views.clear(); lines = LrcParser.parse(result.lrc); active = -2;
        if (!lines.isEmpty()) {
            int limit = Math.min(lines.size(), 1000);
            if (lines.size() > limit) { lines = new ArrayList<LrcParser.Line>(lines.subList(0, limit)); status.setText(result.note + " · first 1,000 lines"); }
            for (final LrcParser.Line line : lines) {
                TextView view = label(line.text, 22, Color.rgb(155, 143, 162), true); view.setPadding(0, dp(9), 0, dp(9));
                view.setFocusable(true); view.setContentDescription("Seek to lyric: " + line.text);
                view.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { player.seek((int) Math.min(Integer.MAX_VALUE, line.atMillis)); } });
                linesBox.addView(view); views.add(view);
            }
            highlight();
        } else if (!result.plain.isEmpty()) {
            TextView plain = label(result.plain, 18, Color.WHITE, false); plain.setLineSpacing(dp(7), 1f); linesBox.addView(plain);
        } else {
            TextView empty = label("No timed lyrics to display.", 18, Color.WHITE, true); linesBox.addView(empty);
        }
    }
    private void highlight() {
        int next = LrcParser.currentLine(lines, player.position());
        if (next == active) return;
        if (active >= 0 && active < views.size()) views.get(active).setTextColor(Color.rgb(155, 143, 162));
        active = next;
        if (active >= 0 && active < views.size()) {
            final TextView line = views.get(active); line.setTextColor(Color.WHITE);
            scroll.post(new Runnable() { @Override public void run() { scroll.smoothScrollTo(0, Math.max(0, line.getTop() - scroll.getHeight() / 3)); } });
        }
    }
}
