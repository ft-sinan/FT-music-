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
    fun perThousand(value: Int): String {
      val whole = value / 1000
      val remainder = value % 1000
      return if (remainder == 0) whole.toString()
        else "$whole.${remainder.toString().padStart(3, '0').trimEnd('0')}"
    }
    val details = mutableListOf<String>()
    if (codec != null) details += codec
    if (bitrate > 0) details += "${perThousand(bitrate)} kbps"
    if (sampleRate > 0) details += "${perThousand(sampleRate)} kHz"
    return if (details.isEmpty()) "Quality unknown" else details.joinToString(" · ")
  }
}
