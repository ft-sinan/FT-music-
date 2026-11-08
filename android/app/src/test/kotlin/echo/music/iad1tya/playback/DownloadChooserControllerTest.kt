package echo.music.iad1tya.playback

import echo.music.iad1tya.constants.DownloadQuality
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The chooser must offer what the resolver really returned and must not queue anything until the
 * user confirms. A batch download asks once, and a failed lookup is reported instead of silently
 * falling back to a saved preference.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadChooserControllerTest {
  private class FakeQueue(
    var options: List<DownloadOption> = emptyList(),
    var unavailable: Set<DownloadQuality> = emptySet(),
    var failure: Exception? = null,
    var gate: CompletableDeferred<Unit>? = null,
  ) : DownloadQueue {
    val discovered = mutableListOf<String>()
    val enqueued = mutableListOf<Pair<List<DownloadChooserItem>, DownloadQuality>>()
    val locked = mutableSetOf<String>()
    val active = mutableSetOf<String>()
    val local = mutableSetOf<String>()
    var saved: DownloadQuality = DownloadQuality.HIGH

    override suspend fun discover(id: String): DownloadOptionDiscoveryResult {
      discovered += id
      gate?.await()
      failure?.let { throw it }
      return DownloadOptionDiscoveryResult(options, unavailable)
    }

    override fun enqueue(items: List<DownloadChooserItem>, quality: DownloadQuality): Int {
      enqueued += items to quality
      return 0
    }

    override fun hasLockedQuality(id: String): Boolean = id in locked

    override fun isActiveDownload(id: String): Boolean = id in active

    override fun isLocalMedia(id: String): Boolean = id in local

    override fun savedQuality(): DownloadQuality = saved
  }

  private val high =
    DownloadOption(
      quality = DownloadQuality.HIGH,
      source = DownloadSourceIdentity(251, "audio/webm", 21_000_000L, "9"),
      codecs = "opus",
      bitrate = 160_000,
      sampleRate = 48_000,
    )
  private val saver =
    DownloadOption(
      quality = DownloadQuality.DATA_SAVER,
      source = DownloadSourceIdentity(140, "audio/mp4", 2_000_000L, "9"),
      codecs = "mp4a.40.2",
      bitrate = 48_000,
      sampleRate = 44_100,
    )

  @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }

  @After fun tearDown() { Dispatchers.resetMain() }

  private fun ready(state: DownloadChooserState?): DownloadChooserStage.Ready {
    val stage = state?.stage
    assertTrue("expected Ready but was $stage", stage is DownloadChooserStage.Ready)
    return stage as DownloadChooserStage.Ready
  }

  @Test fun openingTheChooserOffersFormatsWithoutQueueingAnything() {
    val queue = FakeQueue(options = listOf(high, saver))
    val controller = DownloadChooserController(queue)

    controller.request(DownloadChooserItem("song1", "First Song"))

    val stage = ready(controller.state.value)
    assertEquals(listOf(high, saver), stage.options)
    assertTrue(stage.options.first().isRecommended)
    assertEquals(1, stage.itemCount)
    assertEquals(0, stage.lockedCount)
    assertEquals(listOf("song1"), queue.discovered)
    assertTrue("Discovery must not queue a download", queue.enqueued.isEmpty())
    assertTrue("Discovery must not change the saved quality", queue.locked.isEmpty())
  }

  @Test fun oneChooserCoversAWholeBatchAndConfirmingQueuesEverySong() {
    val queue = FakeQueue(options = listOf(high, saver))
    val controller = DownloadChooserController(queue)
    val items =
      listOf(
        DownloadChooserItem("a", "Song A"),
        DownloadChooserItem("b", "Song B"),
        DownloadChooserItem("c", "Song C"),
        DownloadChooserItem("a", "Song A"), // a duplicate id must not be downloaded twice
      )

    controller.request(items)

    assertEquals(3, ready(controller.state.value).itemCount)
    assertEquals("A batch must ask once", listOf("a"), queue.discovered)

    controller.choose(DownloadQuality.DATA_SAVER)

    assertEquals(
      listOf(listOf(items[0], items[1], items[2]) to DownloadQuality.DATA_SAVER),
      queue.enqueued,
    )
    assertNull(controller.state.value)
  }

  @Test fun songsAlreadyDownloadingOrFinishedAreSkipped() {
    val queue = FakeQueue(options = listOf(high)).apply { active += "done" }
    val controller = DownloadChooserController(queue)

    controller.request(listOf(DownloadChooserItem("done", "Already saved")))
    assertNull("Nothing to offer when every song is already handled", controller.state.value)
    assertTrue(queue.discovered.isEmpty())

    controller.request(listOf(DownloadChooserItem("done", "Already saved"), DownloadChooserItem("new", "New")))
    assertEquals(listOf("new"), controller.state.value?.items?.map { it.id })
    assertEquals(1, ready(controller.state.value).itemCount)
  }

  @Test fun aSongWithNoAudioFormatIsReportedAndNothingIsQueued() {
    val queue = FakeQueue(options = emptyList(), unavailable = setOf(DownloadQuality.HIGH, DownloadQuality.DATA_SAVER))
    val controller = DownloadChooserController(queue)

    controller.request(DownloadChooserItem("song1", "First Song"))

    val stage = controller.state.value?.stage
    assertEquals(
      DownloadChooserStage.Failed(DownloadChooserFailure.NO_AUDIO_FORMAT),
      stage,
    )
    assertTrue(queue.enqueued.isEmpty())
  }

  @Test fun aFailedLookupIsReportedAndCanBeRetried() {
    val queue = FakeQueue(failure = IOException("network down"))
    val controller = DownloadChooserController(queue)

    controller.request(DownloadChooserItem("song1", "First Song"))
    assertEquals(
      DownloadChooserStage.Failed(DownloadChooserFailure.LOOKUP_FAILED),
      controller.state.value?.stage,
    )
    assertTrue(queue.enqueued.isEmpty())

    queue.failure = null
    queue.options = listOf(high)
    controller.retry()

    assertEquals(listOf(high), ready(controller.state.value).options)
    assertEquals(listOf("song1", "song1"), queue.discovered)
  }

  @Test fun unfinishedDownloadsAreCountedSoTheDialogCanSayTheFormatStays() {
    val queue =
      FakeQueue(options = listOf(high, saver)).apply { locked += "partial" }
    val controller = DownloadChooserController(queue)

    controller.request(
      listOf(
        DownloadChooserItem("partial", "Half finished"),
        DownloadChooserItem("fresh", "Fresh song"),
      )
    )

    assertEquals(1, ready(controller.state.value).lockedCount)
  }

  @Test fun unavailableFormatsArePassedThroughToTheDialog() {
    val queue = FakeQueue(options = listOf(high), unavailable = setOf(DownloadQuality.DATA_SAVER))
    val controller = DownloadChooserController(queue)

    controller.request(DownloadChooserItem("song1", "First Song"))

    assertEquals(setOf(DownloadQuality.DATA_SAVER), ready(controller.state.value).unavailable)
    assertEquals("First Song", ready(controller.state.value).sampleTitle)
  }

  @Test fun aNewerRequestWinsAndASlowOlderLookupIsIgnored() {
    val gate = CompletableDeferred<Unit>()
    val queue = FakeQueue(options = listOf(high), gate = gate)
    val controller = DownloadChooserController(queue)

    controller.request(DownloadChooserItem("slow", "Slow Song"))
    assertEquals(DownloadChooserStage.Loading, controller.state.value?.stage)

    queue.gate = null
    queue.options = listOf(saver)
    controller.request(DownloadChooserItem("fast", "Fast Song"))

    assertEquals(listOf("fast"), controller.state.value?.items?.map { it.id })
    assertEquals(listOf(saver), ready(controller.state.value).options)

    // The first lookup finishing late must not replace the newer chooser.
    gate.complete(Unit)
    assertEquals(listOf("fast"), controller.state.value?.items?.map { it.id })
    assertEquals(listOf(saver), ready(controller.state.value).options)
  }

  @Test fun dismissingTheChooserQueuesNothing() {
    val queue = FakeQueue(options = listOf(high, saver))
    val controller = DownloadChooserController(queue)

    controller.request(DownloadChooserItem("song1", "First Song"))
    controller.dismiss()

    assertNull(controller.state.value)
    assertTrue(queue.enqueued.isEmpty())
  }

  @Test fun aDeviceFileIsCopiedWithoutOfferingFormatsThatDoNotExist() {
    val queue = FakeQueue(options = listOf(high, saver)).apply {
      local += "content://media/external/audio/media/42"
      saved = DownloadQuality.DATA_SAVER
    }
    val controller = DownloadChooserController(queue)

    controller.request(DownloadChooserItem("content://media/external/audio/media/42", "Device song"))

    assertNull("A device file has no YouTube formats to choose from", controller.state.value)
    assertTrue("A device file must not be sent to the YouTube resolver", queue.discovered.isEmpty())
    assertEquals(
      listOf(
        listOf(DownloadChooserItem("content://media/external/audio/media/42", "Device song")) to
          DownloadQuality.DATA_SAVER
      ),
      queue.enqueued,
    )
  }

  @Test fun aMixedBatchCopiesDeviceFilesAndOffersAChooserForTheRest() {
    val queue = FakeQueue(options = listOf(high, saver)).apply {
      local += "file:///storage/emulated/0/Music/local.flac"
      saved = DownloadQuality.HIGH
    }
    val controller = DownloadChooserController(queue)
    val device = DownloadChooserItem("file:///storage/emulated/0/Music/local.flac", "Device song")
    val online = DownloadChooserItem("onlineId", "YouTube song")

    controller.request(listOf(device, online))

    // The device file keeps the old one-tap behavior; the online song still gets a choice.
    assertEquals(listOf(listOf(device) to DownloadQuality.HIGH), queue.enqueued)
    assertEquals(listOf(online), controller.state.value?.items)
    assertEquals(listOf("onlineId"), queue.discovered)
    assertEquals(listOf(high, saver), ready(controller.state.value).options)

    controller.choose(DownloadQuality.DATA_SAVER)
    assertEquals(listOf(device) to DownloadQuality.HIGH, queue.enqueued.first())
    assertEquals(listOf(online) to DownloadQuality.DATA_SAVER, queue.enqueued.last())
  }

  @Test fun blankIdsAreIgnored() {
    val queue = FakeQueue(options = listOf(high))
    val controller = DownloadChooserController(queue)

    controller.request(listOf(DownloadChooserItem("", "No id"), DownloadChooserItem("song1", "First")))

    assertEquals(listOf("song1"), controller.state.value?.items?.map { it.id })
  }
}
