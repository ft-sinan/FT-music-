package echo.music.iad1tya.playback

import echo.music.iad1tya.utils.PodcastProgress
import java.util.concurrent.ConcurrentHashMap

/**
 * Podcast streams never touch the streaming cache: a download is the only on-device copy of an
 * episode. Every id recognised as an episode - queued for playback or already carrying local
 * progress - is excluded from cache reads and writes here.
 */
object PodcastStreamPolicy {
  private val episodeIds = ConcurrentHashMap.newKeySet<String>()

  fun noteEpisode(id: String) {
    episodeIds.add(id)
  }

  fun neverCache(id: String): Boolean = id in episodeIds || PodcastProgress.get(id) != null
}
