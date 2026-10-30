package com.ftmusic.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/** Standalone JVM smoke tests for the API parsing and queue; uses a real org.json implementation. */
public final class NativeCoreTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        String id = "dQw4w9WgXcQ";
        check(YouTubeMusicClient.videoIdFromLink("https://music.youtube.com/watch?v=" + id + "&list=RD") .equals(id), "music URL");
        check(YouTubeMusicClient.videoIdFromLink("youtu.be/" + id + "?t=1").equals(id), "short URL");
        check(YouTubeMusicClient.videoIdFromLink("https://www.youtube.com/shorts/" + id).equals(id), "shorts URL");
        check(YouTubeMusicClient.videoIdFromLink("https://youtube.com.evil.example/watch?v=" + id) == null, "reject forged host");
        check(YouTubeMusicClient.videoIdFromLink("https://youtube.com@evil.example/watch?v=" + id) == null, "reject userinfo");
        check(YouTubeMusicClient.videoIdFromLink("https://music.youtube.com/playlist?list=123") == null, "reject playlist-only link");
        check(YouTubeMusicClient.videoIdFromLink("https://youtube.com/watch?v=bad") == null, "reject invalid ID");
        check(YouTubeMusicClient.isSafeAudioUrl("https://rr1.googlevideo.com/videoplayback?x=1"), "allow Google video host");
        check(!YouTubeMusicClient.isSafeAudioUrl("https://rr1.googlevideo.com.evil.example/video"), "reject fake video host");
        check(!YouTubeMusicClient.isSafeAudioUrl("https://googlevideo.com@evil.example/video"), "reject video userinfo");
        check(YouTubeMusicClient.safeThumbnail("https://lh3.googleusercontent.com/some-cover").length() > 0, "allow YTM thumbnail");
        check(YouTubeMusicClient.safeThumbnail("https://i.ytimg.com.evil.example/image").isEmpty(), "reject forged thumbnail host");

        JSONObject columns = new JSONObject().put("musicResponsiveListItemFlexColumnRenderer",
                new JSONObject().put("text", new JSONObject().put("runs", new JSONArray().put(new JSONObject().put("text", "Example Song")))));
        JSONObject artist = new JSONObject().put("musicResponsiveListItemFlexColumnRenderer",
                new JSONObject().put("text", new JSONObject().put("runs", new JSONArray().put(new JSONObject().put("text", "Example Artist • Album")))));
        JSONObject renderer = new JSONObject().put("playlistItemData", new JSONObject().put("videoId", id))
                .put("flexColumns", new JSONArray().put(columns).put(artist))
                .put("thumbnail", new JSONObject().put("thumbnails", new JSONArray().put(new JSONObject().put("url", "https://lh3.googleusercontent.com/art"))));
        JSONObject result = new JSONObject().put("items", new JSONArray().put(new JSONObject().put("musicResponsiveListItemRenderer", renderer))
                .put(new JSONObject().put("musicResponsiveListItemRenderer", renderer)));
        List<Song> songs = YouTubeMusicClient.parseSearch(result);
        check(songs.size() == 1, "deduplicate search results");
        check(songs.get(0).title.equals("Example Song") && songs.get(0).artist.equals("Example Artist"), "extract search metadata");
        check(Song.fromJson(songs.get(0).toJson()).id.equals("yt:" + id), "persist remote song safely");
        check(Song.fromJson(new JSONObject().put("id", "yt:bad").put("title", "Bad").put("videoId", "bad")) == null, "reject tampered record");

        JSONObject response = new JSONObject().put("streamingData", new JSONObject().put("adaptiveFormats", new JSONArray()
                .put(new JSONObject().put("mimeType", "audio/mp4").put("bitrate", 96000).put("url", "https://r1.googlevideo.com/low"))
                .put(new JSONObject().put("mimeType", "audio/mp4").put("bitrate", 128000).put("url", "https://r1.googlevideo.com/high"))
                .put(new JSONObject().put("mimeType", "audio/mp4").put("bitrate", 999999).put("url", "https://evil.example/bad"))));
        check(YouTubeMusicClient.chooseAudio(response).endsWith("/high"), "select highest safe AAC stream");
        try { YouTubeMusicClient.chooseAudio(new JSONObject()); throw new AssertionError("missing audio must fail"); }
        catch (IOException expected) { check(expected.getMessage().contains("No compatible"), "missing stream error"); }

        PlayQueue queue = new PlayQueue();
        queue.replace(Arrays.asList(Catalog.SONGS.get(0), Catalog.SONGS.get(1), Catalog.SONGS.get(2)), 1);
        check(queue.current().id.equals("city-in-the-rearview"), "selected queue index");
        check(queue.previous() && queue.current().id.equals("blue-skies"), "previous");
        check(!queue.previous(), "no previous at start");
        queue.shuffleUpcoming();
        check(queue.current().id.equals("blue-skies") && queue.items().size() == 3, "shuffle preserves current track");
        check(queue.select(2) && !queue.next(), "no next beyond end");
        check(!queue.select(9), "reject out-of-range queue index");
        List<LrcParser.Line> lrc = LrcParser.parse("[ar:Artist]\n[offset:-250]\n[00:03.1][00:01.25]Earlier\n[00:02.500]Second\n[00:99.00]Invalid\n[00:04.001]Last");
        check(lrc.size() == 4, "parse multi-timestamp and ignore metadata/invalid seconds");
        check(lrc.get(0).atMillis == 1000 && lrc.get(0).text.equals("Earlier"), "sort and apply LRC offset");
        check(lrc.get(1).atMillis == 2250 && lrc.get(2).atMillis == 2850 && lrc.get(3).atMillis == 3751, "parse tenths hundredths and milliseconds");
        check(LrcParser.currentLine(lrc, 999) == -1 && LrcParser.currentLine(lrc, 1000) == 0 && LrcParser.currentLine(lrc, 3000) == 2 && LrcParser.currentLine(lrc, 9000) == 3, "binary search current lyric");
        check(LrcParser.parse("[00:00.5] \n[not a lyric]x").isEmpty(), "ignore blank or malformed lines");
        Song sample = new Song("yt:" + id, "Example Song", "Example Artist", "", "", "", id);
        JSONArray lyricResults = new JSONArray().put(new JSONObject().put("trackName", "Unrelated").put("artistName", "Stranger").put("syncedLyrics", "[00:00]wrong"))
            .put(new JSONObject().put("trackName", "Example Song").put("artistName", "Example Artist").put("duration", 100).put("syncedLyrics", "[00:01]line"));
        check(LyricsClient.choose(lyricResults, sample, 100000).lrc.equals("[00:01]line"), "select matching synced lyrics");
        check(LyricsClient.choose(new JSONArray().put(new JSONObject().put("trackName", "Example Song").put("artistName", "Example Artist").put("plainLyrics", "Plain lyric")), sample, 0).note.contains("Unsynced"), "label unsynced lyrics");
        check(LyricsClient.choose(new JSONArray().put(new JSONObject().put("trackName", "Example Song").put("artistName", "Example Artist").put("instrumental", true)), sample, 0).note.contains("instrumental"), "label instrumental");
        check(LyricsClient.choose(new JSONArray().put(lyricResults.getJSONObject(0)), sample, 0).lrc.isEmpty(), "reject unrelated lyrics");
        JSONObject protectedOnly = new JSONObject().put("streamingData", new JSONObject().put("adaptiveFormats", new JSONArray().put(new JSONObject().put("mimeType", "audio/mp4").put("url", "https://r1.googlevideo.com/x").put("drmFamilies", new JSONArray().put("WIDEVINE")))));
        try { YouTubeMusicClient.chooseAudio(protectedOnly); throw new AssertionError("protected audio must not be selected"); } catch (IOException expected) { check(true, "reject protected audio"); }

        Song a = Catalog.SONGS.get(0), b = Catalog.SONGS.get(1), c = Catalog.SONGS.get(2);
        SyncLibrary blank = new SyncLibrary(Arrays.<Song>asList(), Arrays.<SyncLibrary.Playlist>asList());
        SyncLibrary one = new SyncLibrary(Arrays.asList(a), Arrays.<SyncLibrary.Playlist>asList());
        SyncLibrary two = new SyncLibrary(Arrays.asList(b), Arrays.<SyncLibrary.Playlist>asList());
        SyncLibrary first = SyncLibrary.merge(null, one, two);
        check(first.liked.size() == 2, "first sync is additive");
        check(SyncLibrary.merge(one, blank, one).liked.isEmpty(), "local like removal propagates");
        check(SyncLibrary.merge(one, one, blank).liked.isEmpty(), "remote like removal propagates");
        SyncLibrary changed = new SyncLibrary(Arrays.asList(a, c), Arrays.<SyncLibrary.Playlist>asList());
        SyncLibrary combined = SyncLibrary.merge(one, changed, two);
        check(combined.liked.size() == 2 && combined.liked.get(0).id.equals(c.id) && combined.liked.get(1).id.equals(b.id), "merge removals and simultaneous additions");
        SyncLibrary.Playlist original = new SyncLibrary.Playlist("p1", "Mix", Arrays.asList(a));
        SyncLibrary playlistBase = new SyncLibrary(Arrays.<Song>asList(), Arrays.asList(original));
        SyncLibrary localMix = new SyncLibrary(Arrays.<Song>asList(), Arrays.asList(new SyncLibrary.Playlist("p1", "Mix", Arrays.asList(a, b))));
        SyncLibrary remoteMix = new SyncLibrary(Arrays.<Song>asList(), Arrays.asList(new SyncLibrary.Playlist("p1", "Mix", Arrays.asList(a, c))));
        SyncLibrary mergedMix = SyncLibrary.merge(playlistBase, localMix, remoteMix);
        check(mergedMix.playlists.get(0).songs.size() == 3, "merge simultaneous playlist additions");
        check(SyncLibrary.merge(playlistBase, blank, playlistBase).playlists.isEmpty(), "propagate unchanged playlist deletion");
        try { SyncLibrary.merge(playlistBase, blank, remoteMix); throw new AssertionError("delete/edit conflict must fail"); }
        catch (SyncLibrary.Conflict expected) { check(true, "detect delete/edit conflict without upload"); }
        check(SyncLibrary.same(mergedMix, SyncLibrary.fromJson(mergedMix.toJson())), "sync JSON round trip");
        SyncLibrary remoteOrder = new SyncLibrary(Arrays.asList(b, a), Arrays.<SyncLibrary.Playlist>asList());
        check(SyncLibrary.same(SyncLibrary.merge(one, one, remoteOrder), remoteOrder), "unchanged device adopts remote ordering");
        try { SyncLibrary.fromJson(new JSONObject().put("liked", new JSONArray().put(new JSONObject().put("id", "unknown").put("title", "Unknown").put("artist", "").put("album", "").put("artwork", "").put("audioAsset", "").put("videoId", ""))).put("playlists", new JSONArray())); throw new AssertionError("unknown track must fail"); }
        catch (IOException expected) { check(true, "unsupported remote tracks cannot be silently discarded"); }
        check(SyncClient.normalizeServer("https://Example.com:8443/").equals("https://example.com:8443"), "normalize HTTPS sync server");
        for (String invalid : Arrays.asList("http://example.com", "https://u:p@example.com", "https://example.com/path", "https://example.com/?q=secret")) {
            try { SyncClient.normalizeServer(invalid); throw new AssertionError("insecure sync server must fail"); }
            catch (IOException expected) { check(true, "reject insecure or ambiguous sync URL"); }
        }
        check(GithubUpdates.versionCode("0.3.0") == 3000 && GithubUpdates.versionCode("v0.3.1") == 3001, "release versionCode formula");
        check(GithubUpdates.versionCode("0.3.10") > GithubUpdates.versionCode("0.3.2"), "numeric version ordering");
        check(GithubUpdates.versionCode("1.0.0") > GithubUpdates.versionCode("0.999.999"), "major version ordering");
        for (String invalid : Arrays.asList("0.03.0", "0.3", "v0.3.0-beta", "-1.2.3", "2100.0.0", "1.1000.0", "v0.3.0/../../evil")) check(GithubUpdates.versionCode(invalid) < 0, "reject invalid update version");
        JSONArray releases = new JSONArray()
            .put(new JSONObject().put("tag_name", "v0.3.9").put("draft", true).put("assets", new JSONArray().put(new JSONObject().put("name", "FT-Music-v0.3.9.apk").put("state", "uploaded"))))
            .put(new JSONObject().put("tag_name", "v0.3.3").put("draft", false).put("assets", new JSONArray().put(new JSONObject().put("name", "something-else.apk").put("state", "uploaded"))))
            .put(new JSONObject().put("tag_name", "v0.3.4").put("draft", false).put("assets", new JSONArray().put(new JSONObject().put("name", "FT-Music-v0.3.4.apk").put("state", "open"))))
            .put(new JSONObject().put("tag_name", "v0.3.2").put("draft", false).put("prerelease", true).put("html_url", "https://evil.example").put("assets", new JSONArray().put(new JSONObject().put("name", "FT-Music-v0.3.2.apk").put("state", "uploaded"))))
            .put(new JSONObject().put("tag_name", "v0.3.1").put("draft", false).put("assets", new JSONArray().put(new JSONObject().put("name", "FT-Music-v0.3.1.apk").put("state", "uploaded"))));
        GithubUpdates.Update update = GithubUpdates.findNewer(releases, "0.3.0");
        check(update != null && update.version.equals("0.3.2") && update.prerelease, "select newest available published APK including labelled prereleases");
        check(update.releaseUrl().equals("https://github.com/ft-sinan/FT-music-/releases/tag/v0.3.2"), "construct trusted release URL instead of using response URL");
        check(GithubUpdates.findNewer(releases, "0.3.2") == null, "ignore drafts and incomplete or incorrectly named assets");
        try { GithubUpdates.findNewer(releases, "unknown"); throw new AssertionError("uncomparable version must fail"); } catch (IOException expected) { check(true, "reject uncomparable installed version"); }
        System.out.println("Native core: " + checks + " checks passed (no live YouTube or Android device)");
    }
}
