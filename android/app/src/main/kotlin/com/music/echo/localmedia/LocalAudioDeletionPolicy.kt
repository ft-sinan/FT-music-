package echo.music.iad1tya.localmedia

/** Compare the scanned row with the current row before allowing a destructive action. */
internal data class LocalAudioFingerprint(
  val title: String,
  val size: Long,
  val modifiedSeconds: Long?,
  val durationSeconds: Int,
)

internal object LocalAudioDeletionPolicy {
  fun matches(expected: LocalAudioFingerprint, actual: LocalAudioFingerprint, displayName: String): Boolean =
    displayName.isNotBlank() && expected.size > 0 && expected.size == actual.size &&
      expected.title == actual.title &&
      (expected.modifiedSeconds == null || expected.modifiedSeconds == actual.modifiedSeconds) &&
      (expected.durationSeconds <= 0 || expected.durationSeconds == actual.durationSeconds)
}
