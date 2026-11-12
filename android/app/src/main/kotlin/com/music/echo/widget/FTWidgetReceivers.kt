package echo.music.iad1tya.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import echo.music.iad1tya.MainActivity
import echo.music.iad1tya.R
import echo.music.iad1tya.playback.MusicService

/** Keeps an idle widget useful: tapping it opens the player even when the service is stopped. */
abstract class FTWidgetReceiver(private val layout: Int, private val root: Int) : MusicWidgetReceiver() {
  override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
    if (!MusicService.isRunning) {
      val open = Intent(context, MainActivity::class.java).apply {
        action = MainActivity.ACTION_NOW_PLAYING
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
      }
      val pending = PendingIntent.getActivity(
        context, 190, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )
      val views = RemoteViews(context.packageName, layout).apply {
        setOnClickPendingIntent(root, pending)
      }
      ids.forEach { manager.updateAppWidget(it, views) }
    }
    super.onUpdate(context, manager, ids)
  }

  override fun onDisabled(context: Context) {
    super.onDisabled(context)
    if (MusicService.isRunning) {
      runCatching {
        context.startService(Intent(context, MusicService::class.java).apply { action = MusicWidgetReceiver.ACTION_UPDATE_WIDGET })
      }
    }
  }
}

class FTLyricsWidgetReceiver : FTWidgetReceiver(R.layout.widget_ft_lyrics, R.id.ft_lyrics_root)
class FTCompactWidgetReceiver : FTWidgetReceiver(R.layout.widget_ft_compact, R.id.ft_compact_root)
