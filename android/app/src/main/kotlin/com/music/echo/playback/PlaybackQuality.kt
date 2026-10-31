package echo.music.iad1tya.playback

import java.util.Locale

object PlaybackQuality {
  /** Only formats from the selected playing track should be passed here; never guess a bitrate. */
  fun describe(mime: String?, codecs: String?, bitrate: Int, sampleRate: Int): String {
    val type = listOfNotNull(mime, codecs).joinToString(" ").lowercase(Locale.ROOT)
    val codec = when {
      "alac" in type -> "ALAC"
      "flac" in type -> "FLAC"
      "opus" in type -> "Opus"
      "mp4a" in type || "aac" in type -> "AAC"
      "mpeg" in type || "mp3" in type -> "MP3"
      "vorbis" in type -> "Vorbis"
      "wav" in type || "raw" in type -> "PCM"
      else -> null
    }
    val details = mutableListOf<String>()
    if (codec != null) details += codec
    if (bitrate > 0) details += "${bitrate / 1000} kbps"
    if (sampleRate > 0) {
      val khz = sampleRate / 1000.0
      details += if (sampleRate % 1000 == 0) "${sampleRate / 1000} kHz" else String.format(Locale.ROOT, "%.1f kHz", khz)
    }
    return if (details.isEmpty()) "Quality unknown" else details.joinToString(" · ")
  }
}
