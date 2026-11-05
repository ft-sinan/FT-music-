package echo.music.iad1tya.playback

import android.Manifest
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import echo.music.iad1tya.constants.DownloadQuality
import echo.music.iad1tya.db.InternalDatabase
import echo.music.iad1tya.db.MusicDatabase
import echo.music.iad1tya.db.entities.FormatEntity
import echo.music.iad1tya.db.entities.SongEntity
import echo.music.iad1tya.extensions.toMediaItem
import echo.music.iad1tya.models.MediaMetadata
import echo.music.iad1tya.playback.queues.ListQueue
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a real Android filesystem/MediaStore, using a generated tone and no network. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SharedDownloadStoreInstrumentedTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private lateinit var context: Context
  private lateinit var database: MusicDatabase
  private lateinit var provider: StandaloneDatabaseProvider
  private lateinit var cache: SimpleCache
  private lateinit var playerCache: SimpleCache
  private lateinit var store: SharedDownloadStore
  private lateinit var sources: DownloadSourceRegistry
  private lateinit var tracker: ActivePlaybackTracker
  private var util: DownloadUtil? = null
  private var serviceConnection: ServiceConnection? = null
  private var musicService: MusicService? = null
  private val id = "ft-test-${UUID.randomUUID()}"
  private val bytes by lazy { instrumentation.context.assets.open("test-tone.wav").use { it.readBytes() } }

  @Before fun setup() {
    context = instrumentation.targetContext
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
      instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
    val internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
    database = MusicDatabase(internal)
    provider = StandaloneDatabaseProvider(context)
    cache = SimpleCache(File(context.cacheDir, "download-test-$id"), NoOpCacheEvictor(), provider)
    playerCache = SimpleCache(File(context.cacheDir, "player-test-$id"), NoOpCacheEvictor(), provider)
    tracker = ActivePlaybackTracker()
    sources = DownloadSourceRegistry(context)
    store = SharedDownloadStore(context, cache, database, tracker, sources)
  }

  @After fun teardown() {
    serviceConnection?.let { connection ->
      instrumentation.runOnMainSync {
        musicService?.takeIf { it.isPlayerReady.value }?.player?.stop()
        context.unbindService(connection)
        context.stopService(Intent(context, MusicService::class.java))
      }
    }
    File(context.filesDir, "$id-local.wav").delete()
    util?.let { current ->
      current.release()
      instrumentation.runOnMainSync { current.downloadManager.release() }
    }
    runBlocking { store.delete(id) }
    sources.remove(id)
    cache.release()
    playerCache.release()
    database.close()
    File(context.cacheDir, "download-test-$id").deleteRecursively()
    File(context.cacheDir, "player-test-$id").deleteRecursively()
  }

  private fun seed(cachedBytes: Int = bytes.size): Download = runBlocking {
    withContext(Dispatchers.IO) {
      database.upsert(SongEntity(id = id, title = "FT test song", duration = 1, isDownloaded = true))
      database.upsert(FormatEntity(id, 1, "audio/wav", "pcm", 352800, 22050, bytes.size.toLong(), null, null, null))
      val hole = cache.startReadWrite(id, 0, cachedBytes.toLong())
      try {
        val sink = CacheDataSink(cache, C.LENGTH_UNSET.toLong())
        sink.open(DataSpec.Builder().setUri(Uri.parse("https://example.invalid/audio"))
          .setKey(id).setLength(cachedBytes.toLong()).build())
        sink.write(bytes, 0, cachedBytes)
        sink.close()
      } finally {
        cache.releaseHoleSpan(hole)
      }
    }
    val request = DownloadRequest.Builder(id, Uri.parse("https://example.invalid/audio"))
      .setCustomCacheKey(id).setData("FT test song".toByteArray()).build()
    Download(request, Download.STATE_COMPLETED, 1L, 1L, bytes.size.toLong(), Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE)
  }

  private fun read(uri: Uri): ByteArray = if (uri.scheme == "file") File(uri.path!!).readBytes()
    else context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

  private fun removeOutsideApp(uri: Uri) {
    if (uri.scheme == "file") assertTrue(File(uri.path!!).delete())
    else assertEquals(1, context.contentResolver.delete(uri, null, null))
  }

  private fun createUtil(download: Download): DownloadUtil {
    DefaultDownloadIndex(provider).putDownload(download)
    lateinit var created: DownloadUtil
    instrumentation.runOnMainSync { created = DownloadUtil(context, database, provider, cache, store, sources) }
    util = created
    return created
  }

  private fun bindMusicService(): MusicService {
    val connected = CountDownLatch(1)
    var service: MusicService? = null
    val connection = object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        service = (binder as MusicService.MusicBinder).service
        connected.countDown()
      }
      override fun onServiceDisconnected(name: ComponentName?) = Unit
    }
    var bound = false
    instrumentation.runOnMainSync {
      bound = context.bindService(Intent(context, MusicService::class.java), connection, Context.BIND_AUTO_CREATE)
    }
    assertTrue("MusicService could not be bound", bound)
    serviceConnection = connection
    assertTrue("MusicService did not connect", connected.await(30, TimeUnit.SECONDS))
    return service!!.also { musicService = it }
  }

  private fun awaitRemoval(downloadUtil: DownloadUtil) = runBlocking {
    val completed = withTimeoutOrNull(30_000) {
      while (downloadUtil.downloads.value.containsKey(id) || database.song(id).firstOrNull()?.song?.isDownloaded == true) {
        delay(100)
      }
      true
    } == true
    assertTrue("Download state did not clear: manager=${downloadUtil.downloads.value[id]?.state}, " +
      "database=${database.song(id).firstOrNull()?.song?.isDownloaded}", completed)
  }

  private suspend fun awaitSharedRemoval(downloadUtil: DownloadUtil) {
    val completed = withTimeoutOrNull(30_000) {
      while (store.hasRecord(id)) delay(100)
      true
    } == true
    assertTrue("Shared record did not clear: state=${store.state(id)}, " +
      "problem=${downloadUtil.sharedDownloadProblem.value}", completed)
  }

  @Test fun publishesExactBytesAndDeletesOwnedFile() = runBlocking {
    val download = seed()
    assertEquals(SharedDownloadStore.PublishResult.PUBLISHED, store.publish(download))
    val uri = store.recordedUri(id)!!
    assertEquals(SharedDownloadStore.FileState.PRESENT, store.state(id))
    assertEquals(uri, store.verifiedUriForSharing(id))
    assertArrayEquals(bytes, read(uri))
    val metadata = MediaMetadataRetriever()
    try {
      metadata.setDataSource(context, uri)
      assertTrue((metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) > 0)
    } finally { metadata.release() }
    assertTrue(store.delete(id))
    assertFalse(store.hasRecord(id))
    assertEquals(SharedDownloadStore.FileState.UNKNOWN, store.state(id))
  }

  @Test fun publicationUsesDownloadedSourceNotThePlayersLatestFormat() = runBlocking {
    val download = seed()
    sources.qualityFor(id, DownloadQuality.HIGH)
    assertTrue(sources.acceptSource(id, DownloadSourceIdentity(1, "audio/wav", bytes.size.toLong(), null), false))
    // Simulate MusicService resolving a different container for the same song.
    database.upsert(FormatEntity(id, 140, "audio/mp4", "mp4a.40.2", 128000, 44100, 999L, null, null, null))
    assertEquals(SharedDownloadStore.PublishResult.PUBLISHED, store.publish(download))
    val uri = store.recordedUri(id)!!
    assertArrayEquals(bytes, read(uri))
    if (uri.scheme == "file") assertTrue(uri.path!!.endsWith(".wav"))
    else {
      // Some MediaStore versions canonicalize audio/wav to audio/x-wav.
      assertTrue(context.contentResolver.getType(uri) in setOf("audio/wav", "audio/x-wav"))
      val name = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)!!.use {
        assertTrue(it.moveToFirst())
        it.getString(0)
      }
      assertTrue(name.endsWith(".wav"))
    }
  }

  @Test fun mismatchedSourceLengthCannotPublishOrDeletePrivateBytes() = runBlocking {
    val download = seed()
    sources.qualityFor(id, DownloadQuality.HIGH)
    assertTrue(sources.acceptSource(id, DownloadSourceIdentity(1, "audio/wav", bytes.size.toLong() + 1, null), false))
    assertEquals(SharedDownloadStore.PublishResult.UNAVAILABLE, store.publish(download))
    assertFalse(store.hasRecord(id))
    assertTrue(cache.getCachedSpans(id).isNotEmpty())
  }

  @Test fun sourceQualityAndFormatSurviveRegistryRecreation() {
    val first = DownloadSourceIdentity(251, "audio/webm", 1234L, "revision-1")
    assertEquals(DownloadQuality.HIGH, sources.qualityFor(id, DownloadQuality.YOUTUBE))
    assertTrue(sources.acceptSource(id, first, false))
    val afterRestart = DownloadSourceRegistry(context)
    assertEquals(DownloadQuality.HIGH, afterRestart.qualityFor(id, DownloadQuality.DATA_SAVER))
    assertTrue(afterRestart.acceptSource(id, first, true))
    assertFalse(afterRestart.acceptSource(id, first.copy(itag = 140, mimeType = "audio/mp4"), true))
    assertEquals(first, afterRestart.sourceFor(id))
    afterRestart.remove(id)
    assertEquals(DownloadQuality.DATA_SAVER, afterRestart.qualityFor(id, DownloadQuality.DATA_SAVER))
  }

  @Test fun orphanedIdentityDoesNotOverrideANewDownloadChoice() {
    sources.qualityFor(id, DownloadQuality.HIGH)
    sources.prune(emptySet())
    assertEquals(DownloadQuality.DATA_SAVER, sources.qualityFor(id, DownloadQuality.DATA_SAVER))
    sources.prune(setOf(id))
    assertEquals(DownloadQuality.DATA_SAVER, DownloadSourceRegistry(context).qualityFor(id, DownloadQuality.HIGH))
  }

  @Test fun legacyPartialIsPreservedAndRequiresExplicitRemoval() = runBlocking {
    seed(cachedBytes = bytes.size / 3)
    sources.qualityFor(id, DownloadQuality.DATA_SAVER)
    val newSource = DownloadSourceIdentity(140, "audio/mp4", bytes.size.toLong(), null)
    assertFalse(sources.acceptSource(id, newSource, hasCachedBytes = true))
    assertTrue(cache.getCachedSpans(id).isNotEmpty())
    assertEquals(null, sources.sourceFor(id))
  }

  @Test fun deletingInFilesRemovesTheDownloadOnRefresh() = runBlocking {
    val download = seed()
    assertEquals(SharedDownloadStore.PublishResult.PUBLISHED, store.publish(download))
    val downloadUtil = createUtil(download)
    removeOutsideApp(store.recordedUri(id)!!)
    downloadUtil.refreshSharedDownloads()
    awaitRemoval(downloadUtil)
    awaitSharedRemoval(downloadUtil)
    assertFalse(store.hasRecord(id))
  }

  @Test fun removingInTheAppDeletesTheSharedFile() = runBlocking {
    val download = seed()
    assertEquals(SharedDownloadStore.PublishResult.PUBLISHED, store.publish(download))
    val uri = store.recordedUri(id)!!
    val downloadUtil = createUtil(download)
    instrumentation.runOnMainSync { downloadUtil.downloadManager.removeDownload(id) }
    awaitRemoval(downloadUtil)
    awaitSharedRemoval(downloadUtil)
    if (uri.scheme == "file") assertFalse(File(uri.path!!).exists())
    else assertEquals(0, context.contentResolver.query(uri, arrayOf("_id"), null, null, null)?.use { it.count } ?: 0)
  }

  @Test fun playsChosenDeviceFileThenSharedDownloadWithoutNetwork() = runBlocking {
    val download = seed()
    assertEquals(SharedDownloadStore.PublishResult.PUBLISHED, store.publish(download))
    val file = File(context.filesDir, "$id-local.wav").apply { writeBytes(bytes) }
    val service = bindMusicService()
    withTimeout(30_000) { while (!service.isPlayerReady.value) delay(100) }
    val metadata = MediaMetadata(id = id, title = "FT test song", artists = emptyList(), duration = 1)
    val queue = ListQueue(items = listOf(metadata.toMediaItem()))
    instrumentation.runOnMainSync {
      service.selectLocalFile(id, Uri.fromFile(file))
      service.playQueue(queue)
    }
    withTimeout(30_000) {
      var loaded = false
      while (!loaded) {
        instrumentation.runOnMainSync {
          loaded = service.playbackOrigins.value[id] == PlaybackOrigin.DEVICE &&
            (service.player.playbackState == Player.STATE_READY || service.player.playbackState == Player.STATE_ENDED)
        }
        if (!loaded) delay(100)
      }
    }
    instrumentation.runOnMainSync {
      service.selectLocalFile(id, null)
      service.playQueue(queue)
    }
    withTimeout(30_000) {
      var loaded = false
      while (!loaded) {
        instrumentation.runOnMainSync {
          loaded = service.playbackOrigins.value[id] == PlaybackOrigin.FT_DOWNLOAD &&
            (service.player.playbackState == Player.STATE_READY || service.player.playbackState == Player.STATE_ENDED)
        }
        if (!loaded) delay(100)
      }
    }
  }

  @Test fun incompleteCacheIsNotPublishedOrDeleted() = runBlocking {
    val download = seed(cachedBytes = bytes.size / 3)
    withContext(Dispatchers.IO) {
      val start = bytes.size * 2 / 3
      val hole = cache.startReadWrite(id, start.toLong(), (bytes.size - start).toLong())
      try {
        val sink = CacheDataSink(cache, C.LENGTH_UNSET.toLong())
        sink.open(DataSpec.Builder().setUri(Uri.parse("https://example.invalid/audio"))
          .setKey(id).setPosition(start.toLong()).setLength((bytes.size - start).toLong()).build())
        sink.write(bytes, start, bytes.size - start)
        sink.close()
      } finally { cache.releaseHoleSpan(hole) }
    }
    assertEquals(SharedDownloadStore.PublishResult.UNAVAILABLE, store.publish(download))
    assertFalse(store.hasRecord(id))
    assertTrue(cache.getCachedSpans(id).isNotEmpty())
  }

  @Test fun existingFileWithTheSameNameIsNotOverwritten() = runBlocking {
    val download = seed()
    val name = SharedDownloadNames.fileName(id, "FT test song", "", "wav")
    val unrelated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/FT Music/")
      }
      context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!.also {
        context.contentResolver.openOutputStream(it)!!.use { output -> output.write(byteArrayOf(42)) }
      }
    } else {
      @Suppress("DEPRECATION")
      val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FT Music")
      directory.mkdirs()
      Uri.fromFile(File(directory, name).apply { writeBytes(byteArrayOf(42)) })
    }
    try {
      assertEquals(SharedDownloadStore.PublishResult.PUBLISHED, store.publish(download))
      assertArrayEquals(byteArrayOf(42), read(unrelated))
      assertArrayEquals(bytes, read(store.recordedUri(id)!!))
      assertTrue(store.delete(id))
      assertArrayEquals(byteArrayOf(42), read(unrelated))
    } finally { removeOutsideApp(unrelated) }
  }

  @Test fun legacyFileReplacedOutsideTheAppIsNeverDeleted() = runBlocking {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return@runBlocking
    val download = seed()
    assertEquals(SharedDownloadStore.PublishResult.PUBLISHED, store.publish(download))
    val uri = store.recordedUri(id)!!
    val file = File(uri.path!!)
    assertTrue(file.delete())
    file.writeBytes(byteArrayOf(42))
    try {
      assertEquals(SharedDownloadStore.FileState.MISSING, store.state(id))
      assertTrue(store.delete(id))
      assertArrayEquals(byteArrayOf(42), file.readBytes())
    } finally { file.delete() }
  }

  @Test fun mediaStoreMappingCannotDeleteAnotherOwnedFileInTheSameFolder() = runBlocking {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@runBlocking
    val download = seed()
    assertEquals(SharedDownloadStore.PublishResult.PUBLISHED, store.publish(download))
    val original = store.recordedUri(id)!!
    val records = context.getSharedPreferences("ft_shared_downloads", Context.MODE_PRIVATE)
    val originalRecord = records.getString(id, null)!!
    val values = ContentValues().apply {
      put(MediaStore.MediaColumns.DISPLAY_NAME, "$id-personal.wav")
      put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
      put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/FT Music/")
    }
    val unrelated = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
    context.contentResolver.openOutputStream(unrelated)!!.use { it.write(bytes) }
    try {
      val forgedRecord = unrelated.toString() + "\n" + originalRecord.substringAfter('\n')
      records.edit().putString(id, forgedRecord).commit()
      store.delete(id)
      assertArrayEquals(bytes, read(unrelated))
      assertArrayEquals(bytes, read(original))
    } finally {
      records.edit().putString(id, originalRecord).commit()
      store.delete(id)
      removeOutsideApp(unrelated)
    }
  }

  @Test fun editedMediaStoreDownloadIsNeverDeletedAsTheOriginal() = runBlocking {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@runBlocking
    val download = seed()
    assertEquals(SharedDownloadStore.PublishResult.PUBLISHED, store.publish(download))
    val uri = store.recordedUri(id)!!
    val edited = bytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
    context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(edited) }
    try {
      assertEquals(null, store.verifiedUriForSharing(id))
      store.delete(id)
      assertArrayEquals(edited, read(uri))
    } finally {
      context.getSharedPreferences("ft_shared_downloads", Context.MODE_PRIVATE).edit().remove(id).commit()
      context.getSharedPreferences("ft_shared_pending_removals", Context.MODE_PRIVATE).edit().remove(id).commit()
      removeOutsideApp(uri)
    }
  }

  @Test fun neverDeletesAnUnrelatedFileEvenIfItsUriIsRecorded() = runBlocking {
    val unrelated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "$id-unrelated.txt")
        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
      }
      context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!.also {
        context.contentResolver.openOutputStream(it)!!.use { output -> output.write(byteArrayOf(42)) }
      }
    } else {
      @Suppress("DEPRECATION")
      val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "$id-unrelated.txt")
      file.writeBytes(byteArrayOf(42))
      Uri.fromFile(file)
    }
    val records = context.getSharedPreferences("ft_shared_downloads", Context.MODE_PRIVATE)
    records.edit().putString(id, unrelated.toString()).commit()
    try {
      assertFalse(store.delete(id))
      assertArrayEquals(byteArrayOf(42), read(unrelated))
    } finally {
      records.edit().remove(id).commit()
      context.getSharedPreferences("ft_shared_pending_removals", Context.MODE_PRIVATE).edit().remove(id).commit()
      removeOutsideApp(unrelated)
    }
  }

  @Test fun doesNotRemoveAnActivePlaybackCacheUntilPlayersRelease() = runBlocking {
    val download = seed()
    tracker.retain(id)
    assertEquals(SharedDownloadStore.PublishResult.PUBLISHED, store.publish(download))
    assertTrue(cache.getCachedSpans(id).isNotEmpty())
    tracker.releaseAll()
    store.cleanupRedundantCache(id)
    assertTrue(cache.getCachedSpans(id).isEmpty())
  }
}
