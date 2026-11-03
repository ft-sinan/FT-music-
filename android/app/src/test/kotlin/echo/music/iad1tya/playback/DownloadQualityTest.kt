package echo.music.iad1tya.playback

import com.metrolist.innertubex.extraction.AudioQuality as ExtractorQuality
import echo.music.iad1tya.constants.AudioQuality
import echo.music.iad1tya.constants.DownloadQuality
import echo.music.iad1tya.utils.InnerTubeXResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DownloadQualityTest {
  @Test fun legacyPreferenceIsHighAndChoicesSelectDistinctExtractorRungs() {
    assertEquals(DownloadQuality.HIGH, DownloadQuality.YOUTUBE.canonical())
    assertEquals(AudioQuality.HIGH, DownloadQuality.YOUTUBE.resolverQuality())
    assertEquals(AudioQuality.HIGH, DownloadQuality.HIGH.resolverQuality())
    assertEquals(AudioQuality.LOW, DownloadQuality.DATA_SAVER.resolverQuality())
    assertEquals(ExtractorQuality.HIGH, InnerTubeXResolver.audioRung(DownloadQuality.HIGH.resolverQuality().maxKbps, false))
    assertEquals(ExtractorQuality.LOW, InnerTubeXResolver.audioRung(DownloadQuality.DATA_SAVER.resolverQuality().maxKbps, false))
  }

  @Test fun resumeRequiresTheSameKnownRepresentation() {
    val old = DownloadSourceIdentity(251, "audio/webm", 4_000L, "123")
    assertTrue(canResumeDownload(old, old))
    assertFalse(canResumeDownload(old, old.copy(itag = 140)))
    assertFalse(canResumeDownload(old, old.copy(mimeType = "audio/mp4")))
    assertFalse(canResumeDownload(old, old.copy(length = 3_999L)))
    assertFalse(canResumeDownload(old, old.copy(revision = "456")))
    assertFalse(canResumeDownload(old, old.copy(revision = null)))
    assertFalse(canResumeDownload(old.copy(itag = null), old.copy(itag = null)))
    assertFalse(canResumeDownload(old.copy(length = null), old.copy(length = null)))
    assertFalse(canResumeDownload(old.copy(mimeType = ""), old.copy(mimeType = "")))
    // Not every provider supplies a revision; a known itag, MIME and length are
    // still required, and a revision must match whenever the original provided one.
    assertTrue(canResumeDownload(old.copy(revision = null), old.copy(revision = null)))
  }

  @Test fun unknownItagIsNotInventedForTheIdentity() {
    val source = DownloadSourceIdentity.from("https://example.com/audio?clen=4200", 0, "audio/webm; codecs=\"opus\"", null)
    assertEquals(null, source.itag)
    assertEquals("audio/webm", source.mimeType)
    assertEquals(4_200L, source.length)
    assertFalse(canResumeDownload(source, source))
    val withUrlItag = DownloadSourceIdentity.from("https://example.com/audio?itag=251&clen=4200&lmt=42", 0, "audio/webm", null)
    assertEquals(251, withUrlItag.itag)
    assertEquals("42", withUrlItag.revision)
    assertTrue(canResumeDownload(withUrlItag, withUrlItag))
  }
}
