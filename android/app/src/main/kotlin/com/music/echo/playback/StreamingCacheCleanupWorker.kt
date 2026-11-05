package echo.music.iad1tya.playback

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

@EntryPoint
@InstallIn(SingletonComponent::class)
interface StreamingCacheWorkerDependencies {
  fun streamingCacheExpiry(): StreamingCacheExpiry
}

/** WorkManager may defer periodic work when Android restricts background activity. */
class StreamingCacheCleanupWorker(context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {
  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    try {
      EntryPointAccessors.fromApplication(applicationContext, StreamingCacheWorkerDependencies::class.java)
        .streamingCacheExpiry().cleanInWorker()
      Result.success()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Timber.w(e, "Background streaming-cache cleanup failed; it will be retried")
      Result.retry()
    }
  }
}
