package echo.music.iad1tya.playback

import echo.music.iad1tya.constants.AudioQuality
import echo.music.iad1tya.constants.DownloadQuality
import echo.music.iad1tya.utils.YTPlayerUtils.PlaybackData
import java.util.Locale
import kotlinx.coroutines.CancellationException

/** A resolved YouTube option. Never store or display its temporary signed stream URL. */
data class DownloadOption(
  val quality: DownloadQuality,
  val source: DownloadSourceIdentity,
  val codecs: String?,
  val bitrate: Int?,
  val sampleRate: Int?,
) {
  val sizeBytes: Long? get() = source.length
  val isRecommended: Boolean get() = quality == DownloadQuality.HIGH
}

/** A failed lookup is different from a source that resolved to the same option twice. */
data class DownloadOptionDiscoveryResult(
  val options: List<DownloadOption>,
  val unavailable: Set<DownloadQuality>,
)

/**
 * Discover only actual options returned by the existing YouTube resolver. This is deliberately
 * read-only: merely opening a chooser must not change preferences, queue a download or reserve
 * a source. Unknown size/bitrate remains unknown, and no file-size limit is imposed.
 */
internal suspend fun discoverYouTubeDownloadOptions(
  mediaId: String,
  resolve: suspend (String, AudioQuality) -> PlaybackData,
): DownloadOptionDiscoveryResult {
  val options = mutableListOf<DownloadOption>()
  val unavailable = mutableSetOf<DownloadQuality>()
  for (quality in listOf(DownloadQuality.HIGH, DownloadQuality.DATA_SAVER)) {
    try {
      val playback = resolve(mediaId, quality.resolverQuality())
      val format = playback.format
      val source = DownloadSourceIdentity.from(
        playback.streamUrl,
        format.itag,
        format.mimeType,
        format.contentLength,
      )
      // A video or unknown container must not be offered as an audio download.
      if (!source.mimeType.startsWith("audio/")) {
        unavailable += quality
        continue
      }
      val codecs = CODECS.find(format.mimeType)?.groupValues?.get(1)
        ?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
      val option = DownloadOption(
        quality = quality,
        source = source,
        codecs = codecs,
        bitrate = format.bitrate.takeIf { it > 0 },
        sampleRate = format.audioSampleRate?.takeIf { it > 0 },
      )
      // If the resolver returned the exact same known representation for both quality
      // requests, show it once (the recommended High choice comes first).
      val duplicate = source.itag != null && source.length != null && options.any {
        it.source == source && it.codecs == option.codecs &&
          it.bitrate == option.bitrate && it.sampleRate == option.sampleRate
      }
      if (!duplicate) options += option
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      // One unavailable option must not hide another successfully resolved option.
      unavailable += quality
    }
  }
  return DownloadOptionDiscoveryResult(options.toList(), unavailable.toSet())
}

private val CODECS = Regex("(?:^|;)\\s*codecs\\s*=\\s*\\\"?([^;\\\"]+)", RegexOption.IGNORE_CASE)
