package echo.music.iad1tya.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses real, separate Media3 caches and simulated times; no network or hour-long sleep. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class StreamingCacheExpiryInstrumentedTest {
  private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val prefix = "ft-expiry-${UUID.randomUUID()}-"
  private val bytes = byteArrayOf(1, 2, 3, 4, 5, 6)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private lateinit var provider: StandaloneDatabaseProvider
  private lateinit var ages: StreamingCacheAges
  private lateinit var guard: StreamingCacheGuard
  private lateinit var playerCache: SimpleCache
  private lateinit var downloadCache: SimpleCache
  private val playerDirectory get() = File(context.cacheDir, "${prefix}player")
  private val downloadDirectory get() = File(context.cacheDir, "${prefix}download")
  private val agePreferences get() =
    context.getSharedPreferences(StreamingCacheAges.PREFERENCES_NAME, Context.MODE_PRIVATE)

  @Before fun setup() {
    ages = StreamingCacheAges(context)
    guard = StreamingCacheGuard(ages)
    provider = StandaloneDatabaseProvider(context)
    playerCache = newPlayerCache()
    downloadCache = SimpleCache(downloadDirectory, NoOpCacheEvictor(), provider)
  }

  private fun newPlayerCache() = SimpleCache(
    playerDirectory,
    StreamingCacheEvictor(LeastRecentlyUsedCacheEvictor(1_000_000), ages),
    provider,
  )

  @After fun tearDown() {
    scope.cancel()
    playerCache.release()
    downloadCache.release()
    playerDirectory.deleteRecursively()
    downloadDirectory.deleteRecursively()
    val editor = agePreferences.edit()
    agePreferences.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
    editor.commit()
  }

  private fun id(suffix: String) = prefix + suffix
  private fun engine() = StreamingCacheExpiryEngine(playerCache, ages, guard)
  private fun age(key: String, timestamp: Long) {
    assertTrue(agePreferences.edit().putLong(key, timestamp).commit())
  }

  private fun cacheBytes(cache: SimpleCache, key: String, data: ByteArray = bytes, position: Long = 0L) {
    val hole = cache.startReadWrite(key, position, data.size.toLong())
    try {
      val sink = CacheDataSink(cache, C.LENGTH_UNSET.toLong())
      sink.open(DataSpec.Builder().setUri("https://example.invalid/audio").setKey(key)
        .setPosition(position).setLength(data.size.toLong()).build())
      sink.write(data, 0, data.size)
      sink.close()
    } finally {
      cache.releaseHoleSpan(hole)
    }
  }

  @Test fun oneHourBoundaryNeverDeletesDownloadsOrLocalFiles() {
    val now = System.currentTimeMillis()
    val old = id("old")
    val fresh = id("fresh")
    val localFile = File(context.cacheDir, "${prefix}local.wav")
    try {
      cacheBytes(playerCache, old)
      cacheBytes(playerCache, fresh)
      cacheBytes(downloadCache, old)
      localFile.writeBytes(bytes)
      age(old, now - STREAMING_CACHE_LIFETIME_MS)
      age(fresh, now - STREAMING_CACHE_LIFETIME_MS + 1)

      assertEquals(now + 1, engine().clean(now))
      assertTrue(playerCache.getCachedSpans(old).isEmpty())
      assertFalse(playerCache.getCachedSpans(fresh).isEmpty())
      assertTrue(downloadCache.isCached(old, 0, bytes.size.toLong()))
      assertArrayEquals(bytes, localFile.readBytes())

      engine().clean(now + 1)
      assertTrue(playerCache.getCachedSpans(fresh).isEmpty())
      assertTrue(downloadCache.isCached(old, 0, bytes.size.toLong()))
      assertArrayEquals(bytes, localFile.readBytes())
    } finally { localFile.delete() }
  }

  @Test fun playbackCrossfadeAndPrebufferKeysWaitButEarlierSongsExpire() {
    val now = System.currentTimeMillis()
    val playing = id("playing")
    val diff = "${playing}_diff"
    val next = id("prebuffer")
    val fading = id("fading")
    val earlier = id("earlier")
    listOf(playing, diff, next, fading, earlier).forEach {
      cacheBytes(playerCache, it)
      age(it, now - STREAMING_CACHE_LIFETIME_MS)
    }
    guard.setActiveMediaIds(setOf(playing, next, fading))
    engine().clean(now)
    assertTrue(playerCache.getCachedSpans(earlier).isEmpty())
    listOf(playing, diff, next, fading).forEach { assertFalse(playerCache.getCachedSpans(it).isEmpty()) }

    guard.setActiveMediaIds(setOf(next))
    engine().clean(now)
    listOf(playing, diff, fading).forEach { assertTrue(playerCache.getCachedSpans(it).isEmpty()) }
    assertFalse(playerCache.getCachedSpans(next).isEmpty())
    guard.setActiveMediaIds(emptySet())
    engine().clean(now)
    assertTrue(playerCache.getCachedSpans(next).isEmpty())
  }

  @Test fun readingOrPreloadingProtectsCacheUntilTheDataSourceCloses() {
    val now = System.currentTimeMillis()
    val key = id("reading")
    cacheBytes(playerCache, key)
    age(key, now - STREAMING_CACHE_LIFETIME_MS)
    val expiry = StreamingCacheExpiry(context, playerCache, ages, guard, scope)
    val cachedSource = CacheDataSource.Factory().setCache(playerCache)
      .setUpstreamDataSourceFactory(DataSource.Factory { ByteArrayDataSource(ByteArray(bytes.size) { 99 }) })
      .createDataSource()
    val source = GuardedStreamingDataSource(cachedSource, guard, expiry)
    guard.setActiveMediaIds(setOf(key))
    try {
      source.open(DataSpec.Builder().setUri("https://example.invalid/audio").setKey(key)
        .setLength(bytes.size.toLong()).build())
      guard.setActiveMediaIds(emptySet()) // e.g. a prefetch with no current player
      engine().clean(now)
      assertFalse(playerCache.getCachedSpans(key).isEmpty())
      val read = ByteArray(bytes.size)
      assertEquals(bytes.size, source.read(read, 0, read.size))
      assertArrayEquals(bytes, read)
    } finally { source.close() }
    engine().clean(now)
    assertTrue(playerCache.getCachedSpans(key).isEmpty())
  }

  @Test fun staleCacheIsRemovedBeforeAnIdleReaderOpensAndFailedOpenReleasesItsLease() {
    val key = id("failed-open")
    cacheBytes(playerCache, key)
    age(key, System.currentTimeMillis() - STREAMING_CACHE_LIFETIME_MS - 1)
    val expiry = StreamingCacheExpiry(context, playerCache, ages, guard, scope)
    var closed = false
    val failing = object : DataSource by ByteArrayDataSource(bytes) {
      override fun open(dataSpec: DataSpec): Long {
        assertTrue(playerCache.getCachedSpans(key).isEmpty())
        throw IOException("test failure")
      }
      override fun close() { closed = true }
    }
    val source = GuardedStreamingDataSource(failing, guard, expiry)
    try {
      source.open(DataSpec.Builder().setUri(Uri.parse("https://example.invalid/audio")).setKey(key).build())
      fail("open should fail")
    } catch (_: IOException) { }
    assertTrue(closed)

    // A failed open must not leave the newly cached song permanently protected.
    cacheBytes(playerCache, key)
    val now = System.currentTimeMillis()
    age(key, now - STREAMING_CACHE_LIFETIME_MS)
    engine().clean(now)
    assertTrue(playerCache.getCachedSpans(key).isEmpty())
  }

  @Test fun touchesAndLaterSpansDoNotExtendTheFirstCachingTime() {
    val key = id("touch")
    val now = System.currentTimeMillis()
    cacheBytes(playerCache, key)
    age(key, now - STREAMING_CACHE_LIFETIME_MS)
    val cached = playerCache.startReadWrite(key, 0, bytes.size.toLong()) // LRU touch
    assertTrue(cached.isCached)
    cacheBytes(playerCache, key, position = bytes.size.toLong())
    // Even if the first span is evicted, the remainder retains the original age.
    playerCache.removeSpan(playerCache.getCachedSpans(key).first())
    assertFalse(playerCache.getCachedSpans(key).isEmpty())
    engine().clean(now)
    assertTrue(playerCache.getCachedSpans(key).isEmpty())
  }

  @Test fun manualClearAndRecacheStartsANewHour() {
    val key = id("recache")
    val now = System.currentTimeMillis()
    cacheBytes(playerCache, key)
    age(key, now - STREAMING_CACHE_LIFETIME_MS)
    playerCache.removeResource(key)
    assertFalse(agePreferences.contains(key))
    cacheBytes(playerCache, key)
    engine().clean(now + 1_000)
    assertFalse(playerCache.getCachedSpans(key).isEmpty())
  }

  @Test fun creationAgeSurvivesCacheReinitialization() {
    val key = id("restart")
    val now = System.currentTimeMillis()
    cacheBytes(playerCache, key)
    age(key, now - STREAMING_CACHE_LIFETIME_MS)
    playerCache.release()
    playerCache = newPlayerCache()
    assertFalse(playerCache.getCachedSpans(key).isEmpty())
    engine().clean(now)
    assertTrue(playerCache.getCachedSpans(key).isEmpty())
  }

  @Test fun oldUntrackedCacheAndClockRollbackAreHandled() {
    val now = System.currentTimeMillis()
    val legacy = id("legacy")
    val future = id("future-clock")
    cacheBytes(playerCache, legacy)
    cacheBytes(playerCache, future)
    assertTrue(playerCache.getCachedSpans(legacy).first().file!!.setLastModified(now - STREAMING_CACHE_LIFETIME_MS - 5_000))
    agePreferences.edit().remove(legacy).putLong(future, now + 60_000).commit()
    engine().clean(now)
    assertTrue(playerCache.getCachedSpans(legacy).isEmpty())
    assertFalse(playerCache.getCachedSpans(future).isEmpty())
    assertEquals(now, agePreferences.getLong(future, 0L))
    engine().clean(now + STREAMING_CACHE_LIFETIME_MS)
    assertTrue(playerCache.getCachedSpans(future).isEmpty())
  }
}
