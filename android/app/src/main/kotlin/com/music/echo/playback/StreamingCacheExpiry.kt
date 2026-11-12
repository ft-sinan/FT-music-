package echo.music.iad1tya.playback

import android.content.Context
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import echo.music.iad1tya.di.ApplicationScope
import echo.music.iad1tya.di.PlayerCache
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

internal const val STREAMING_CACHE_LIFETIME_MS = 60 * 60 * 1000L
private const val ACTIVE_RETRY_MS = 30_000L
private const val CLOCK_CHECK_MS = 5 * 60 * 1000L

/** Expiry is deliberately limited to the player cache; it is never given the download cache. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class StreamingCacheExpiryEngine(
  private val cache: Cache,
  private val ages: StreamingCacheAges,
  private val guard: StreamingCacheGuard,
) {
  /** Removes expired, idle resources and returns the next time the scan should run, if any. */
  fun clean(nowMs: Long): Long? {
    var nextScanMs: Long? = null
    for (key in cache.keys) {
      val spans = cache.getCachedSpans(key)
      if (spans.isEmpty()) continue
      val expiresAt = expiresAt(key, spans, nowMs)
      if (nowMs >= expiresAt) {
        // Re-check under the guard: a manual clear and re-cache may have replaced this key
        // since the snapshot above was taken.
        val removed = guard.ifIdle(key) { cleanBeforeFirstUse(key, nowMs) }
        if (!removed) nextScanMs = earlier(nextScanMs, nowMs + ACTIVE_RETRY_MS)
      } else {
        nextScanMs = earlier(nextScanMs, expiresAt)
      }
    }
    return nextScanMs
  }

  /** Called while the guard is held, before the first reader of an idle song opens. */
  fun cleanBeforeFirstUse(key: String, nowMs: Long) {
    val spans = cache.getCachedSpans(key)
    if (spans.isNotEmpty() && nowMs >= expiresAt(key, spans, nowMs)) {
      cache.removeResource(key)
    }
  }

  private fun expiresAt(key: String, spans: Set<CacheSpan>, nowMs: Long): Long {
    // File modification time is the last write, unlike Media3's LRU last-touch timestamp.
    // It also supplies a conservative first-seen time for caches created by older app versions.
    val firstWrite = spans.mapNotNull { it.file?.lastModified()?.takeIf { time -> time > 0L } }
      .minOrNull() ?: nowMs
    val createdAt = ages.createdAt(key, firstWrite, nowMs)
    return if (createdAt > Long.MAX_VALUE - STREAMING_CACHE_LIFETIME_MS) Long.MAX_VALUE
    else createdAt + STREAMING_CACHE_LIFETIME_MS
  }

  private fun earlier(current: Long?, candidate: Long): Long =
    if (current == null) candidate else minOf(current, candidate)
}

/** In-process deadline scheduling plus OS-managed cleanup after the app leaves memory. */
@Singleton
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StreamingCacheExpiry @Inject constructor(
  @ApplicationContext private val context: Context,
  @PlayerCache private val cache: SimpleCache,
  private val ages: StreamingCacheAges,
  private val guard: StreamingCacheGuard,
  @ApplicationScope private val scope: CoroutineScope,
) {
  private val engine = StreamingCacheExpiryEngine(cache, ages, guard)
  private val started = AtomicBoolean(false)
  private val workLock = Any()
  private var backgroundWorkNeeded: Boolean? = null

  fun start() {
    if (!started.compareAndSet(false, true)) return
    scope.launch(Dispatchers.IO) {
      while (isActive) {
        try {
          val now = System.currentTimeMillis()
          val next = engine.clean(now)
          syncBackgroundWork(next, now)
          if (next == null) {
            ages.awaitChange()
          } else {
            withTimeoutOrNull((next - System.currentTimeMillis()).coerceIn(1_000L, CLOCK_CHECK_MS)) {
              ages.awaitChange()
            }
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Timber.w(e, "Could not clean temporary streaming cache")
          withTimeoutOrNull(ACTIVE_RETRY_MS) { ages.awaitChange() }
        }
      }
    }
  }

  /** Best effort: cache housekeeping must not cause playback to fail. */
  internal fun beforeFirstUse(key: String) {
    try {
      engine.cleanBeforeFirstUse(key, System.currentTimeMillis())
    } catch (e: Exception) {
      Timber.w(e, "Could not expire a temporary cache entry before playback")
    }
  }

  internal fun cleanInWorker() {
    val now = System.currentTimeMillis()
    val next = engine.clean(now)
    syncBackgroundWork(next, now)
  }

  /** Only keep a periodic job while there is temporary audio to clean. */
  private fun syncBackgroundWork(nextScanMs: Long?, nowMs: Long) = synchronized(workLock) {
    // Re-read inside the lock: a worker and the in-process scanner may finish in either order.
    val hasEntries = cache.keys.any { cache.getCachedSpans(it).isNotEmpty() }
    if (backgroundWorkNeeded == hasEntries) return@synchronized
    val workManager = WorkManager.getInstance(context)
    if (hasEntries) {
      val initialDelay = ((nextScanMs ?: nowMs) - nowMs).coerceAtLeast(0L)
      val request = PeriodicWorkRequestBuilder<StreamingCacheCleanupWorker>(15, TimeUnit.MINUTES)
        .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
        .build()
      workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    } else {
      workManager.cancelUniqueWork(WORK_NAME)
    }
    backgroundWorkNeeded = hasEntries
  }

  internal companion object { const val WORK_NAME = "ft-streaming-cache-expiry" }
}
