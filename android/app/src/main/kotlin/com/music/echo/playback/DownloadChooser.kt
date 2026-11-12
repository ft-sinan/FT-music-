package echo.music.iad1tya.playback

import echo.music.iad1tya.constants.DownloadQuality
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** A song the user asked to download. The title is stored with the download for notifications. */
data class DownloadChooserItem(val id: String, val title: String)

/**
 * What the chooser needs from the download pipeline. Keeping it behind an interface means the
 * whole offer-then-confirm flow can be verified without Android, a database or YouTube access.
 */
interface DownloadQueue {
  /** Read-only discovery: must never queue a download or reserve a source. */
  suspend fun discover(id: String): DownloadOptionDiscoveryResult

  /** Locks the chosen format and queues [items]. Returns how many songs could not be queued. */
  fun enqueue(items: List<DownloadChooserItem>, quality: DownloadQuality): Int

  /** True when an unfinished download already locked its format. */
  fun hasLockedQuality(id: String): Boolean

  /** True when this song is already queued, downloading or downloaded. */
  fun isActiveDownload(id: String): Boolean

  /** True for a device file. There are no YouTube formats to offer for those. */
  fun isLocalMedia(id: String): Boolean

  /** The saved download-quality preference, used when there is nothing to choose. */
  fun savedQuality(): DownloadQuality
}

/** Why no format could be offered. Both are reported honestly rather than silently queueing. */
enum class DownloadChooserFailure { NO_AUDIO_FORMAT, LOOKUP_FAILED }

sealed interface DownloadChooserStage {
  data object Loading : DownloadChooserStage

  data class Ready(
    val options: List<DownloadOption>,
    val unavailable: Set<DownloadQuality>,
    /** Sizes come from this song; a batch download cannot know every song's size up front. */
    val sampleTitle: String,
    val itemCount: Int,
    /** Items whose format is already locked by an unfinished download. */
    val lockedCount: Int,
  ) : DownloadChooserStage

  data class Failed(val reason: DownloadChooserFailure) : DownloadChooserStage
}

data class DownloadChooserState(
  val items: List<DownloadChooserItem>,
  val stage: DownloadChooserStage,
)

/**
 * One chooser for every download entry point, so a bulk download asks once instead of once per
 * song. Discovery is read-only: opening the chooser never queues a download, changes the saved
 * quality preference, or reserves a source. The chosen format is locked per download when the
 * user confirms.
 */
@Singleton
class DownloadChooserController @Inject constructor(private val queue: DownloadQueue) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  val state = MutableStateFlow<DownloadChooserState?>(null)
  private var job: Job? = null

  /**
   * Starts a chooser for [items], ignoring songs already queued, downloading or completed.
   *
   * A device file has no YouTube formats to choose between, so it is copied straight away exactly
   * as the download button did before, instead of offering a choice that cannot exist.
   */
  fun request(items: List<DownloadChooserItem>) {
    val pending = items
      .filter { it.id.isNotBlank() }
      .distinctBy { it.id }
      .filterNot { queue.isActiveDownload(it.id) }
    if (pending.isEmpty()) return
    val (local, online) = pending.partition { queue.isLocalMedia(it.id) }
    if (local.isNotEmpty()) queue.enqueue(local, queue.savedQuality())
    if (online.isEmpty()) return
    job?.cancel()
    state.value = DownloadChooserState(online, DownloadChooserStage.Loading)
    job = scope.launch { load(online) }
  }

  fun request(item: DownloadChooserItem) = request(listOf(item))

  fun choose(quality: DownloadQuality) {
    val current = state.value ?: return
    dismiss()
    queue.enqueue(current.items, quality)
  }

  fun retry() {
    val items = state.value?.items ?: return
    job?.cancel()
    state.value = state.value?.copy(stage = DownloadChooserStage.Loading)
    job = scope.launch { load(items) }
  }

  fun dismiss() {
    job?.cancel()
    job = null
    state.value = null
  }

  private suspend fun load(items: List<DownloadChooserItem>) {
    val stage =
      try {
        val result = queue.discover(items.first().id)
        if (result.options.isEmpty()) {
          DownloadChooserStage.Failed(DownloadChooserFailure.NO_AUDIO_FORMAT)
        } else {
          DownloadChooserStage.Ready(
            options = result.options,
            unavailable = result.unavailable,
            sampleTitle = items.first().title,
            itemCount = items.size,
            lockedCount = items.count { queue.hasLockedQuality(it.id) },
          )
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        DownloadChooserStage.Failed(DownloadChooserFailure.LOOKUP_FAILED)
      }
    // A newer request may already be showing; never overwrite it with a stale result.
    val current = state.value
    if (current != null && current.items == items) state.value = current.copy(stage = stage)
  }
}
