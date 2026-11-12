package echo.music.iad1tya.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import echo.music.iad1tya.localmedia.TagSearchSource
import echo.music.iad1tya.localmedia.YouTubeTagSearchSource
import javax.inject.Singleton

/**
 * The tag editor searches through [TagSearchSource] rather than calling YouTube Music directly, so
 * the Edit info flow can be exercised with a fake source instead of live network responses.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TagSearchModule {
  @Binds
  @Singleton
  abstract fun bindTagSearchSource(source: YouTubeTagSearchSource): TagSearchSource
}
