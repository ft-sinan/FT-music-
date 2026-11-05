package echo.music.iad1tya.widget

import android.content.ComponentName
import android.content.pm.PackageManager
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import echo.music.iad1tya.R
import echo.music.iad1tya.db.InternalDatabase
import echo.music.iad1tya.db.MusicDatabase
import echo.music.iad1tya.db.entities.LyricsEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FTWidgetInstrumentedTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context get() = instrumentation.targetContext

  @Test
  fun newWidgetsAreRegisteredAlongsideExistingWidgets() {
    val pm = context.packageManager
    for (receiver in listOf(FTLyricsWidgetReceiver::class.java, FTCompactWidgetReceiver::class.java, MusicWidgetReceiver::class.java)) {
      val info = pm.getReceiverInfo(ComponentName(context, receiver), PackageManager.GET_META_DATA)
      assertNotNull(info.metaData.get("android.appwidget.provider"))
    }
  }

  @Test
  fun backgroundLookupCannotOverwriteEditedLyrics() = runBlocking {
    val internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
    val database = MusicDatabase(internal)
    try {
      database.upsert(LyricsEntity("song", "User edited"))
      database.insertLyricsIfAbsent(LyricsEntity("song", "Fetched result"))
      assertEquals("User edited", database.lyrics("song").first()?.lyrics)
    } finally {
      internal.close()
    }
  }

  @Test
  fun bothRemoteViewsInflateAndAcceptPlayerUpdates() {
    instrumentation.runOnMainSync {
      for (layout in listOf(R.layout.widget_ft_lyrics, R.layout.widget_ft_compact)) {
        val views = RemoteViews(context.packageName, layout).apply {
          setTextViewText(R.id.widget_song_title, "Test song")
          setTextViewText(R.id.widget_artist_name, "Test artist")
          setImageViewResource(R.id.widget_play_pause, R.drawable.ic_widget_pause_bold_white)
          setInt(R.id.widget_progress_fill, "setImageLevel", 5000)
          if (layout == R.layout.widget_ft_lyrics) {
            setTextViewText(R.id.widget_lyrics_current, "A timed line")
          }
        }
        val root = views.apply(context, FrameLayout(context))
        assertEquals("Test song", root.findViewById<TextView>(R.id.widget_song_title).text.toString())
        assertNotNull(root.findViewById<android.view.View>(R.id.widget_next_button))
        if (layout == R.layout.widget_ft_lyrics) {
          assertEquals("A timed line", root.findViewById<TextView>(R.id.widget_lyrics_current).text.toString())
        }
      }
    }
  }
}
