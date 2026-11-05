package echo.music.iad1tya.playback

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

/** Keeps prefetches, crossfade reads, and normal cache reads/writes alive until close. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class GuardedStreamingDataSource(
  private val delegate: DataSource,
  private val guard: StreamingCacheGuard,
  private val expiry: StreamingCacheExpiry,
) : DataSource by delegate {
  private var leasedKey: String? = null

  override fun open(dataSpec: DataSpec): Long {
    check(leasedKey == null) { "Data source is already open" }
    // This is Media3's default CacheKeyFactory rule, also used by our player cache.
    val key = dataSpec.key ?: dataSpec.uri.toString()
    guard.acquire(key) { expiry.beforeFirstUse(key) }
    leasedKey = key
    try {
      return delegate.open(dataSpec)
    } catch (e: Throwable) {
      try {
        delegate.close()
      } catch (closeError: Throwable) {
        e.addSuppressed(closeError)
      } finally {
        leasedKey = null
        guard.release(key)
      }
      throw e
    }
  }

  override fun close() {
    try {
      delegate.close()
    } finally {
      leasedKey?.let { guard.release(it) }
      leasedKey = null
    }
  }
}
