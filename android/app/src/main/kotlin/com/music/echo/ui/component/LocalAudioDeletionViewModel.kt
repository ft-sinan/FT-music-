package echo.music.iad1tya.ui.component

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
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lives outside transient song menus and restores the selected row after recreation. */
@HiltViewModel
class LocalAudioDeletionViewModel @Inject constructor(
  private val savedState: SavedStateHandle,
  private val database: MusicDatabase,
) : ViewModel() {
  private val songIdKey = "localAudioDeletionSongId"
  var selectedSong by mutableStateOf<Song?>(null)
    private set

  init {
    savedState.get<String>(songIdKey)?.let { savedId ->
      viewModelScope.launch {
        val restored = try {
          withContext(Dispatchers.IO) { database.getSongById(savedId) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return@launch }
        if (savedState.get<String>(songIdKey) == savedId) {
          selectedSong = restored?.takeIf { it.song.isLocal }
          if (selectedSong == null) savedState.remove<String>(songIdKey)
        }
      }
    }
  }

  fun request(song: Song) {
    if (song.song.isLocal) {
      savedState[songIdKey] = song.id
      selectedSong = song
    }
  }

  fun clear() {
    savedState.remove<String>(songIdKey)
    selectedSong = null
  }
}

val LocalAudioDeletion = staticCompositionLocalOf<LocalAudioDeletionViewModel> {
  error("No local-audio deletion controller provided")
}
