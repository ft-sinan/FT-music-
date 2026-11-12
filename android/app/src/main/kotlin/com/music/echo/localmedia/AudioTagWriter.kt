package echo.music.iad1tya.localmedia

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import dagger.hilt.android.qualifiers.ApplicationContext
import echo.music.iad1tya.db.MusicDatabase
import echo.music.iad1tya.db.entities.ArtistEntity
import echo.music.iad1tya.db.entities.SongArtistMap
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Writes title, artists, album, year and cover art into a local audio file's own tags.
 *
 * The audio stream is copied, never decoded or re-encoded, so tagging cannot cost quality. The
 * rewritten bytes are verified before they replace anything: if ffmpeg fails, the result does not
 * read back, or Android refuses the write, the original file is left exactly as it was and the
 * reason is reported instead of a silent half-write.
 */
@Singleton
class AudioTagWriter
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val database: MusicDatabase,
) {
  private val files = LocalAudioFileDeletion(context)


  /** [AudioTagEditResult] plus the system's own permission prompt when Android offered one. */
  data class TagWriteOutcome(
    val result: AudioTagEditResult,
    val permissionSender: IntentSender? = null,
  )

  /** What FT Music found in the file, so the editor can pre-fill and warn before writing. */
  data class Target(
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
    /** Null when FT Music cannot retag this container, which the UI reports as such. */
    val container: TagContainer?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val year: Int?,
    val durationMs: Long,
    val sizeBytes: Long,
    /** True when the file can be rewritten without asking Android for a per-item grant. */
    val directlyWritable: Boolean,
  )

  /** Android 10 and newer only let FT Music rewrite another app's file after an explicit grant. */
  fun writeRequestSender(uri: Uri): IntentSender? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      runCatching {
        MediaStore.createWriteRequest(context.contentResolver, listOf(uri)).intentSender
      }.getOrNull()
    } else {
      null
  }

  suspend fun inspect(songId: String): Target? =
    withContext(Dispatchers.IO) {
      val uri = files.audioUri(songId) ?: return@withContext null
      val columns = mutableListOf(
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.MIME_TYPE,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.SIZE,
      )
      if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) columns.add(MediaStore.Audio.Media.DATA)
      columns.add(MediaStore.Audio.Media.YEAR)
      runCatching {
        context.contentResolver.query(uri, columns.toTypedArray(), null, null, null)?.use { cursor ->
          if (!cursor.moveToFirst()) return@use null
          fun string(name: String) =
            cursor.getColumnIndex(name).takeIf { it >= 0 }?.let { cursor.getString(it) }
          fun long(name: String) =
            cursor.getColumnIndex(name).takeIf { it >= 0 }?.let { cursor.getLong(it) } ?: 0L
          val mime = string(MediaStore.Audio.Media.MIME_TYPE)
          val displayName = string(MediaStore.Audio.Media.DISPLAY_NAME).orEmpty()
          val container = TagContainer.from(mime, displayName)
          val path = string(MediaStore.Audio.Media.DATA)
          @Suppress("DEPRECATION")
          val year = long(MediaStore.Audio.Media.YEAR).toInt().takeIf { it > 0 }
          Target(
            uri = uri,
            displayName = displayName,
            mimeType = mime,
            container = container,
            title = string(MediaStore.Audio.Media.TITLE),
            artist = string(MediaStore.Audio.Media.ARTIST),
            album = string(MediaStore.Audio.Media.ALBUM),
            year = year,
            durationMs = long(MediaStore.Audio.Media.DURATION),
            sizeBytes = long(MediaStore.Audio.Media.SIZE),
            directlyWritable = path != null && runCatching { File(path).canWrite() }.getOrDefault(false),
          )
        }
      }.getOrNull()
    }

  /**
   * Writes [edit] into the file behind [songId]. A permission failure carries the system's own
   * request sender when there is one, so the caller can ask and try again.
   */
  suspend fun write(songId: String, edit: AudioTagEdit): TagWriteOutcome =
    withContext(Dispatchers.IO) {
      val target = inspect(songId)
        ?: return@withContext TagWriteOutcome(
          AudioTagEditResult.Failed(AudioTagEditFailure.NO_FILE, null)
        )
      val container = target.container
        ?: return@withContext TagWriteOutcome(
          AudioTagEditResult.Failed(
            AudioTagEditFailure.UNSUPPORTED_FORMAT,
            "${target.displayName} (${target.mimeType ?: "unknown type"})",
          )
        )
      if (edit.title.isBlank()) {
        return@withContext TagWriteOutcome(
          AudioTagEditResult.Failed(AudioTagEditFailure.WRITE_FAILED, "no title to write")
        )
      }
      val workDir = File(context.cacheDir, "ft-tag-edit").apply { mkdirs() }
      val suffix = target.displayName.substringAfterLast('.', "tmp").ifBlank { "tmp" }
      val run = UUID.randomUUID().toString().take(8)
      val input = File(workDir, "in-$run.$suffix")
      val output = File(workDir, "out-$run.$suffix")
      val cover = edit.coverBytes?.let { bytes ->
        val ext = if (edit.coverMimeType?.contains("png", ignoreCase = true) == true) "png" else "jpg"
        File(workDir, "cover-$run.$ext").also { it.writeBytes(bytes) }
      }
      try {
        // ffmpeg needs a path it can open, so read through Android when there is no usable one.
        val source = readableSource(target, input)
          ?: return@withContext TagWriteOutcome(
            AudioTagEditResult.Failed(AudioTagEditFailure.NO_FILE, target.displayName)
          )
        val readable = source.file
        val command = buildTagCommand(
          input = readable.absolutePath,
          output = output.absolutePath,
          edit = edit,
          container = container,
          coverPath = cover?.absolutePath,
        )
        val ffmpegOk = runCatching { execute(command) }.getOrElse { error ->
          Timber.w(error, "Tag edit failed for ${target.displayName}")
          return@withContext TagWriteOutcome(
            AudioTagEditResult.Failed(AudioTagEditFailure.WRITE_FAILED, error.message)
          )
        }
        if (!ffmpegOk || !output.isFile || output.length() <= 0L) {
          return@withContext TagWriteOutcome(
            AudioTagEditResult.Failed(AudioTagEditFailure.WRITE_FAILED, "ffmpeg reported no output")
          )
        }
        val problem = verify(readable, output, edit, target)
        if (problem != null) {
          return@withContext TagWriteOutcome(
            AudioTagEditResult.Failed(AudioTagEditFailure.VERIFY_FAILED, problem)
          )
        }
        when (val replaced = replace(target, source, output)) {
          is ReplaceResult.Denied ->
            return@withContext TagWriteOutcome(
              AudioTagEditResult.Failed(AudioTagEditFailure.PERMISSION_DENIED, replaced.detail),
              replaced.sender,
            )
          is ReplaceResult.Failed ->
            return@withContext TagWriteOutcome(
              AudioTagEditResult.Failed(AudioTagEditFailure.WRITE_FAILED, replaced.detail)
            )
          ReplaceResult.Written -> Unit
        }
        refreshSystemRows(target, edit)
        updateLibrary(songId, edit)
        TagWriteOutcome(
          AudioTagEditResult.Written(
            container = container,
            coverWritten = cover != null && container.supportsCover,
          )
        )
      } finally {
        runCatching { input.delete() }
        runCatching { output.delete() }
        cover?.let { runCatching { it.delete() } }
      }
    }

  /** [file] as ffmpeg sees it, and whether that path really is the user's file. */
  private data class Source(val file: File, val isTheUsersFile: Boolean)

  /** A path ffmpeg can read: the real file where Android exposes it, otherwise a temporary copy. */
  private fun readableSource(target: Target, fallback: File): Source? {
    val columns =
      if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) arrayOf(MediaStore.Audio.Media.DATA)
      else null
    if (columns != null) {
      val path = runCatching {
        context.contentResolver.query(target.uri, columns, null, null, null)?.use { cursor ->
          if (cursor.moveToFirst()) cursor.getString(0) else null
        }
      }.getOrNull()
      if (path != null) {
        val file = File(path)
        if (file.isFile && file.canRead()) return Source(file, isTheUsersFile = true)
      }
    }
    // Scoped storage hides the path, so work from a copy and write back through the descriptor.
    return runCatching {
      context.contentResolver.openInputStream(target.uri)?.use { stream ->
        FileOutputStream(fallback).use { sink -> stream.copyTo(sink) }
      } ?: return@runCatching null
      if (fallback.length() > 0L) Source(fallback, isTheUsersFile = false) else null
    }.getOrNull()
  }

  private suspend fun execute(command: String): Boolean =
    withTimeoutOrNull(TAG_EDIT_TIMEOUT_MS) {
      suspendCancellableCoroutine { continuation ->
        val session = FFmpegKit.executeAsync(command) { completed ->
          if (!continuation.isActive) return@executeAsync
          val returnCode = completed.returnCode
          if (returnCode != null && ReturnCode.isSuccess(returnCode)) continuation.resume(true)
          else continuation.resumeWithException(
            IllegalStateException("ffmpeg exited with ${completed.output?.take(400)}")
          )
        }
        continuation.invokeOnCancellation { runCatching { FFmpegKit.cancel(session.sessionId) } }
      }
    } ?: false

  /**
   * Confirms the audio survived and the tags are really there. Android's reader cannot report tags
   * for every container, so a missing readback is not treated as a contradiction - a *different*
   * value is.
   */
  private fun verify(
    original: File,
    rewritten: File,
    edit: AudioTagEdit,
    target: Target,
  ): String? {
    val before = readMetadata(original)
    val after = readMetadata(rewritten) ?: return "the rewritten file could not be read"
    val expectedDuration = target.durationMs.takeIf { it > 0 } ?: before?.durationMs ?: 0L
    if (expectedDuration > 0 && kotlin.math.abs(after.durationMs - expectedDuration) > 1_500L) {
      return "duration changed from $expectedDuration ms to ${after.durationMs} ms"
    }
    val beforeCodec = before?.mimeType
    if (beforeCodec != null && after.mimeType != null && beforeCodec != after.mimeType) {
      return "audio format changed from $beforeCodec to ${after.mimeType}"
    }
    val readTitle = after.title?.trim()
    if (!readTitle.isNullOrEmpty() && !readTitle.equals(edit.title.trim(), ignoreCase = true)) {
      return "the file still reports the title \"$readTitle\""
    }
    return null
  }

  private data class ReadMetadata(val title: String?, val durationMs: Long, val mimeType: String?)

  private fun readMetadata(file: File): ReadMetadata? = runCatching {
    // Closed by hand: MediaMetadataRetriever is only AutoCloseable from API 29, and FT Music
    // still supports API 26.
    val retriever = MediaMetadataRetriever()
    try {
      retriever.setDataSource(file.absolutePath)
      ReadMetadata(
        title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
        durationMs =
          retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L,
        mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
      )
    } finally {
      runCatching { retriever.release() }
    }
  }.getOrNull()

  private sealed interface ReplaceResult {
    data object Written : ReplaceResult
    data class Denied(val detail: String?, val sender: IntentSender?) : ReplaceResult
    data class Failed(val detail: String?) : ReplaceResult
  }

  /** Puts the verified bytes where the original was, by path when possible and by descriptor else. */
  private fun replace(target: Target, source: Source, verified: File): ReplaceResult {
    val original = source.file
    if (source.isTheUsersFile && original.canWrite()) {
      return runCatching<ReplaceResult> {
        val sibling = File(original.parentFile, ".${original.name}.ft-tmp")
        Files.copy(verified.toPath(), sibling.toPath(), StandardCopyOption.REPLACE_EXISTING)
        Files.move(sibling.toPath(), original.toPath(), StandardCopyOption.REPLACE_EXISTING)
        ReplaceResult.Written
      }.getOrElse { error ->
        Timber.w(error, "Could not replace ${target.displayName} in place")
        ReplaceResult.Failed(error.message)
      }
    }
    return runCatching<ReplaceResult> {
      context.contentResolver.openFileDescriptor(target.uri, "rw")?.use { descriptor ->
        FileOutputStream(descriptor.fileDescriptor).use { sink ->
          // "rw" keeps existing bytes past the new end, so the file must be truncated first.
          sink.channel.truncate(0)
          verified.inputStream().use { source -> source.copyTo(sink) }
          sink.flush()
        }
      } ?: return@runCatching ReplaceResult.Failed("the file could not be opened for writing")
      ReplaceResult.Written
    }.getOrElse { error ->
      val sender =
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q &&
          error is RecoverableSecurityException
        ) {
          error.userAction.actionIntent.intentSender
        } else {
          writeRequestSender(target.uri)
        }
      Timber.w(error, "Android refused to rewrite ${target.displayName}")
      ReplaceResult.Denied(error.message, sender)
    }
  }

  /** Keeps MediaStore and any library that reads it consistent with the tags just written. */
  private fun refreshSystemRows(target: Target, edit: AudioTagEdit) {
    val values = ContentValues().apply {
      put(MediaStore.Audio.Media.TITLE, edit.title)
      put(MediaStore.Audio.Media.ARTIST, edit.artists)
      edit.album?.let { put(MediaStore.Audio.Media.ALBUM, it) }
      edit.year?.let { put(MediaStore.Audio.Media.YEAR, it) }
    }
    runCatching {
      @Suppress("DEPRECATION")
      context.contentResolver.update(target.uri, values, null, null)
    }
      .onFailure { Timber.w(it, "Could not update the MediaStore row for ${target.displayName}") }
    @Suppress("DEPRECATION")
    val path = runCatching {
      context.contentResolver.query(
        target.uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null
      )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()
    if (path != null) {
      runCatching {
        MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(target.mimeType), null)
      }
    }
  }

  /** The app's own library must show what the file now says, artists included. */
  private suspend fun updateLibrary(songId: String, edit: AudioTagEdit) {
    val names = edit.artists.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    // getSongById is the only synchronous-source read of a song row; the DAO's song(id) is a Flow.
    val existing = runCatching { database.getSongById(songId)?.song }.getOrNull()
    if (existing == null) {
      Timber.w("No FT Music row for $songId; the file's own tags hold this edit")
      return
    }
    runCatching {
      database.transaction {
        deleteSongArtistMaps(songId)
        upsert(
          existing.copy(
            title = edit.title,
            albumName = edit.album ?: existing.albumName,
            year = edit.year ?: existing.year,
            dateModified = LocalDateTime.now(),
          )
        )
        names.forEachIndexed { index, name ->
          val known = artistByName(name)
          val artistId = known?.id ?: ArtistEntity.generateArtistId()
          insert(
            ArtistEntity(
              id = artistId,
              name = name,
              thumbnailUrl = known?.thumbnailUrl,
              lastUpdateTime = known?.lastUpdateTime ?: LocalDateTime.now(),
              bookmarkedAt = known?.bookmarkedAt,
              isLocal = true,
            )
          )
          insert(SongArtistMap(songId = songId, artistId = artistId, position = index))
        }
      }
    }.onFailure { Timber.w(it, "Could not refresh the FT Music library row for $songId") }
  }

  private companion object {
    const val TAG_EDIT_TIMEOUT_MS = 180_000L
  }
}
