package echo.music.iad1tya.playback

import androidx.core.net.toUri
import androidx.media3.datasource.DataSpec
import echo.music.iad1tya.utils.InnerTubeXResolver
import echo.music.iad1tya.utils.PlayerClient
import echo.music.iad1tya.utils.YTPlayerUtils.PlaybackData

internal data class ResolvedStream(
  val url: String,
  val expiresAtMs: Long,
  val headers: Map<String, String>,
  val isFromExtension: Boolean = false,
  /** Bitrate the source actually reported for this stream; 0 when it reported none. */
  val bitrate: Int = 0,
  val mimeType: String? = null,
  val sampleRate: Int = 0,
) {
  fun resolve(dataSpec: DataSpec): DataSpec =
    dataSpec.buildUpon()
      .setUri(url.toUri())
      .setHttpRequestHeaders(dataSpec.httpRequestHeaders + headers)
      .build()

  companion object {
    fun from(
      playbackData: PlaybackData,
      nowMs: Long = System.currentTimeMillis(),
      isFromExtension: Boolean = false,
    ): ResolvedStream =
      ResolvedStream(
        url = playbackData.streamUrl,
        expiresAtMs = nowMs + playbackData.streamExpiresInSeconds * 1000L,
        headers = playbackData.headers
          ?: InnerTubeXResolver.headersFor(playbackData.streamUrl)
          ?: PlayerClient.forStreamUrl(playbackData.streamUrl).mediaHeaders(),
        isFromExtension = isFromExtension,
        bitrate = playbackData.format.bitrate,
        mimeType = playbackData.format.mimeType,
        sampleRate = playbackData.format.audioSampleRate ?: 0,
      )
  }
}
