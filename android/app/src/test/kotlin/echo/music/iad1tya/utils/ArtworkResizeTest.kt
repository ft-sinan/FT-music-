package echo.music.iad1tya.utils

import echo.music.iad1tya.ui.utils.resize
import org.junit.Assert.assertEquals
import org.junit.Test

/** Artwork must reach big surfaces (player, widgets, notification) at full size. */
class ArtworkResizeTest {
  @Test
  fun sizedGoogleusercontentUrlsAreRaisedToTheLargeVariant() {
    val url = "https://lh3.googleusercontent.com/abc123=w544-h544-l90-rj"
    assertEquals("https://lh3.googleusercontent.com/abc123=w1200-h1200", url.resize(1200, 1200))
    // Small surfaces keep a small request rather than always paying for 1200 px.
    assertEquals("https://lh3.googleusercontent.com/abc123=w500-h500", url.resize(544, 544))
  }

  @Test
  fun unsizedGoogleusercontentUrlsGetASuffix() {
    val url = "https://lh3.googleusercontent.com/abc123"
    assertEquals("https://lh3.googleusercontent.com/abc123=w1200-h1200", url.resize(1200, 1200))
  }

  @Test
  fun youtubeImageUrlsPreferTheLargestVariantWhenAskingFor1200() {
    val url = "https://i.ytimg.com/vi/abc123/hqdefault.jpg"
    assertEquals("https://i.ytimg.com/vi/abc123/maxresdefault.jpg", url.resize(1200, 1200))
    assertEquals("https://i.ytimg.com/vi/abc123/hqdefault.jpg", url.resize(544, 544))
  }

  @Test
  fun unrelatedUrlsPassThroughUntouched() {
    val url = "https://example.com/cover.png"
    assertEquals(url, url.resize(1200, 1200))
  }
}
