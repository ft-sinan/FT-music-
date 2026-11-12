package echo.music.iad1tya.ui.component

import android.content.IntentSender
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import echo.music.iad1tya.db.MusicDatabase
import echo.music.iad1tya.db.entities.Song
import echo.music.iad1tya.localmedia.AudioTagEdit
import echo.music.iad1tya.localmedia.AudioTagEditFailure
import echo.music.iad1tya.localmedia.AudioTagEditResult
import echo.music.iad1tya.localmedia.AudioTagWriter
import echo.music.iad1tya.localmedia.LocalSongInfoEditor
import echo.music.iad1tya.localmedia.TagSearchResult
import echo.music.iad1tya.localmedia.TagSearchSource
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the Edit info sheet for one local song: what the file says today, what the user wants it to
 * say, and the outcome of writing it. Lives outside transient menus and survives recreation.
 */
@HiltViewModel
class LocalSongInfoViewModel
@Inject
constructor(
  private val savedState: SavedStateHandle,
  private val database: MusicDatabase,
  private val writer: AudioTagWriter,
  searchSource: TagSearchSource,
) : ViewModel() {
  /** What happened after Apply, so the sheet reports it instead of closing silently. */
  sealed interface Outcome {
    data object Saved : Outcome

    /** Android needs the user's own confirmation for this file; [sender] starts that prompt. */
    data class PermissionNeeded(val sender: IntentSender?) : Outcome

    data class Problem(val failure: AudioTagEditFailure, val detail: String?) : Outcome
  }

  private val songIdKey = "localSongInfoSongId"
  private val editor = LocalSongInfoEditor(searchSource::search, searchSource::cover)
  private var pendingEdit: AudioTagEdit? = null

  var selectedSong by mutableStateOf<Song?>(null)
    private set
  var target by mutableStateOf<AudioTagWriter.Target?>(null)
    private set
  var snapshot by mutableStateOf(editor.snapshot())
    private set
  var working by mutableStateOf(false)
    private set
  var outcome by mutableStateOf<Outcome?>(null)
    private set

  init {
    savedState.get<String>(songIdKey)?.let { savedId ->
      viewModelScope.launch {
        val restored = try {
          withContext(Dispatchers.IO) { database.getSongById(savedId) }
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          return@launch
        }
        if (savedState.get<String>(songIdKey) == savedId) {
          selectedSong = restored?.takeIf { it.song.isLocal }
          if (selectedSong == null) savedState.remove<String>(songIdKey) else open(savedId)
        }
      }
    }
  }

  fun request(song: Song) {
    if (!song.song.isLocal) return
    savedState[songIdKey] = song.id
    selectedSong = song
    open(song.id)
  }

  fun clear() {
    savedState.remove<String>(songIdKey)
    selectedSong = null
    target = null
    outcome = null
    working = false
    pendingEdit = null
    editor.clearSearch()
    publish()
  }

  private fun open(songId: String) {
    outcome = null
    working = true
    viewModelScope.launch {
      val inspected = try {
        writer.inspect(songId)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        null
      }
      target = inspected
      editor.load(
        newContainer = inspected?.container,
        newTitle = inspected?.title,
        newArtists = inspected?.artist,
        newAlbum = inspected?.album,
        newYear = inspected?.year,
      )
      publish()
      working = false
    }
  }

  fun setTitle(value: String) = mutate { editor.setTitle(value) }

  fun setArtists(value: String) = mutate { editor.setArtists(value) }

  fun setAlbum(value: String) = mutate { editor.setAlbum(value) }

  fun setYear(value: String) = mutate { editor.setYear(value) }

  fun setCoverFromFile(bytes: ByteArray, mimeType: String?) = mutate {
    editor.setCoverFromFile(bytes, mimeType)
  }

  fun clearCover() = mutate { editor.clearCover() }

  fun clearSearch() = mutate { editor.clearSearch() }

  fun search(query: String) {
    working = true
    viewModelScope.launch {
      try {
        editor.search(query)
      } catch (e: CancellationException) {
        throw e
      } finally {
        publish()
        working = false
      }
    }
  }

  fun acceptResult(result: TagSearchResult) {
    working = true
    viewModelScope.launch {
      try {
        editor.accept(result)
      } catch (e: CancellationException) {
        throw e
      } finally {
        publish()
        working = false
      }
    }
  }

  /** Writes the tags. Android 10 and newer may answer with its own permission prompt first. */
  fun apply() {
    val song = selectedSong ?: return
    if (!editor.canApply()) {
      outcome = Outcome.Problem(AudioTagEditFailure.WRITE_FAILED, "title and artists are required")
      publish()
      return
    }
    pendingEdit = editor.edit()
    write(song.id, pendingEdit!!)
  }

  /** Called once the user has granted Android's per-file write permission. */
  fun retryAfterPermission() {
    val song = selectedSong ?: return
    val edit = pendingEdit ?: return
    outcome = null
    write(song.id, edit)
  }

  fun permissionRefused() {
    outcome = Outcome.Problem(AudioTagEditFailure.PERMISSION_DENIED, null)
    publish()
  }

  fun clearOutcome() {
    outcome = null
    publish()
  }

  private fun write(songId: String, edit: AudioTagEdit) {
    working = true
    viewModelScope.launch {
      val outcomeResult = try {
        writer.write(songId, edit)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        AudioTagWriter.TagWriteOutcome(
          AudioTagEditResult.Failed(AudioTagEditFailure.WRITE_FAILED, null)
        )
      }
      outcome = when (val written = outcomeResult.result) {
        is AudioTagEditResult.Written -> {
          pendingEdit = null
          Outcome.Saved
        }
        is AudioTagEditResult.Failed ->
          when (written.reason) {
            AudioTagEditFailure.PERMISSION_DENIED ->
              Outcome.PermissionNeeded(outcomeResult.permissionSender)
            else -> Outcome.Problem(written.reason, written.detail)
          }
      }
      publish()
      working = false
    }
  }

  private fun mutate(block: () -> Unit) {
    block()
    publish()
  }

  private fun publish() {
    snapshot = editor.snapshot()
  }
}

val LocalSongInfo = staticCompositionLocalOf<LocalSongInfoViewModel> {
  error("No local song info editor provided")
}
