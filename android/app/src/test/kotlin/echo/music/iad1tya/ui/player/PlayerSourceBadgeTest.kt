package echo.music.iad1tya.ui.player

import echo.music.iad1tya.playback.PlaybackOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerSourceBadgeTest {
  @Test fun deviceFilesAndFtDownloadsShowLocal() {
    assertEquals(PlayerSourceBadge.LOCAL, playerSourceBadge(PlaybackOrigin.DEVICE, isCasting = false))
    assertEquals(PlayerSourceBadge.LOCAL, playerSourceBadge(PlaybackOrigin.FT_DOWNLOAD, isCasting = false))
  }

  @Test fun streamingAndUnknownSourcesHaveNoSourceLabel() {
    assertNull(playerSourceBadge(PlaybackOrigin.STREAM, isCasting = false))
    assertNull(playerSourceBadge(PlaybackOrigin.UNKNOWN, isCasting = false))
    assertNull(playerSourceBadge(null, isCasting = false))
  }

  @Test fun castingNeverClaimsPlaybackIsLocal() {
    for (origin in PlaybackOrigin.entries) {
      assertEquals(PlayerSourceBadge.CASTING, playerSourceBadge(origin, isCasting = true))
    }
    assertEquals(PlayerSourceBadge.CASTING, playerSourceBadge(null, isCasting = true))
  }
}
