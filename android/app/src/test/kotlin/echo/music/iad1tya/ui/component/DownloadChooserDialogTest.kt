package echo.music.iad1tya.ui.component

import echo.music.iad1tya.R
import echo.music.iad1tya.constants.DownloadQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The chooser must describe only what the source reported. An unknown size stays unknown rather
 * than being estimated, and no format is labelled lossless unless the source said so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DownloadChooserDialogTest {
  private val unknown = "Size unknown"

  @Test fun detailsReportWhatTheSourceSaid() {
    assertEquals(
      "Opus · 160 kbps · 48 kHz · 20 MB",
      downloadOptionDetails("opus", 160_000, 48_000, 21_000_000L, unknown),
    )
    assertEquals(
      "Mp4a.40.2 · 48 kbps · 44.1 kHz · 1 MB",
      downloadOptionDetails("mp4a.40.2", 48_000, 44_100, 1_500_000L, unknown),
    )
  }

  @Test fun unknownValuesStayUnknown() {
    assertEquals(unknown, downloadOptionDetails(null, null, null, null, unknown))
    assertEquals("Opus · $unknown", downloadOptionDetails("opus", 0, 0, 0L, unknown))
    assertEquals(unknown, downloadOptionDetails("  ", null, null, null, unknown))
    assertEquals("Opus · $unknown", downloadOptionDetails("opus", null, null, null, unknown))
  }

  @Test fun labelsMatchTheDownloadQualitySetting() {
    assertEquals(R.string.ft_download_quality_high, downloadQualityLabel(DownloadQuality.HIGH))
    assertEquals(R.string.ft_download_quality_high, downloadQualityLabel(DownloadQuality.YOUTUBE))
    assertEquals(R.string.ft_download_quality_saver, downloadQualityLabel(DownloadQuality.DATA_SAVER))
  }

  @Test fun nothingIsCalledLosslessUnlessTheSourceSaidSo() {
    val lossy = downloadOptionDetails("opus", 160_000, 48_000, 21_000_000L, unknown)
    assertFalse(lossy.contains("lossless", ignoreCase = true))
    assertFalse(lossy.contains("flac", ignoreCase = true))
    // A codec the source really reported is shown as itself, with no quality claim attached.
    assertEquals(
      "Flac · $unknown",
      downloadOptionDetails("flac", null, null, null, unknown),
    )
  }
}
