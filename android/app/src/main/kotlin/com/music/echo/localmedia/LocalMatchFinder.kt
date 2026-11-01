package echo.music.iad1tya.localmedia

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import echo.music.iad1tya.models.MediaMetadata
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class LocalMatchFinder @Inject constructor(@ApplicationContext private val context: Context) {
  val permission: String
    get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
      else Manifest.permission.READ_EXTERNAL_STORAGE

  fun hasPermission(): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

  suspend fun find(metadata: MediaMetadata): LocalMatchResult = withContext(Dispatchers.IO) {
    if (!hasPermission()) return@withContext LocalMatchResult(null, emptyList())
    val projection = arrayOf(
      MediaStore.Audio.Media._ID,
      MediaStore.Audio.Media.TITLE,
      MediaStore.Audio.Media.ARTIST,
      MediaStore.Audio.Media.DURATION,
      MediaStore.Audio.Media.DISPLAY_NAME,
      MediaStore.Audio.Media.MIME_TYPE,
    )
    val rows = mutableListOf<MatchTrack>()
    try {
      context.contentResolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        projection,
        "${MediaStore.Audio.Media.SIZE} > 0 AND ${MediaStore.Audio.Media.DURATION} > 0",
        null,
        null,
      )?.use { cursor ->
        val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val name = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
        val mime = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
        while (cursor.moveToNext()) {
          if (!SupportedLocalAudio.isSupported(cursor.getString(name), cursor.getString(mime))) continue
          val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(id))
          rows += MatchTrack(
            id = uri.toString(),
            title = cursor.getString(title).orEmpty(),
            artist = cursor.getString(artist).orEmpty(),
            durationSeconds = (cursor.getLong(duration) / 1000L).coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
          )
        }
      }
      val online = MatchTrack(
        id = metadata.id,
        title = metadata.title,
        artist = metadata.artists.joinToString(", ") { it.name },
        durationSeconds = metadata.duration,
      )
      val preliminary = LocalMatchEngine.match(online, rows)
      val accessible = preliminary.candidates.filter { track ->
        try {
          context.contentResolver.openFileDescriptor(android.net.Uri.parse(track.id), "r")?.use { true } ?: false
        } catch (_: Exception) {
          false
        }
      }
      LocalMatchEngine.match(online, accessible)
    } catch (_: SecurityException) {
      LocalMatchResult(null, emptyList())
    } catch (_: IllegalArgumentException) {
      // An unusual provider/device may not support the query. Playback can still stream.
      LocalMatchResult(null, emptyList())
    }
  }
}
