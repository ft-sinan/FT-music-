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
      if (TIMESTAMP.containsMatchIn(text) && parsed.any { it.text.isNotBlank() }) {
        // The shared parser intentionally ignores timestamp-only lines. For widgets those mark
        // instrumental gaps, so restore them instead of showing a stale verse indefinitely.
        val gaps = emptyTimestampLines(text)
        val lines = (parsed.map { Line(it.time, it.text.trim()) } + gaps)
          .sortedWith(compareBy<Line> { it.timeMs }.thenBy { it.text.isNotBlank() })
        return Timed(lines)
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

private val TIMESTAMP = Regex("""\[(\d{1,2}):(\d{2})\.(\d{2,3})]""")
private val TIMESTAMP_ONLY_LINE = Regex("""^(?:\s*\[\d{1,2}:\d{2}\.\d{2,3}]\s*)+$""")

private fun emptyTimestampLines(raw: String): List<WidgetLyrics.Line> {
  val normalized = raw.trim().removePrefix("\"").removeSuffix("\"")
    .replace("\\\\", "\\").replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
  return normalized.lines().filter { TIMESTAMP_ONLY_LINE.matches(it) }.flatMap { line ->
    TIMESTAMP.findAll(line).map { match ->
      val minutes = match.groupValues[1].toLong()
      val seconds = match.groupValues[2].toLong()
      val milliseconds = match.groupValues[3].padEnd(3, '0').toLong()
      WidgetLyrics.Line((minutes * 60 + seconds) * 1000 + milliseconds, "")
    }.toList()
  }
}
