package echo.music.iad1tya.localmedia

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Everything the Edit info sheet needs to render, as one immutable value. */
data class LocalSongInfoSnapshot(
  val title: String,
  val artists: String,
  val album: String,
  val year: Int?,
  val coverBytes: ByteArray?,
  val coverSource: LocalSongInfoEditor.CoverSource,
  val searchStage: LocalSongInfoEditor.SearchStage,
  val coverProblem: String?,
  val notices: List<String>,
  val canApply: Boolean,
) {
  val hasCover: Boolean get() = coverBytes != null
}

/** One YouTube Music result the user can accept into the local file's tags. */
data class TagSearchResult(
  val videoId: String,
  val title: String,
  val artists: String,
  val album: String?,
  val year: Int?,
  val durationMs: Long,
  val coverUrl: String?,
)

/**
 * Drives the Edit info flow for a local song: pre-fill from the file, search YouTube Music, accept
 * a result, let the user correct it, and hand back exactly what should be written.
 *
 * Nothing here touches Android, so the behaviour is unit-testable. Cover bytes are fetched through
 * the injected [fetchCover] rather than a hard-wired HTTP client.
 */
class LocalSongInfoEditor(
  private val searchYouTube: suspend (String) -> List<TagSearchResult>,
  private val fetchCover: suspend (String) -> ByteArray?,
) {
  enum class CoverSource { NONE, FILE, SEARCH }

  sealed interface SearchStage {
    data object Idle : SearchStage
    data object Searching : SearchStage
    data class Ready(val results: List<TagSearchResult>) : SearchStage
    data class Failed(val reason: SearchFailure) : SearchStage
  }

  enum class SearchFailure { NO_RESULTS, LOOKUP_FAILED, TIMED_OUT }

  var title: String = ""
    private set
  var artists: String = ""
    private set
  var album: String = ""
    private set
  var year: Int? = null
    private set
  var coverBytes: ByteArray? = null
    private set
  var coverMimeType: String? = null
    private set
  var coverSource: CoverSource = CoverSource.NONE
    private set
  var searchStage: SearchStage = SearchStage.Idle
    private set
  /** Set when a chosen result's cover could not be downloaded, so the UI can say so. */
  var coverProblem: String? = null
    private set

  private var container: TagContainer = TagContainer.MP3

  /**
   * Prefills from what the file already says, so Apply never silently blanks a tag. A null
   * [newContainer] means the container is unknown and the last known one is kept.
   */
  fun load(
    newContainer: TagContainer?,
    newTitle: String?,
    newArtists: String?,
    newAlbum: String?,
    newYear: Int?,
  ) {
    newContainer?.let { container = it }
    title = newTitle?.takeIf { it.isNotBlank() } ?: ""
    artists = newArtists?.takeIf { it.isNotBlank() } ?: ""
    album = newAlbum?.takeIf { it.isNotBlank() } ?: ""
    year = newYear
  }

  fun setTitle(value: String) {
    title = value
  }

  fun setArtists(value: String) {
    artists = value
  }

  fun setAlbum(value: String) {
    album = value
  }

  /**
   * Accepts any year a recording could plausibly carry and rejects values that cannot be one, so a
   * mistyped "2" or "99999" is dropped rather than written into the file. Clearing the field leaves
   * the year the file already has alone - [AudioTagEdit] only omits a null year.
   */
  fun setYear(value: String) {
    year = value.trim().toIntOrNull()?.takeIf { it in PLAUSIBLE_YEARS }
  }

  /** A cover the user picked from the device. */
  fun setCoverFromFile(bytes: ByteArray, mimeType: String?) {
    if (bytes.isEmpty()) return
    coverBytes = bytes
    coverMimeType = normalizeCoverMime(mimeType)
    coverSource = CoverSource.FILE
    coverProblem = null
  }

  fun clearCover() {
    coverBytes = null
    coverMimeType = null
    coverSource = CoverSource.NONE
    coverProblem = null
  }

  suspend fun search(query: String) {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return
    searchStage = SearchStage.Searching
    coverProblem = null
    val results = try {
      withTimeoutOrNull(SEARCH_TIMEOUT_MS) { searchYouTube(trimmed) }
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
    searchStage = when {
      results == null -> SearchStage.Failed(SearchFailure.LOOKUP_FAILED)
      results.isEmpty() -> SearchStage.Failed(SearchFailure.NO_RESULTS)
      else -> SearchStage.Ready(results)
    }
  }

  fun clearSearch() {
    searchStage = SearchStage.Idle
  }

  /**
   * Accepts a search result into the fields and pulls its cover. A cover that will not download is
   * reported, never faked: the text tags still apply.
   */
  suspend fun accept(result: TagSearchResult) {
    title = result.title
    artists = result.artists
    album = result.album.orEmpty()
    year = result.year
    val url = result.coverUrl
    if (url == null || !container.supportsCover) {
      coverProblem = if (url == null) "no cover reported for this result" else null
      return
    }
    val bytes = try {
      withTimeoutOrNull(COVER_TIMEOUT_MS) { fetchCover(url) }
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
    if (bytes == null || bytes.isEmpty()) {
      coverProblem = "the cover could not be downloaded"
      return
    }
    coverBytes = bytes
    coverMimeType = normalizeCoverMime(if (url.contains(".png", ignoreCase = true)) "image/png" else "image/jpeg")
    coverSource = CoverSource.SEARCH
    coverProblem = null
  }

  /** What Apply will write. Text tags are always trimmed; a blank title is refused. */
  fun edit(): AudioTagEdit = AudioTagEdit(
    title = title.trim(),
    artists = artists.trim(),
    album = album.trim().takeIf { it.isNotEmpty() },
    year = year,
    coverBytes = if (container.supportsCover) coverBytes else null,
    coverMimeType = if (container.supportsCover) coverMimeType else null,
  )

  /**
   * Truthful warnings shown before Apply: what this container cannot store, and whether the audio
   * itself will be touched (it never is).
   */
  fun notices(): List<String> {
    val notices = mutableListOf<String>()
    if (!container.supportsCover && coverSource != CoverSource.NONE) {
      notices.add(COVER_UNSUPPORTED)
    }
    notices.add(AUDIO_UNTOUCHED)
    return notices
  }

  fun canApply(): Boolean = title.isNotBlank() && artists.isNotBlank()

  /**
   * One immutable view of the editor for Compose. The view model republishes it after every change
   * so the sheet re-reads the fields instead of guessing what mutated.
   */
  fun snapshot(): LocalSongInfoSnapshot = LocalSongInfoSnapshot(
    title = title,
    artists = artists,
    album = album,
    year = year,
    coverBytes = coverBytes,
    coverSource = coverSource,
    searchStage = searchStage,
    coverProblem = coverProblem,
    notices = notices(),
    canApply = canApply(),
  )

  private fun normalizeCoverMime(mime: String?): String = when {
    mime == null -> "image/jpeg"
    mime.contains("png", ignoreCase = true) -> "image/png"
    mime.contains("jpeg", ignoreCase = true) || mime.contains("jpg", ignoreCase = true) -> "image/jpeg"
    else -> mime
  }

  companion object {
    /** Deliberately generous: the point is to reject non-years, not to police history. */
    val PLAUSIBLE_YEARS = 1000..2999
    const val COVER_UNSUPPORTED = "cover_unsupported"
    const val AUDIO_UNTOUCHED = "audio_untouched"
    private const val SEARCH_TIMEOUT_MS = 20_000L
    private const val COVER_TIMEOUT_MS = 20_000L
  }
}
