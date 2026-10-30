package com.ftmusic.app;

import org.junit.Assume;
import org.junit.Test;
import java.io.IOException;
import java.util.List;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Optional network smoke checks. They are skipped during normal, offline-safe CI. */
public final class YouTubeLiveJUnitTest {
    private void requireOptIn() { Assume.assumeTrue("Set FT_LIVE_TESTS=1 to contact YouTube Music", "1".equals(System.getenv("FT_LIVE_TESTS"))); }

    @Test public void anonymousDiscovery() throws Exception {
        requireOptIn();
        List<YouTubeMusicClient.HomeSection> sections = new YouTubeMusicClient().discover();
        assertFalse("No supported Home cards were returned", sections.isEmpty());
        assertFalse("First Home shelf is empty", sections.get(0).items.isEmpty());
    }

    @Test public void anonymousSearchAndDirectAudio() throws Exception {
        requireOptIn();
        YouTubeMusicClient client = new YouTubeMusicClient();
        List<Song> songs = client.search("popular music");
        assertFalse("No search results were returned", songs.isEmpty());
        boolean directAudio = false;
        String lastError = "No compatible stream";
        for (int i = 0; i < Math.min(3, songs.size()); i++) {
            try { directAudio = YouTubeMusicClient.isSafeAudioUrl(client.resolveAudio(songs.get(i).videoId)); }
            catch (IOException problem) { lastError = problem.getMessage(); }
            if (directAudio) break;
        }
        assertTrue("No direct audio was resolved from the first results: " + lastError, directAudio);
    }
}
