package echo.music.iad1tya.playback

import java.security.MessageDigest
import java.util.Locale

object SharedDownloadNames {
  fun extension(mimeType: String): String? = when (mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)) {
    "audio/mp4", "audio/x-m4a", "video/mp4" -> "m4a"
    "audio/webm", "video/webm" -> "webm"
    "audio/ogg", "application/ogg" -> "ogg"
    "audio/mpeg" -> "mp3"
    "audio/aac", "audio/aac-adts" -> "aac"
    "audio/flac", "audio/x-flac" -> "flac"
    "audio/wav", "audio/x-wav" -> "wav"
    else -> null
  }

  fun fileName(id: String, title: String, artist: String, extension: String): String {
    val rawLabel = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" - ")
      .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
      .replace(Regex("\\s+"), " ")
      .trim(' ', '.')
    // Keep even a title made entirely of four-byte characters under filesystem limits.
    val bounded = StringBuilder()
    var offset = 0
    while (offset < rawLabel.length) {
      val codePoint = rawLabel.codePointAt(offset)
      val part = String(Character.toChars(codePoint))
      if ((bounded.toString() + part).toByteArray(Charsets.UTF_8).size > 180) break
      bounded.append(part)
      offset += Character.charCount(codePoint)
    }
    val label = bounded.toString().trim(' ', '.').ifBlank { "Song" }
    val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
      .take(6).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return "$label [$digest].$extension"
  }
}
