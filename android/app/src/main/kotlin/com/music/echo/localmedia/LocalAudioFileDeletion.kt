package echo.music.iad1tya.localmedia

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import echo.music.iad1tya.R
import echo.music.iad1tya.db.entities.Song
import java.io.File
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
    // Android 9's MediaStore can remove an audio row without removing its file.
    // Record the canonical path and inode that the user actually confirmed.
    val legacyPath: String? = null,
    val legacyDevice: Long? = null,
    val legacyInode: Long? = null,
    val legacyModifiedMillis: Long? = null,
  ) {
    fun encode(): String = JSONObject().put("uri", uri.toString()).put("name", displayName)
      .put("size", size).put("modified", modifiedSeconds).put("title", title)
      .put("duration", durationSeconds).put("path", legacyPath).put("device", legacyDevice)
      .put("inode", legacyInode).put("mtime", legacyModifiedMillis).toString()

    companion object {
      fun decode(value: String): Target? = runCatching {
        val json = JSONObject(value)
        Target(Uri.parse(json.getString("uri")), json.getString("name"), json.getLong("size"),
          json.getLong("modified"), json.getString("title"), json.getInt("duration"),
          json.optString("path").takeIf { it.isNotEmpty() },
          if (json.has("device")) json.getLong("device") else null,
          if (json.has("inode")) json.getLong("inode") else null,
          if (json.has("mtime")) json.getLong("mtime") else null)
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
      @Suppress("DEPRECATION")
      val projection = buildList {
        add(MediaStore.Audio.Media.DISPLAY_NAME)
        add(MediaStore.Audio.Media.TITLE)
        add(MediaStore.Audio.Media.SIZE)
        add(MediaStore.Audio.Media.DATE_MODIFIED)
        add(MediaStore.Audio.Media.DURATION)
        if (Build.VERSION.SDK_INT <= 28) add(MediaStore.Audio.Media.DATA)
      }.toTypedArray()
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
        if (Build.VERSION.SDK_INT <= 28) {
          val rawPath = cursor.getString(5)?.takeIf { it.isNotBlank() } ?: return@withContext Inspection.Unavailable
          val rawFile = File(rawPath)
          if (!rawFile.isAbsolute) return@withContext Inspection.Unavailable
          val rawStat = Os.lstat(rawFile.absolutePath)
          // Do not follow a final-component symlink to a different file.
          if (!OsConstants.S_ISREG(rawStat.st_mode)) return@withContext Inspection.Changed
          val file = rawFile.canonicalFile
          val stat = Os.lstat(file.absolutePath)
          if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_dev != rawStat.st_dev || stat.st_ino != rawStat.st_ino ||
            stat.st_size != size || !file.canRead()) return@withContext Inspection.Changed
          val modifiedMillis = file.lastModified().takeIf { it > 0 } ?: return@withContext Inspection.Unavailable
          return@withContext Inspection.Ready(Target(uri, name, size, modified, title, duration,
            file.absolutePath, stat.st_dev, stat.st_ino, modifiedMillis))
        }
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
        if (Build.VERSION.SDK_INT <= 28) return@withContext deleteLegacy(inspected.target)
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

  /** API 28's MediaProvider deletes audio metadata but may leave the actual audio file. */
  private fun deleteLegacy(target: Target): DeleteResult {
    val path = target.legacyPath ?: return DeleteResult.Failed
    val file = File(path)
    try {
      val stat = Os.lstat(path)
      if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_dev != target.legacyDevice ||
        stat.st_ino != target.legacyInode || stat.st_size != target.size ||
        file.lastModified() != target.legacyModifiedMillis || file.canonicalPath != path) return DeleteResult.Changed
      if (!file.delete()) return if (file.exists()) DeleteResult.Failed else DeleteResult.Missing
    } catch (_: Exception) {
      return DeleteResult.Failed
    }

    // The file is already gone. Delete only its matching media row, never a row
    // that may have been replaced while the filesystem operation was in progress.
    try {
      if (legacyRowMatches(target)) context.contentResolver.delete(target.uri, null, null)
    } catch (_: Exception) { /* The media scanner can remove a stale row later. */ }
    try { MediaScannerConnection.scanFile(context, arrayOf(path), null, null) }
    catch (_: Exception) { /* File removal has already succeeded. */ }
    return DeleteResult.Deleted
  }

  @Suppress("DEPRECATION")
  private fun legacyRowMatches(target: Target): Boolean {
    return try {
      context.contentResolver.query(target.uri, arrayOf(
        MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.SIZE,
        MediaStore.Audio.Media.DATE_MODIFIED, MediaStore.Audio.Media.DURATION,
      ), null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use false
        val rowPath = cursor.getString(0) ?: return@use false
        File(rowPath).canonicalPath == target.legacyPath && cursor.getString(1) == target.displayName &&
          cursor.getLong(2) == target.size && cursor.getLong(3) == target.modifiedSeconds &&
          (cursor.getLong(4).coerceAtLeast(0L) / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() == target.durationSeconds
      } ?: false
    } catch (_: Exception) { false }
  }

  /** Android 11+ performs the deletion itself if the user approves the system prompt. */
  fun systemDeleteRequest(target: Target): IntentSender? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || audioUri(target.uri.toString()) == null) return null
    return try {
      MediaStore.createDeleteRequest(context.contentResolver, listOf(target.uri)).intentSender
    } catch (_: Exception) { null }
  }
}
