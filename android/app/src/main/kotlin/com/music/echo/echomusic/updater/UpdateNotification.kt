package echo.music.iad1tya.echomusic.updater

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import echo.music.iad1tya.MainActivity
import echo.music.iad1tya.R

/**
 * Tells the user a newer FT Music release exists.
 *
 * Tapping a notification opens the in-app update screen rather than Android's installer, because
 * that screen is where the downloaded package is checked against the release's published SHA-256
 * and FT Music's signing certificate. A notification must not be a way around that.
 *
 * Notifications are only posted when the user left "Update notifications" on, and on Android 13+
 * only if the notification permission was already granted - this never asks for it.
 */
object UpdateNotification {
  const val CHANNEL_ID = "app_updates_channel"
  const val NOTIFICATION_ID = 5679

  private fun manager(context: Context): NotificationManager =
    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  private fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val channel =
      NotificationChannel(
        CHANNEL_ID,
        context.getString(R.string.update_channel_name),
        NotificationManager.IMPORTANCE_DEFAULT
      ).apply {
        description = context.getString(R.string.update_channel_desc)
        setShowBadge(true)
      }
    manager(context).createNotificationChannel(channel)
  }

  /** False when the user turned update notifications off or has not allowed notifications. */
  private fun mayNotify(context: Context): Boolean {
    if (!getUpdateNotificationsSetting(context)) return false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val granted =
        ContextCompat.checkSelfPermission(
          context,
          android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
      if (!granted) return false
    }
    return NotificationManagerCompat.from(context).areNotificationsEnabled()
  }

  private fun openUpdateScreenIntent(context: Context): PendingIntent {
    val intent =
      Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra(MainActivity.EXTRA_OPEN_UPDATE_SCREEN, true)
    return PendingIntent.getActivity(
      context,
      NOTIFICATION_ID,
      intent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
  }

  fun showUpdateAvailable(context: Context, version: String, sizeMb: String) {
    if (!mayNotify(context)) return
    ensureChannel(context)
    val text =
      if (sizeMb.isNotEmpty()) {
        context.getString(R.string.version_file_size, version, sizeMb)
      } else {
        context.getString(R.string.version, version)
      }
    val notification =
      NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.updated)
        .setContentTitle(context.getString(R.string.update_available_title))
        .setContentText(text)
        .setStyle(
          NotificationCompat.BigTextStyle().bigText(context.getString(R.string.update_to_v, version))
        )
        .setContentIntent(openUpdateScreenIntent(context))
        .setAutoCancel(true)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
        .build()
    NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
  }

  /**
   * A newer release exists but publishes no package for this device, which is worth saying out loud
   * rather than silently showing "up to date".
   */
  fun showNoPackageForDevice(context: Context, version: String) {
    if (!mayNotify(context)) return
    ensureChannel(context)
    val notification =
      NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.updated)
        .setContentTitle(context.getString(R.string.update_available_title))
        .setContentText(context.getString(R.string.update_no_package_title, version))
        .setStyle(
          NotificationCompat.BigTextStyle()
            .bigText(context.getString(R.string.update_no_package_body))
        )
        .setContentIntent(openUpdateScreenIntent(context))
        .setAutoCancel(true)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .build()
    NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
  }

  fun cancel(context: Context) {
    runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
  }
}
