package echo.music.iad1tya.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import dagger.hilt.android.lifecycle.HiltViewModel
import echo.music.iad1tya.constants.PlaylistSortType
import echo.music.iad1tya.db.MusicDatabase
import echo.music.iad1tya.utils.EpisodeDates
import echo.music.iad1tya.utils.PodcastShowStore
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Followed podcast shows, and the latest episodes across them, newest first. */
@HiltViewModel
class PodcastsViewModel
@Inject
constructor(
  database: MusicDatabase,
  podcastShows: PodcastShowStore,
) : ViewModel() {
  private val followed =
    combine(database.playlists(PlaylistSortType.NAME, false), podcastShows.ids) { playlists, ids ->
      playlists.filter { it.playlist.browseId in ids && it.playlist.bookmarkedAt != null }
    }

  val podcasts = followed.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

  /** Latest episodes of the first [MAX_SHOWS] followed shows, newest first. */
  val newEpisodes = MutableStateFlow<List<SongItem>>(emptyList())
  private var newEpisodesFor: Set<String>? = null
  private var newEpisodesAtMs = 0L

  fun loadNewEpisodes() {
    viewModelScope.launch(Dispatchers.IO) {
      val showIds = followed.first().mapNotNull { it.playlist.browseId }.toSet()
      val now = System.currentTimeMillis()
      if (showIds == newEpisodesFor && now - newEpisodesAtMs < NEW_EPISODES_TTL_MS) return@launch
      val results =
        showIds
          .take(MAX_SHOWS)
          .map { id -> async { YouTube.podcast(id).getOrNull()?.songs?.take(PER_SHOW) } }
          .awaitAll()
      // Offline: keep the list for the same shows, and retry next time.
      if (showIds.isNotEmpty() && results.all { it == null } && showIds == newEpisodesFor) {
        return@launch
      }
      newEpisodes.value =
        results
          .filterNotNull()
          .flatten()
          .sortedByDescending { EpisodeDates.parse(it.publishedText, now) ?: 0L }
          .take(MAX_EPISODES)
      if (results.none { it == null }) {
        newEpisodesFor = showIds
        newEpisodesAtMs = now
      }
    }
  }

  private companion object {
    const val MAX_SHOWS = 20
    const val PER_SHOW = 5
    const val MAX_EPISODES = 20
    const val NEW_EPISODES_TTL_MS = 30 * 60 * 1000L
  }
}
