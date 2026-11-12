package echo.music.iad1tya.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.collect.ImmutableList
import echo.music.iad1tya.MainActivity
import echo.music.iad1tya.R
import echo.music.iad1tya.echomusic.updater.downloadmanager.EchoNotificationProvider
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Ensures the system still receives a real media session and transport actions. */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class MediaNotificationInstrumentedTest {
  @Test fun mediaNotificationRequestsColorWithoutLosingMediaControls() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    instrumentation.runOnMainSync {
      val player = ExoPlayer.Builder(context).build()
      val session = MediaSession.Builder(context, player).setId("ft-notification-${UUID.randomUUID()}").build()
      try {
        player.setMediaItem(
          MediaItem.Builder().setUri("https://example.invalid/test")
            .setMediaMetadata(MediaMetadata.Builder().setTitle("FT test").setArtist("Artist").build()).build()
        )
        val provider = EchoNotificationProvider(context, { 3001 }, "ft-test-media", R.string.music_player)
          .setSmallIcon(R.drawable.ic_launcher_nobg)
        val result = provider.createNotification(session, ImmutableList.of(), TestActionFactory(context)) { }
        val notification = result.notification
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_COLORIZED))
        assertNotNull(notification.extras.getParcelable<android.os.Parcelable>(Notification.EXTRA_MEDIA_SESSION))
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
        assertTrue("Media controls should remain available", !notification.actions.isNullOrEmpty())
      } finally {
        session.release()
        player.release()
      }
    }
  }

  private class TestActionFactory(context: Context) : MediaNotification.ActionFactory {
    private val pending = PendingIntent.getActivity(
      context, 3001, Intent(context, MainActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    override fun createMediaAction(
      mediaSession: MediaSession, icon: IconCompat, title: CharSequence, command: Int,
    ): NotificationCompat.Action = NotificationCompat.Action.Builder(icon, title, pending).build()

    override fun createCustomAction(
      mediaSession: MediaSession, icon: IconCompat, title: CharSequence, customAction: String, extras: Bundle,
    ): NotificationCompat.Action = NotificationCompat.Action.Builder(icon, title, pending).build()

    override fun createCustomActionFromCustomCommandButton(
      mediaSession: MediaSession, customCommandButton: CommandButton,
    ): NotificationCompat.Action = NotificationCompat.Action.Builder(
      customCommandButton.iconResId, customCommandButton.displayName, pending
    ).build()

    override fun createMediaActionPendingIntent(mediaSession: MediaSession, command: Long): PendingIntent = pending
  }
}
