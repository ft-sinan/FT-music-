package echo.music.iad1tya.playback

import javax.inject.Inject
import javax.inject.Singleton

/** Prevent expiry from deleting spans that a player or an in-flight cache read/write is using. */
@Singleton
class StreamingCacheGuard @Inject constructor(private val ages: StreamingCacheAges) {
  private val openCounts = mutableMapOf<String, Int>()
  private var playerKeys = emptySet<String>()

  /** The quality-change path may use either the original key or its `_diff` variant. */
  @Synchronized
  fun setActiveMediaIds(mediaIds: Set<String>) {
    val keys = mediaIds.flatMapTo(mutableSetOf()) { listOf(it, "${it}_diff") }
    if (keys == playerKeys) return
    playerKeys = keys
    ages.signalChange()
  }

  /**
   * Runs [beforeFirstUse] before a new/idle reader starts. This can discard already-expired
   * temporary bytes before playback, but never halfway through an active reader or song.
   */
  @Synchronized
  internal fun acquire(key: String, beforeFirstUse: () -> Unit) {
    val openCount = openCounts[key] ?: 0
    if (openCount == 0 && key !in playerKeys) {
      beforeFirstUse()
    }
    openCounts[key] = openCount + 1
  }

  @Synchronized
  internal fun release(key: String) {
    val count = openCounts[key] ?: return
    if (count <= 1) openCounts.remove(key) else openCounts[key] = count - 1
    ages.signalChange()
  }

  /** Atomic with acquire: cleanup cannot race a new cache reader or writer. */
  @Synchronized
  internal fun ifIdle(key: String, action: () -> Unit): Boolean {
    if (key in playerKeys || (openCounts[key] ?: 0) > 0) return false
    action()
    return true
  }
}
