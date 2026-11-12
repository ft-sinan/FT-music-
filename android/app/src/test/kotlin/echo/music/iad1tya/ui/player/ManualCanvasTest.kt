package echo.music.iad1tya.ui.player

import echo.music.iad1tya.canvas.CanvasArtwork
import echo.music.iad1tya.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualCanvasTest {
  private val song = MediaMetadata(
    id = "12345678901",
    title = "Northern Lights",
    artists = listOf(MediaMetadata.Artist(null, "Example Artist")),
    duration = 180,
    album = MediaMetadata.Album("album", "Night Sky"),
  )

  @Test fun requiresMatchingArtistAndSongOrAlbum() {
    assertTrue(canvasMatches(song, CanvasArtwork(name = "Northern Lights", artist = "Example Artist")))
    assertTrue(canvasMatches(song, CanvasArtwork(name = "Night Sky", artist = "Example Artist", albumName = "Night Sky")))
    assertFalse(canvasMatches(song, CanvasArtwork(name = "Different Song", artist = "Example Artist")))
    assertFalse(canvasMatches(song, CanvasArtwork(name = "Northern Lights", artist = "Another Artist")))
    assertFalse(canvasMatches(song, CanvasArtwork(name = "Northern Lights", artist = null)))
  }

  @Test fun onlyHttpVideoUrlsCanBeSelected() {
    assertNull(safeCanvasUrls(CanvasArtwork(animated = "file:///private/file")))
    assertNull(safeCanvasUrls(CanvasArtwork(animated = "https://user:pass@example.com/video")))
    val safe = safeCanvasUrls(CanvasArtwork(animated = "javascript:alert(1)", videoUrl = "https://example.com/art.mp4"))
    assertNotNull(safe)
    assertNull(safe!!.animated)
    assertEquals("https://example.com/art.mp4", safe.videoUrl)
  }

  @Test fun selectingAgainForcesAPlaybackRetry() {
    val artwork = CanvasArtwork(name = "Northern Lights", artist = "Example Artist", videoUrl = "https://example.com/art.mp4")
    ManualCanvasSelections.select(song.id, artwork)
    val first = ManualCanvasSelections.selections.value.getValue(song.id)
    ManualCanvasSelections.select(song.id, artwork)
    val second = ManualCanvasSelections.selections.value.getValue(song.id)
    assertTrue(second.generation > first.generation)
  }
}
