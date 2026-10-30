package com.ftmusic.app;

import java.util.Arrays;

/** End-to-end JVM client test; caller provides a TLS server trusted by the test JVM. */
public final class SyncIntegrationTest {
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected a trusted HTTPS sync server URL");
        String username = "test_" + Long.toString(System.currentTimeMillis(), 36);
        String password = "correct-horse-123";
        Song a = Catalog.SONGS.get(0), b = Catalog.SONGS.get(1), c = Catalog.SONGS.get(2);
        SyncLibrary blank = new SyncLibrary(Arrays.<Song>asList(), Arrays.<SyncLibrary.Playlist>asList());
        SyncLibrary initial = new SyncLibrary(Arrays.asList(a), Arrays.<SyncLibrary.Playlist>asList());
        SyncClient first = new SyncClient(); first.signIn(args[0], username, password, true);
        SyncLibrary baseA = first.sync(initial, null);
        SyncClient second = new SyncClient(); second.signIn(args[0], username, password, false);
        check(first.identity().equals(second.identity()), "same account incarnation");
        SyncLibrary baseB = second.sync(blank, null);
        check(baseB.liked.size() == 1 && baseB.liked.get(0).id.equals(a.id), "first sync pulls remote songs");
        SyncLibrary editedA = new SyncLibrary(Arrays.asList(b), Arrays.<SyncLibrary.Playlist>asList());
        SyncLibrary afterA = first.sync(editedA, baseA);
        SyncLibrary editedB = new SyncLibrary(Arrays.asList(a, c), Arrays.<SyncLibrary.Playlist>asList());
        SyncLibrary afterB = second.sync(editedB, baseB);
        check(afterB.liked.size() == 2 && afterB.liked.get(0).id.equals(c.id) && afterB.liked.get(1).id.equals(b.id), "stale device merges additions and deletion");
        SyncLibrary finalA = first.sync(afterA, afterA);
        check(finalA.liked.size() == 2 && finalA.liked.get(0).id.equals(c.id) && finalA.liked.get(1).id.equals(b.id), "first device adopts the remote order and addition");
        first.signOut(); second.signOut();
        check(!first.signedIn() && !second.signedIn(), "sign out clears tokens");
        System.out.println("HTTPS sync integration: passed (two JVM clients, private test server)");
    }
}
