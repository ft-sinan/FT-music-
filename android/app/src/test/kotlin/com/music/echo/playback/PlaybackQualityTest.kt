package echo.music.iad1tya.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackQualityTest {
  @Test fun unknownInformationIsNotGuessed() {
    assertEquals("Quality unknown", PlaybackQuality.describe(null, null, -1, -1))
    assertEquals("Opus", PlaybackQuality.describe("audio/opus", null, -1, -1))
  }

  @Test fun showsActualTrackDetails() {
    assertEquals("AAC · 128 kbps · 44.1 kHz", PlaybackQuality.describe("audio/mp4a-latm", null, 128000, 44100))
    assertEquals("FLAC · 48 kHz", PlaybackQuality.describe("audio/flac", null, -1, 48000))
    assertEquals("PCM · 352.8 kbps · 22.05 kHz", PlaybackQuality.describe("audio/raw", null, 352800, 22050))
  }
}
