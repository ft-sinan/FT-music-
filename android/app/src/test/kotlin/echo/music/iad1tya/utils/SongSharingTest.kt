package echo.music.iad1tya.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SongSharingTest {
  @Test
  fun onlyYouTubeVideoIdsProduceLinks() {
    assertEquals("https://music.youtube.com/watch?v=dQw4w9WgXcQ", youtubeSongLink("dQw4w9WgXcQ"))
    assertNull(youtubeSongLink("content://media/external/audio/media/4"))
    assertNull(youtubeSongLink("too-short"))
    assertNull(youtubeSongLink("dQw4w9WgXcQ&other=1"))
  }
}
