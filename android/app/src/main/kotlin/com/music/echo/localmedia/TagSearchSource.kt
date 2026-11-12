package echo.music.iad1tya.localmedia

import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Where the tag editor gets its suggestions. Kept behind an interface so the Edit info flow can be
 * driven by a fake source in tests instead of depending on live YouTube responses.
 */
interface TagSearchSource {
  /** Songs on YouTube Music matching [query], best matches first. */
  suspend fun search(query: String): List<TagSearchResult>

  /** Cover bytes for a thumbnail URL, or null when they cannot be fetched. */
  suspend fun cover(url: String): ByteArray?
}

@Singleton
class YouTubeTagSearchSource
@Inject
constructor() : TagSearchSource {
  private val httpClient = OkHttpClient()

  override suspend fun search(query: String): List<TagSearchResult> = withContext(Dispatchers.IO) {
    // FILTER_SONG keeps the list to real tracks, so a playlist or artist page can never be
    // accepted into a file's tags.
    val page = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull() ?: return@withContext emptyList()
    page.items
      .filterIsInstance<SongItem>()
      .map { item ->
        TagSearchResult(
          videoId = item.id,
          title = item.title,
          artists = item.artists.joinToString(", ") { it.name },
          // SongItem carries no release year, so the field stays empty rather than guessed.
          album = item.album?.name,
          year = null,
          durationMs = (item.duration ?: 0) * 1000L,
          coverUrl = item.thumbnail.takeIf { it.isNotBlank() },
        )
      }
      .filter { it.title.isNotBlank() && it.artists.isNotBlank() }
  }

  override suspend fun cover(url: String): ByteArray? = withContext(Dispatchers.IO) {
    if (url.isBlank()) return@withContext null
    runCatching {
      httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) return@use null
        response.body?.bytes()?.takeIf { it.isNotEmpty() }
      }
    }.getOrNull()
  }
}
