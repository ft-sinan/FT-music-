package echo.music.iad1tya.widget

import echo.music.iad1tya.db.entities.LyricsEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLyricsTest {
  @Test
  fun followsTimedLyricsAndDoesNotShowFutureLinesAsCurrent() {
    val lyrics = WidgetLyrics.parse("[00:02.00]First line\n[00:05.50]Second line")
    assertTrue(lyrics is WidgetLyrics.Timed)
    lyrics as WidgetLyrics.Timed
    assertNull(lyrics.at(0).current)
    assertEquals("First line", lyrics.at(0).next)
    assertEquals("First line", lyrics.at(2_000).current)
    assertEquals("Second line", lyrics.at(2_000).next)
    assertEquals("Second line", lyrics.at(5_500).current)
    assertNull(lyrics.at(5_500).next)
  }

  @Test
  fun unsyncedAndUnavailableAreNeverPresentedAsTimed() {
    assertTrue(WidgetLyrics.parse("First line\nSecond line") is WidgetLyrics.Untimed)
    assertEquals(WidgetLyrics.Unavailable, WidgetLyrics.parse(null))
    assertEquals(WidgetLyrics.Unavailable, WidgetLyrics.parse(" "))
    assertEquals(WidgetLyrics.Unavailable, WidgetLyrics.parse(LyricsEntity.LYRICS_NOT_FOUND))
  }

  @Test
  fun timedInstrumentalGapDoesNotRepeatOldWords() {
    val lyrics = WidgetLyrics.Timed(listOf(WidgetLyrics.Line(1000, "Verse"), WidgetLyrics.Line(2000, "")))
    assertEquals("", lyrics.at(2000).current)
  }
}
