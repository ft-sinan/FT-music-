package echo.music.iad1tya.playback

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel

/** Creation times for temporary player-cache entries, kept separately from downloads. */
@Singleton
class StreamingCacheAges @Inject constructor(@ApplicationContext context: Context) {
  private val preferences: SharedPreferences =
    context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
  private val changes = Channel<Unit>(Channel.CONFLATED)

  @Synchronized
  internal fun recordSpan(key: String, fileTimeMs: Long, initializing: Boolean, firstLiveSpan: Boolean) {
    val now = System.currentTimeMillis().coerceAtLeast(1L)
    val observed = fileTimeMs.takeIf { it in 1..now } ?: now
    val existing = read(key)
    val created = when {
      initializing && existing != null -> minOf(existing, observed)
      firstLiveSpan -> observed
      else -> existing ?: observed
    }
    if (created != existing) {
      val edit = preferences.edit().putLong(key, created)
      // Initialization can discover many old files at once. Their file times are a
      // recoverable fallback if the process stops before this batched write finishes.
      if (initializing) edit.apply() else edit.commit()
      signalChange()
    } else if (firstLiveSpan) {
      signalChange()
    }
  }

  @Synchronized
  internal fun createdAt(key: String, fallbackMs: Long, nowMs: Long): Long {
    val now = nowMs.coerceAtLeast(1L)
    val stored = read(key)
    val fallback = fallbackMs.takeIf { it in 1..now } ?: now
    // Clock changes must not keep a temporary file indefinitely.
    val created = stored?.takeIf { it in 1..now } ?: fallback
    if (created != stored) preferences.edit().putLong(key, created).commit()
    return created
  }

  @Synchronized
  internal fun forget(key: String) {
    if (preferences.contains(key)) preferences.edit().remove(key).commit()
    signalChange()
  }

  private fun read(key: String): Long? =
    try { preferences.getLong(key, 0L).takeIf { it > 0L } }
    catch (_: ClassCastException) { null }

  internal fun signalChange() { changes.trySend(Unit) }
  internal suspend fun awaitChange() { changes.receive() }

  internal companion object {
    const val PREFERENCES_NAME = "ft_streaming_cache_ages"
  }
}
