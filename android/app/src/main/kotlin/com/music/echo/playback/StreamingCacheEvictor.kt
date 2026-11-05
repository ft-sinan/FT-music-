package echo.music.iad1tya.playback

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import timber.log.Timber

/** Records the first cached span while preserving the existing size-based eviction policy. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class StreamingCacheEvictor(
  private val delegate: CacheEvictor,
  private val ages: StreamingCacheAges,
) : CacheEvictor {
  private var initializing = true

  override fun requiresCacheSpanTouches(): Boolean = delegate.requiresCacheSpanTouches()

  override fun onCacheInitialized() {
    initializing = false
    delegate.onCacheInitialized()
    ages.signalChange()
  }

  override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) =
    delegate.onStartFile(cache, key, position, length)

  override fun onSpanAdded(cache: Cache, span: CacheSpan) {
    try {
      val firstLiveSpan = !initializing && cache.getCachedSpans(span.key).size == 1
      ages.recordSpan(span.key, span.file?.lastModified() ?: 0L, initializing, firstLiveSpan)
    } catch (e: Exception) {
      // Age bookkeeping must never break playback or skip the size evictor's callback.
      Timber.w(e, "Could not record the temporary cache age")
      ages.signalChange()
    }
    delegate.onSpanAdded(cache, span)
  }

  override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
    delegate.onSpanRemoved(cache, span)
    // During cache initialization more spans for this key may still be discovered.
    try {
      if (!initializing && cache.getCachedSpans(span.key).isEmpty()) ages.forget(span.key)
    } catch (e: Exception) {
      Timber.w(e, "Could not forget the temporary cache age")
      ages.signalChange()
    }
  }

  override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) =
    delegate.onSpanTouched(cache, oldSpan, newSpan)
}
