package echo.music.iad1tya.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import echo.music.iad1tya.playback.DownloadQueue
import echo.music.iad1tya.playback.DownloadUtil
import javax.inject.Singleton

/**
 * The download chooser talks to [DownloadQueue] rather than to [DownloadUtil] directly, so the
 * offer-then-confirm flow can be exercised with a fake queue in plain JVM tests.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DownloadQueueModule {
  @Binds
  @Singleton
  abstract fun bindDownloadQueue(downloadUtil: DownloadUtil): DownloadQueue
}
