package echo.music.iad1tya.localmedia

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAudioDeletionPolicyTest {
  private val scanned = LocalAudioFingerprint("A song", 2500, 1234, 180)

  @Test fun unchangedFileIsEligible() {
    assertTrue(LocalAudioDeletionPolicy.matches(scanned, scanned, "A song.mp3"))
  }

  @Test fun changedOrRecycledRowsAreRejected() {
    assertFalse(LocalAudioDeletionPolicy.matches(scanned, scanned.copy(title = "Another song"), "other.mp3"))
    assertFalse(LocalAudioDeletionPolicy.matches(scanned, scanned.copy(size = 1), "A song.mp3"))
    assertFalse(LocalAudioDeletionPolicy.matches(scanned, scanned.copy(modifiedSeconds = 1235), "A song.mp3"))
    assertFalse(LocalAudioDeletionPolicy.matches(scanned, scanned.copy(durationSeconds = 181), "A song.mp3"))
    assertFalse(LocalAudioDeletionPolicy.matches(scanned, scanned, ""))
  }

  @Test fun missingOptionalMetadataDoesNotInventValues() {
    val limited = scanned.copy(modifiedSeconds = null, durationSeconds = -1)
    assertTrue(LocalAudioDeletionPolicy.matches(limited, scanned, "A song.mp3"))
    assertFalse(LocalAudioDeletionPolicy.matches(limited.copy(size = 0), scanned, "A song.mp3"))
  }
}
