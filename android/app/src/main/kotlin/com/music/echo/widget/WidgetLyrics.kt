package echo.music.iad1tya.widget

import echo.music.iad1tya.db.entities.LyricsEntity
import echo.music.iad1tya.lyrics.LyricsUtils

/** Parsed once when the database row changes, not on every widget progress update. */
internal sealed interface WidgetLyrics {
  data object Loading : WidgetLyrics
  data object Unavailable : WidgetLyrics
  data class Timed(val lines: List<Line>) : WidgetLyrics
  data class Untimed(val text: String) : WidgetLyrics

  data class Line(val timeMs: Long, val text: String)

  companion object {
    fun parse(raw: String?): WidgetLyrics {
      val text = raw?.trim().orEmpty()
      if (text.isEmpty() || text == LyricsEntity.LYRICS_NOT_FOUND) return Unavailable

      val parsed = LyricsUtils.parseLyrics(text)
      if (parsed.any { it.time > 0L } && parsed.any { it.text.isNotBlank() }) {
        return Timed(parsed.map { Line(it.time, it.text.trim()) })
      }

      val plain =
        (if (parsed.isNotEmpty()) parsed.map { it.text } else text.lines())
          .map { it.trim() }
          .filter { it.isNotEmpty() }
          .take(3)
          .joinToString("\n")
          .take(MAX_TEXT_LENGTH)
      return if (plain.isEmpty()) Unavailable else Untimed(plain)
    }
  }
}

/** Returns the active and following lines without showing a future line as if it were current. */
internal fun WidgetLyrics.Timed.at(positionMs: Long): WidgetLyricsFrame {
  val index = lines.indexOfLast { it.timeMs <= positionMs.coerceAtLeast(0L) }
  if (index < 0) {
    return WidgetLyricsFrame(null, lines.firstOrNull { it.text.isNotBlank() }?.text?.take(MAX_TEXT_LENGTH))
  }
  val current = lines[index].text.take(MAX_TEXT_LENGTH)
  val next = lines.drop(index + 1).firstOrNull { it.text.isNotBlank() }?.text?.take(MAX_TEXT_LENGTH)
  return WidgetLyricsFrame(current, next)
}

internal data class WidgetLyricsFrame(val current: String?, val next: String?)
private const val MAX_TEXT_LENGTH = 240
