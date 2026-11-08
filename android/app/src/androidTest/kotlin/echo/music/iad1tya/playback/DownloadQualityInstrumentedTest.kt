package echo.music.iad1tya.playback

import android.Manifest
import android.content.Context
import android.os.Build
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.music.innertube.models.response.PlayerResponse
import echo.music.iad1tya.constants.AudioQuality
import echo.music.iad1tya.constants.DownloadOnWifiOnlyKey
import echo.music.iad1tya.constants.DownloadQuality
import echo.music.iad1tya.constants.DownloadQualityKey
import echo.music.iad1tya.constants.DownloadWithMetadataKey
import echo.music.iad1tya.db.InternalDatabase
import echo.music.iad1tya.db.MusicDatabase
import echo.music.iad1tya.utils.YTPlayerUtils.PlaybackData
import echo.music.iad1tya.utils.dataStore
import echo.music.iad1tya.utils.findShareableAudio
import echo.music.iad1tya.utils.audioShareIntent
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.UUID
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual download resolver/data source with a loopback server, never YouTube. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class DownloadQualityInstrumentedTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context: Context get() = instrumentation.targetContext
  private val id = "ft-quality-${UUID.randomUUID()}"
  private val high by lazy { instrumentation.context.assets.open("test-tone.wav").use { it.readBytes() } }
  private val small by lazy { high.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() } }
  private lateinit var database: MusicDatabase
  private lateinit var provider: StandaloneDatabaseProvider
  private lateinit var downloadCache: SimpleCache
  private lateinit var playerCache: SimpleCache
  private lateinit var sources: DownloadSourceRegistry
  private lateinit var store: SharedDownloadStore
  private lateinit var tracker: ActivePlaybackTracker
  private var util: DownloadUtil? = null
  private var oldQuality: String? = null
  private var oldMetadata: Boolean? = null
  private var oldWifiOnly: Boolean? = null

  @Before fun setup() = runBlocking {
    val settings = context.dataStore.data.first()
    oldQuality = settings[DownloadQualityKey]
    oldMetadata = settings[DownloadWithMetadataKey]
    oldWifiOnly = settings[DownloadOnWifiOnlyKey]
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
      instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
    context.dataStore.edit { it[DownloadWithMetadataKey] = false }
    val internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
    database = MusicDatabase(internal)
    provider = StandaloneDatabaseProvider(context)
    downloadCache = SimpleCache(File(context.cacheDir, "quality-download-$id"), NoOpCacheEvictor(), provider)
    playerCache = SimpleCache(File(context.cacheDir, "quality-player-$id"), NoOpCacheEvictor(), provider)
    sources = DownloadSourceRegistry(context)
    tracker = ActivePlaybackTracker()
    store = SharedDownloadStore(context, downloadCache, database, tracker, sources)
  }

  @After fun teardown() = runBlocking {
    util?.let { current ->
      if (current.downloads.value.containsKey(id)) {
        instrumentation.runOnMainSync { current.downloadManager.removeDownload(id) }
        withTimeoutOrNull(10_000) { while (current.downloads.value.containsKey(id)) delay(50) }
      }
      current.release()
      instrumentation.runOnMainSync { current.downloadManager.release() }
    }
    store.delete(id)
    sources.remove(id)
    downloadCache.release()
    playerCache.release()
    database.close()
    File(context.cacheDir, "quality-download-$id").deleteRecursively()
    File(context.cacheDir, "quality-player-$id").deleteRecursively()
    context.dataStore.edit {
      if (oldQuality == null) it.remove(DownloadQualityKey) else it[DownloadQualityKey] = oldQuality!!
      if (oldMetadata == null) it.remove(DownloadWithMetadataKey) else it[DownloadWithMetadataKey] = oldMetadata!!
      if (oldWifiOnly == null) it.remove(DownloadOnWifiOnlyKey) else it[DownloadOnWifiOnlyKey] = oldWifiOnly!!
    }
    Unit
  }

  @Test fun sharingRequiresPlayableContentAndGrantsReadAccess() = runBlocking {
    val playable = File(context.cacheDir, "share-test-$id.wav")
    val broken = File(context.cacheDir, "share-broken-$id.wav")
    try {
      playable.writeBytes(high)
      broken.writeText("not an audio file")
      val audio = findShareableAudio(context, store, Uri.fromFile(playable).toString())
      assertTrue(audio != null)
      assertEquals("content", audio!!.uri.scheme)
      val intent = audioShareIntent(context, audio)
      assertTrue(intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
      assertEquals(audio.uri, intent.clipData?.getItemAt(0)?.uri)
      assertEquals(null, findShareableAudio(context, store, Uri.fromFile(broken).toString()))
      assertEquals(null, findShareableAudio(context, store, "not-a-published-download"))
    } finally {
      playable.delete()
      broken.delete()
    }
  }

  private fun createUtil(): DownloadUtil {
    lateinit var created: DownloadUtil
    instrumentation.runOnMainSync {
      created = DownloadUtil(context, database, provider, downloadCache, store, sources)
    }
    util = created
    return created
  }

  private fun cacheBytes(cache: SimpleCache, bytes: ByteArray) {
    val hole = cache.startReadWrite(id, 0, bytes.size.toLong())
    try {
      val sink = CacheDataSink(cache, C.LENGTH_UNSET.toLong())
      sink.open(DataSpec.Builder().setUri("https://example.invalid/old").setKey(id).setLength(bytes.size.toLong()).build())
      sink.write(bytes, 0, bytes.size)
      sink.close()
    } finally { cache.releaseHoleSpan(hole) }
  }

  private fun playback(url: String, itag: Int, size: Int) = PlaybackData(
    audioConfig = null,
    videoDetails = null,
    playbackTracking = null,
    format = PlayerResponse.StreamingData.Format(
      itag = itag, url = url, mimeType = "audio/wav", bitrate = 100_000,
      width = null, height = null, contentLength = size.toLong(), quality = "test",
      fps = null, qualityLabel = null, averageBitrate = null, audioQuality = null,
      approxDurationMs = null, audioSampleRate = null, audioChannels = null,
      loudnessDb = null, lastModified = null, signatureCipher = null, cipher = null, audioTrack = null,
    ),
    streamUrl = url,
    // Force a new resolution on each open, as after expiry or an app restart.
    streamExpiresInSeconds = 0,
    headers = emptyMap(),
  )

  private fun readAll(factory: DataSource.Factory): ByteArray {
    val source = factory.createDataSource()
    return try {
      source.open(DataSpec.Builder().setUri(Uri.parse(id)).setKey(id).build())
      val output = java.io.ByteArrayOutputStream()
      val buffer = ByteArray(8192)
      while (true) {
        val count = source.read(buffer, 0, buffer.size)
        if (count == C.RESULT_END_OF_INPUT) break
        output.write(buffer, 0, count)
      }
      output.toByteArray()
    } finally { source.close() }
  }

  @Test fun downloadOptionDiscoveryReportsRealFormatsWithoutStartingADownload() = runBlocking {
    val downloadUtil = createUtil()
    val highBytes = 20 * 1024 * 1024 + 123 // Files above 15 MB are valid choices.
    val saverBytes = 2 * 1024 * 1024
    val qualities = mutableListOf<AudioQuality>()
    downloadUtil.resolveDownloadSource = { requestedId, quality ->
      assertEquals(id, requestedId)
      qualities += quality
      val highQuality = quality == AudioQuality.HIGH
      val size = if (highQuality) highBytes else saverBytes
      val itag = if (highQuality) 251 else 140
      val mime = if (highQuality) "audio/webm; codecs=\"opus\"" else "audio/mp4; codecs=\"mp4a.40.2\""
      playback("https://example.invalid/audio?itag=$itag&clen=$size&lmt=1", itag, size).let {
        it.copy(format = it.format.copy(mimeType = mime, bitrate = if (highQuality) 160_000 else 48_000))
      }
    }

    val result = downloadUtil.inspectDownloadOptions(id)
    assertEquals(listOf(AudioQuality.HIGH, AudioQuality.LOW), qualities)
    assertEquals(listOf(DownloadQuality.HIGH, DownloadQuality.DATA_SAVER), result.options.map { it.quality })
    assertTrue(result.unavailable.isEmpty())
    assertTrue(result.options.first().isRecommended)
    assertEquals("audio/webm", result.options.first().source.mimeType)
    assertEquals("opus", result.options.first().codecs)
    assertEquals(160_000, result.options.first().bitrate)
    assertEquals(highBytes.toLong(), result.options.first().sizeBytes)
    assertEquals("audio/mp4", result.options.last().source.mimeType)
    assertEquals("mp4a.40.2", result.options.last().codecs)
    assertEquals(saverBytes.toLong(), result.options.last().sizeBytes)
    assertTrue(id !in downloadUtil.downloads.value)
    assertTrue(downloadCache.getCachedSpans(id).isEmpty())
    assertTrue(!sources.hasRecord(id))
  }

  @Test fun downloadOptionDiscoveryKeepsUnknownValuesAndOtherAvailableChoices() = runBlocking {
    val downloadUtil = createUtil()
    downloadUtil.resolveDownloadSource = { _, quality ->
      if (quality == AudioQuality.LOW) throw IOException("No low-quality source")
      playback("https://example.invalid/audio?itag=251", 251, 1).let {
        it.copy(format = it.format.copy(mimeType = "audio/webm", contentLength = null, bitrate = 0))
      }
    }
    val result = downloadUtil.inspectDownloadOptions(id)
    assertEquals(1, result.options.size)
    assertEquals(DownloadQuality.HIGH, result.options.single().quality)
    assertEquals(null, result.options.single().sizeBytes)
    assertEquals(null, result.options.single().bitrate)
    assertEquals(null, result.options.single().codecs)
    assertEquals(setOf(DownloadQuality.DATA_SAVER), result.unavailable)
    assertTrue(id !in downloadUtil.downloads.value)
    assertTrue(!sources.hasRecord(id))
  }

  @Test fun downloadOptionDiscoveryDeduplicatesOnlyKnownMatchingFormats() = runBlocking {
    val downloadUtil = createUtil()
    downloadUtil.resolveDownloadSource = { _, _ ->
      playback("https://example.invalid/audio?itag=251&clen=4096&lmt=1", 251, 4096)
    }
    val result = downloadUtil.inspectDownloadOptions(id)
    assertEquals(1, result.options.size)
    assertEquals(DownloadQuality.HIGH, result.options.single().quality)
    assertTrue(result.unavailable.isEmpty())
    assertTrue(!sources.hasRecord(id))
  }

  @Test fun downloadOptionDiscoveryDoesNotOfferVideoAsAudio() = runBlocking {
    val downloadUtil = createUtil()
    downloadUtil.resolveDownloadSource = { _, quality ->
      playback("https://example.invalid/audio?itag=251&clen=4096", 251, 4096).let {
        if (quality == AudioQuality.HIGH) it.copy(format = it.format.copy(mimeType = "video/mp4")) else it
      }
    }
    val result = downloadUtil.inspectDownloadOptions(id)
    assertEquals(listOf(DownloadQuality.DATA_SAVER), result.options.map { it.quality })
    assertEquals(setOf(DownloadQuality.HIGH), result.unavailable)
    assertTrue(!result.options.single().isRecommended)
  }

  @Test fun downloadOptionDiscoveryPropagatesCancellation() = runBlocking {
    val downloadUtil = createUtil()
    downloadUtil.resolveDownloadSource = { _, _ -> throw kotlinx.coroutines.CancellationException("Canceled") }
    try {
      downloadUtil.inspectDownloadOptions(id)
      fail("Canceling discovery must not be reported as a missing format")
    } catch (_: kotlinx.coroutines.CancellationException) { /* expected */ }
    assertTrue(id !in downloadUtil.downloads.value)
    assertTrue(!sources.hasRecord(id))
  }

  @Test fun chooserChoiceIsLockedBeforeTheRequestIsQueued() = runBlocking {
    val downloadUtil = createUtil()
    val second = "$id-2"
    val sent = mutableListOf<DownloadRequest>()
    downloadUtil.sendAddDownload = { sent += it }
    try {
      context.dataStore.edit { it[DownloadQualityKey] = DownloadQuality.HIGH.name }
      assertTrue(!downloadUtil.hasLockedQuality(id))
      val items =
        listOf(DownloadChooserItem(id, "FT Title"), DownloadChooserItem(second, "Second Title"))
      // The chooser locks what the user picked, not what the saved preference happens to say.
      assertEquals(0, downloadUtil.enqueue(items, DownloadQuality.DATA_SAVER))
      assertEquals(listOf(id, second), sent.map { it.id })
      assertEquals(listOf(id, second), sent.map { it.cacheKey })
      assertEquals(listOf("FT Title", "Second Title"), sent.map { String(it.data!!) })
      assertEquals(DownloadQuality.DATA_SAVER, sources.qualityFor(id, DownloadQuality.HIGH))
      assertTrue(downloadUtil.hasLockedQuality(id))
      // A queue failure is reported rather than leaving the user guessing.
      sent.clear()
      downloadUtil.sendAddDownload = { throw IllegalStateException("service unavailable") }
      assertEquals(
        1,
        downloadUtil.enqueue(listOf(DownloadChooserItem(second, "Second Title")), DownloadQuality.HIGH),
      )
      assertTrue(sent.isEmpty())
      assertEquals(
        context.getString(echo.music.iad1tya.R.string.ft_download_queue_failed),
        downloadUtil.downloadQueueProblem.value,
      )
    } finally {
      sources.remove(second)
    }
  }

  @Test fun sourceFollowsPreferenceAndNeverUsesUnqualifiedStreamingCache() = runBlocking {
    cacheBytes(playerCache, ByteArray(high.size) { 0x55 })
    val qualities = Collections.synchronizedList(mutableListOf<AudioQuality>())
    AudioServer(high, small).use { server ->
      val downloadUtil = createUtil()
      downloadUtil.resolveDownloadSource = { _, quality ->
        qualities.add(quality)
        val useSmall = quality == AudioQuality.LOW
        val bytes = if (useSmall) small else high
        val itag = if (useSmall) 140 else 251
        playback(server.url(if (useSmall) "small" else "high", itag, bytes.size), itag, bytes.size)
      }
      context.dataStore.edit { it[DownloadQualityKey] = DownloadQuality.HIGH.name }
      assertArrayEquals(high, readAll(downloadUtil.dataSourceFactory))
      context.dataStore.edit { it[DownloadQualityKey] = DownloadQuality.DATA_SAVER.name }
      // A changed preference cannot alter a download that has already started.
      assertArrayEquals(high, readAll(downloadUtil.dataSourceFactory))
      sources.remove(id)
      assertArrayEquals(small, readAll(downloadUtil.dataSourceFactory))
      assertEquals(listOf(AudioQuality.HIGH, AudioQuality.HIGH, AudioQuality.LOW), qualities.toList())
      assertTrue(playerCache.getCachedSpans(id).isNotEmpty())
    }
  }

  @Test fun downloadManagerSavesTheSelectedBytesNotTheStreamingCache() = runBlocking {
    cacheBytes(playerCache, ByteArray(high.size) { 0x55 })
    tracker.retain(id) // Keep the private copy while shared publication runs.
    context.dataStore.edit {
      it[DownloadQualityKey] = DownloadQuality.HIGH.name
      it[DownloadOnWifiOnlyKey] = true
    }
    AudioServer(high, small).use { server ->
      val downloadUtil = createUtil()
      val qualities = Collections.synchronizedList(mutableListOf<AudioQuality>())
      downloadUtil.resolveDownloadSource = { _, quality ->
        qualities.add(quality)
        playback(server.url("high", 251, high.size), 251, high.size)
      }
      // The suite disables all external networks. Wait for the preference collector,
      // then allow this loopback-only download without changing production defaults.
      withTimeout(15_000) {
        var unmetered = false
        while (!unmetered) {
          instrumentation.runOnMainSync { unmetered = downloadUtil.downloadManager.requirements.isUnmeteredNetworkRequired }
          if (!unmetered) delay(50)
        }
      }
      instrumentation.runOnMainSync {
        downloadUtil.downloadManager.requirements = Requirements(0)
        downloadUtil.downloadManager.resumeDownloads()
        downloadUtil.downloadManager.addDownload(
          DownloadRequest.Builder(id, Uri.parse(id)).setCustomCacheKey(id).setData("FT quality test".toByteArray()).build()
        )
      }
      withTimeout(30_000) {
        while (downloadUtil.downloads.value[id]?.state != Download.STATE_COMPLETED) {
          check(downloadUtil.downloads.value[id]?.state != Download.STATE_FAILED) { "Loopback download failed" }
          delay(100)
        }
      }
      val spans = downloadCache.getCachedSpans(id).sortedBy { it.position }
      assertTrue(spans.isNotEmpty())
      val bytes = java.io.ByteArrayOutputStream().apply { spans.forEach { write(it.file!!.readBytes()) } }.toByteArray()
      assertArrayEquals(high, bytes)
      assertEquals(listOf(AudioQuality.HIGH), qualities.toList())
      assertEquals(DownloadQuality.HIGH, sources.qualityFor(id, DownloadQuality.DATA_SAVER))
      instrumentation.runOnMainSync { downloadUtil.downloadManager.removeDownload(id) }
      withTimeout(30_000) { while (downloadUtil.downloads.value.containsKey(id)) delay(100) }
    }
  }

  @Test fun changedRepresentationDoesNotTouchOrAppendToPartialCache() = runBlocking {
    val partial = high.copyOf(high.size / 3)
    cacheBytes(downloadCache, partial)
    val downloadUtil = createUtil()
    sources.qualityFor(id, DownloadQuality.HIGH)
    assertTrue(sources.acceptSource(id, DownloadSourceIdentity(251, "audio/wav", high.size.toLong(), "1"), false))
    downloadUtil.resolveDownloadSource = { _, _ ->
      playback("http://127.0.0.1:1/audio?itag=140&clen=${high.size}&lmt=1", 140, high.size)
    }
    try {
      readAll(downloadUtil.dataSourceFactory)
      fail("A different source must not resume an existing partial download")
    } catch (_: IOException) { /* expected */ }
    val spans = downloadCache.getCachedSpans(id)
    assertEquals(1, spans.size)
    assertArrayEquals(partial, spans.first().file!!.readBytes())
    assertEquals(251, sources.sourceFor(id)!!.itag)
  }

  private class AudioServer(private val high: ByteArray, private val small: ByteArray) : Closeable {
    private val socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
    private val worker = thread(name = "ft-quality-test-server", isDaemon = true) {
      while (!socket.isClosed) {
        val client = try { socket.accept() } catch (_: IOException) { break }
        client.use {
          it.soTimeout = 5_000
          val reader = it.getInputStream().bufferedReader()
          val request = reader.readLine() ?: return@use
          val headers = mutableListOf<String>()
          while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            headers.add(line)
          }
          val data = if (request.contains("/small?")) small else high
          val range = headers.firstOrNull { line -> line.startsWith("Range:", ignoreCase = true) }
          val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
          val requestedEnd = range?.substringAfter('-')?.trim()?.toIntOrNull() ?: (data.size - 1)
          val output = it.getOutputStream()
          if (start >= data.size) {
            output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${data.size}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
          } else {
            val end = requestedEnd.coerceAtMost(data.size - 1)
            val length = end - start + 1
            val status = if (range == null) "200 OK" else "206 Partial Content"
            output.write(("HTTP/1.1 $status\r\nContent-Type: audio/wav\r\nContent-Length: $length\r\n" +
              "Content-Range: bytes $start-$end/${data.size}\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n").toByteArray())
            output.write(data, start, length)
          }
          output.flush()
        }
      }
    }
    fun url(path: String, itag: Int, length: Int) = "http://127.0.0.1:${socket.localPort}/$path?itag=$itag&clen=$length&lmt=1"
    override fun close() { socket.close(); worker.join(5_000) }
  }
}
