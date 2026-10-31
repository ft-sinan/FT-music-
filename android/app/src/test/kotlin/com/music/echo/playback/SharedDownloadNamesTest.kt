package echo.music.iad1tya.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedDownloadNamesTest {
  @Test fun keepsTheActualContainerExtension() {
    assertEquals("m4a", SharedDownloadNames.extension("audio/mp4; codecs=mp4a.40.2"))
    assertEquals("webm", SharedDownloadNames.extension("audio/webm"))
    assertNull(SharedDownloadNames.extension("application/octet-stream"))
  }

  @Test fun fileNamesAreSafeAndDifferentForDifferentSongs() {
    val first = SharedDownloadNames.fileName("one", "Title / Another", "Artist", "m4a")
    val second = SharedDownloadNames.fileName("two", "Title / Another", "Artist", "m4a")
    assertFalse(first.contains('/'))
    assertNotEquals(first, second)
  }
}
