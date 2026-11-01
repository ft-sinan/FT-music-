package echo.music.iad1tya.playback

import javax.inject.Inject
import javax.inject.Singleton

/** Retains cache resources opened by any player, including prebuffered and crossfading players. */
@Singleton
class ActivePlaybackTracker @Inject constructor() {
  private val retained = mutableSetOf<String>()

  @Synchronized fun retain(id: String) { retained += id }

  @Synchronized fun ifNotRetained(id: String, action: () -> Unit) {
    if (id !in retained) action()
  }

  @Synchronized fun releaseAll(): Set<String> = retained.toSet().also { retained.clear() }
}
