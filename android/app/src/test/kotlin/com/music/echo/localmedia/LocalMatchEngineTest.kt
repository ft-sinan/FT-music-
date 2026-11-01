package echo.music.iad1tya.localmedia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalMatchEngineTest {
  private val song = MatchTrack("online", "A Song", "An Artist", 200)

  @Test fun exactTitleArtistAndDurationAreConfident() {
    val file = MatchTrack("content://one", "A Song", "An Artist", 202)
    assertEquals(file, LocalMatchEngine.match(song, listOf(file)).confident)
  }

  @Test fun differentArtistOrUnknownDurationRequiresConfirmation() {
    val otherArtist = MatchTrack("one", "A Song", "Another Artist", 200)
    val unknownDuration = MatchTrack("two", "A Song", "An Artist", 0)
    val result = LocalMatchEngine.match(song, listOf(otherArtist, unknownDuration))
    assertNull(result.confident)
    assertEquals(2, result.candidates.size)
  }

  @Test fun similarVersionIsOfferedButNeverAutomaticallySelected() {
    val live = MatchTrack("one", "A Song Live", "An Artist", 200)
    val result = LocalMatchEngine.match(song, listOf(live))
    assertNull(result.confident)
    assertEquals(listOf(live), result.candidates)
  }

  @Test fun duplicateConfidentFilesRequireChoice() {
    val one = MatchTrack("one", "A Song", "An Artist", 200)
    val two = one.copy(id = "two")
    assertNull(LocalMatchEngine.match(song, listOf(one, two)).confident)
  }

  @Test fun unrelatedTitleIsNotSuggested() {
    val unrelated = MatchTrack("one", "Different Song", "An Artist", 200)
    assertEquals(emptyList<MatchTrack>(), LocalMatchEngine.match(song, listOf(unrelated)).candidates)
  }
}
