package echo.music.iad1tya.localmedia

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import echo.music.iad1tya.R
import echo.music.iad1tya.db.entities.Song
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Operations are limited to the single MediaStore audio row the user selected. */
class LocalAudioFileDeletion(private val context: Context) {
  data class Target(
    val uri: Uri,
    val displayName: String,
    val size: Long,
    val modifiedSeconds: Long,
    val title: String,
    val durationSeconds: Int,
  ) {
    fun encode(): String = JSONObject().put("uri", uri.toString()).put("name", displayName)
      .put("size", size).put("modified", modifiedSeconds).put("title", title)
      .put("duration", durationSeconds).toString()

    companion object {
      fun decode(value: String): Target? = runCatching {
        val json = JSONObject(value)
        Target(Uri.parse(json.getString("uri")), json.getString("name"), json.getLong("size"),
          json.getLong("modified"), json.getString("title"), json.getInt("duration"))
      }.getOrNull()
    }
  }

  sealed interface Inspection {
    data class Ready(val target: Target) : Inspection
    data object Missing : Inspection
    data object Changed : Inspection
    data object Unavailable : Inspection
  }

  sealed interface DeleteResult {
    data object Deleted : DeleteResult
    data object Missing : DeleteResult
    data object Changed : DeleteResult
    data object Failed : DeleteResult
    data class NeedsConsent(val sender: IntentSender) : DeleteResult
  }

  enum class Presence { PRESENT, MISSING, UNKNOWN }

  /** Accept only the exact collection/row format produced by LocalSongScanner. */
  fun audioUri(id: String): Uri? {
    val uri = runCatching { Uri.parse(id) }.getOrNull() ?: return null
    if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY ||
      uri.query != null || uri.fragment != null) return null
    val parts = uri.pathSegments
    if (parts.size != 4 || parts[0] != "external" || parts[1] != "audio" || parts[2] != "media") return null
    val rowId = parts[3].toLongOrNull() ?: return null
    if (rowId <= 0 || parts[3] != rowId.toString()) return null
    return uri
  }

  suspend fun inspect(song: Song): Inspection = withContext(Dispatchers.IO) {
    if (!song.song.isLocal) return@withContext Inspection.Unavailable
    val uri = audioUri(song.id) ?: return@withContext Inspection.Unavailable
    val expectedSize = song.format?.contentLength?.takeIf { it > 0 } ?: return@withContext Inspection.Changed
    try {
      val projection = arrayOf(
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.SIZE,
        MediaStore.Audio.Media.DATE_MODIFIED,
        MediaStore.Audio.Media.DURATION,
      )
      context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@withContext Inspection.Missing
        val name = cursor.getString(0).orEmpty()
        val title = cursor.getString(1)?.trim()?.takeIf { it.isNotEmpty() }
          ?: name.substringBeforeLast('.').trim().takeIf { it.isNotEmpty() }
          ?: context.getString(R.string.unknown)
        val size = cursor.getLong(2)
        val modified = cursor.getLong(3)
        val duration = (cursor.getLong(4).coerceAtLeast(0L) / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val scannedDate = song.song.dateModified
        val currentDate = modified.takeIf { it > 0 }?.let {
          LocalDateTime.ofInstant(Instant.ofEpochSecond(it), ZoneId.systemDefault())
        }
        if (scannedDate != null && scannedDate != currentDate) return@withContext Inspection.Changed
        // Refuse a stale/recycled MediaStore ID rather than risk deleting another file.
        val expected = LocalAudioFingerprint(song.song.title, expectedSize, if (scannedDate == null) null else modified, song.song.duration)
        val actual = LocalAudioFingerprint(title, size, modified, duration)
        if (!LocalAudioDeletionPolicy.matches(expected, actual, name)) return@withContext Inspection.Changed
        return@withContext Inspection.Ready(Target(uri, name, size, modified, title, duration))
      } ?: Inspection.Unavailable
    } catch (_: Exception) {
      Inspection.Unavailable
    }
  }

  suspend fun presence(id: String): Presence = withContext(Dispatchers.IO) {
    val uri = audioUri(id) ?: return@withContext Presence.UNKNOWN
    try {
      context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media._ID), null, null, null)?.use {
        if (it.moveToFirst()) Presence.PRESENT else Presence.MISSING
      } ?: Presence.UNKNOWN
    } catch (_: Exception) { Presence.UNKNOWN }
  }

  /** On Android 10 this can ask Android for write consent before retrying the same deletion. */
  suspend fun deleteDirect(song: Song, expectedTarget: Target): DeleteResult = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return@withContext DeleteResult.Failed
    when (val inspected = inspect(song)) {
      Inspection.Missing -> return@withContext DeleteResult.Missing
      Inspection.Changed -> return@withContext DeleteResult.Changed
      Inspection.Unavailable -> return@withContext DeleteResult.Failed
      is Inspection.Ready -> {
        if (inspected.target != expectedTarget) return@withContext DeleteResult.Changed
        try {
          if (context.contentResolver.delete(inspected.target.uri, null, null) == 1) DeleteResult.Deleted
          else if (presence(song.id) == Presence.MISSING) DeleteResult.Missing else DeleteResult.Failed
        } catch (e: SecurityException) {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
            DeleteResult.NeedsConsent(e.userAction.actionIntent.intentSender)
          } else DeleteResult.Failed
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          DeleteResult.Failed
        }
      }
    }
  }

  /** Android 11+ performs the deletion itself if the user approves the system prompt. */
  fun systemDeleteRequest(target: Target): IntentSender? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || audioUri(target.uri.toString()) == null) return null
    return try {
      MediaStore.createDeleteRequest(context.contentResolver, listOf(target.uri)).intentSender
    } catch (_: Exception) { null }
  }
}
