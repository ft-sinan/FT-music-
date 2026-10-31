package echo.music.iad1tya.playback

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import dagger.hilt.android.qualifiers.ApplicationContext
import echo.music.iad1tya.db.MusicDatabase
import echo.music.iad1tya.di.DownloadCache
import java.io.File
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Owns only files created by FT Music; it never removes arbitrary device audio. */
@Singleton
class SharedDownloadStore @Inject constructor(
  @ApplicationContext private val context: Context,
  @DownloadCache private val cache: SimpleCache,
  private val database: MusicDatabase,
  private val activePlayback: ActivePlaybackTracker,
) {
  enum class FileState { PRESENT, MISSING, UNKNOWN }
  enum class PublishResult { PUBLISHED, ALREADY_PRESENT, NEEDS_PERMISSION, UNAVAILABLE }

  private val preferences = context.getSharedPreferences("ft_shared_downloads", Context.MODE_PRIVATE)
  private val pendingRemovals = context.getSharedPreferences("ft_shared_pending_removals", Context.MODE_PRIVATE)
  private val lock = Mutex()

  fun markRemovalPending(id: String) { pendingRemovals.edit().putBoolean(id, true).commit() }
  fun pendingRemovalIds(): Set<String> = pendingRemovals.all.keys.toSet()

  fun requiresLegacyPermission(): Boolean = Build.VERSION.SDK_INT <= 28 &&
    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

  fun ids(): Set<String> = preferences.all.keys.toSet()
  fun hasRecord(id: String): Boolean = preferences.contains(id)
  fun recordedUri(id: String): Uri? = preferences.getString(id, null)?.let(Uri::parse)

  fun totalSizeBytes(): Long = ids().sumOf { id ->
    if (state(id) != FileState.PRESENT) return@sumOf 0L
    val uri = recordedUri(id) ?: return@sumOf 0L
    try {
      if (uri.scheme == "file") uri.path?.let { File(it).length() } ?: 0L
      else context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use {
        if (it.moveToFirst()) it.getLong(0).coerceAtLeast(0L) else 0L
      } ?: 0L
    } catch (_: Exception) { 0L }
  }

  /** UNKNOWN is deliberately not treated as deletion (permission/provider/storage may be unavailable). */
  fun state(id: String): FileState {
    val uri = recordedUri(id) ?: return FileState.UNKNOWN
    if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) return FileState.UNKNOWN
    if (uri.scheme == "file") {
      if (requiresLegacyPermission()) return FileState.UNKNOWN
      val file = uri.path?.let(::File) ?: return FileState.UNKNOWN
      @Suppress("DEPRECATION")
      val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FT Music")
      val withinFolder = try { file.canonicalPath.startsWith(root.canonicalPath + File.separator) } catch (_: Exception) { false }
      if (!withinFolder) return FileState.UNKNOWN
      return if (!file.exists()) FileState.MISSING else if (file.canRead()) FileState.PRESENT else FileState.UNKNOWN
    }
    if (Build.VERSION.SDK_INT < 29 || uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY ||
      uri.lastPathSegment?.toLongOrNull() == null) return FileState.UNKNOWN
    return try {
      val columns = if (Build.VERSION.SDK_INT >= 29) {
        arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.RELATIVE_PATH)
      } else arrayOf(MediaStore.MediaColumns._ID)
      context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return FileState.MISSING
        if (Build.VERSION.SDK_INT >= 29) {
          val owner = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.OWNER_PACKAGE_NAME))
          val folder = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)).orEmpty().trimEnd('/')
          // Never let a recycled ID, corrupt record or a moved file authorize deletion elsewhere.
          if (owner != context.packageName || folder != "${Environment.DIRECTORY_DOWNLOADS}/FT Music") return FileState.UNKNOWN
        }
      } ?: return FileState.UNKNOWN
      context.contentResolver.openFileDescriptor(uri, "r")?.use { FileState.PRESENT } ?: FileState.UNKNOWN
    } catch (_: FileNotFoundException) {
      FileState.MISSING
    } catch (_: SecurityException) {
      FileState.UNKNOWN
    } catch (_: Exception) {
      FileState.UNKNOWN
    }
  }

  suspend fun publish(download: Download): PublishResult = withContext(Dispatchers.IO) {
    lock.withLock {
      val id = download.request.id
      if (hasRecord(id)) return@withLock PublishResult.ALREADY_PRESENT
      if (requiresLegacyPermission()) return@withLock PublishResult.NEEDS_PERMISSION
      if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) return@withLock PublishResult.UNAVAILABLE
      val format = database.format(id).firstOrNull() ?: return@withLock PublishResult.UNAVAILABLE
      val ext = SharedDownloadNames.extension(format.mimeType) ?: return@withLock PublishResult.UNAVAILABLE
      val song = database.song(id).firstOrNull()
      val title = song?.title ?: String(download.request.data, Charsets.UTF_8)
      val artist = song?.artists?.joinToString(", ") { it.name }.orEmpty()
      val name = SharedDownloadNames.fileName(id, title, artist, ext)
      val knownLength = download.contentLength.takeIf { it > 0 }
        ?: ContentMetadata.getContentLength(cache.getContentMetadata(id)).takeIf { it > 0 && it != C.LENGTH_UNSET.toLong() }
        ?: format.contentLength.takeIf { it > 0 }
        ?: return@withLock PublishResult.UNAVAILABLE
      val spans = cache.getCachedSpans(id).sortedBy { it.position }
      var position = 0L
      for (span in spans) {
        if (span.position != position || span.file == null || span.length <= 0) return@withLock PublishResult.UNAVAILABLE
        position += span.length
      }
      if (position != knownLength) return@withLock PublishResult.UNAVAILABLE

      var publishedUri: Uri? = null
      try {
        publishedUri = if (Build.VERSION.SDK_INT >= 29) {
          publishMediaStore(name, format.mimeType, spans.map { it.file!! }, knownLength)
        } else {
          publishLegacy(name, format.mimeType, spans.map { it.file!! }, knownLength)
        }
        // Persist the association before discarding a single private cache byte.
        check(preferences.edit().putString(id, publishedUri.toString()).commit())
        // Once registered, the shared file is the authoritative copy. A cache cleanup
        // failure is harmless and must never cause us to delete that registered file.
        activePlayback.ifNotRetained(id) {
          try {
            cache.removeResource(id)
          } catch (e: Exception) {
            Timber.w(e, "Unable to remove redundant private download cache")
          }
        }
        PublishResult.PUBLISHED
      } catch (e: kotlinx.coroutines.CancellationException) {
        publishedUri?.let { deleteUri(it) }
        throw e
      } catch (e: Exception) {
        Timber.w(e, "Unable to publish FT download; keeping its private cache")
        publishedUri?.let { deleteUri(it) }
        PublishResult.UNAVAILABLE
      }
    }
  }

  private suspend fun copyFiles(files: List<File>, output: OutputStream, expected: Long) {
    var written = 0L
    val buffer = ByteArray(64 * 1024)
    files.forEach { file ->
      file.inputStream().use { input ->
        while (true) {
          currentCoroutineContext().ensureActive()
          val count = input.read(buffer)
          if (count < 0) break
          output.write(buffer, 0, count)
          written += count
        }
      }
    }
    output.flush()
    check(written == expected) { "Downloaded cache is incomplete" }
  }

  private suspend fun publishMediaStore(name: String, mime: String, files: List<File>, size: Long): Uri {
    val values = ContentValues().apply {
      put(MediaStore.MediaColumns.DISPLAY_NAME, name)
      put(MediaStore.MediaColumns.MIME_TYPE, mime.substringBefore(';'))
      put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/FT Music/")
      put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
      ?: error("Unable to create shared download")
    try {
      val output = context.contentResolver.openOutputStream(uri, "w") ?: error("Unable to write shared download")
      output.use { copyFiles(files, it, size) }
      val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
      check(context.contentResolver.update(uri, ready, null, null) == 1)
      return uri
    } catch (e: Exception) {
      deleteUri(uri)
      throw e
    }
  }

  @Suppress("DEPRECATION")
  private suspend fun publishLegacy(name: String, mime: String, files: List<File>, size: Long): Uri {
    val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FT Music")
    check(directory.isDirectory || directory.mkdirs())
    var destination: File? = null
    var createdIdentity: Pair<Long, Long>? = null
    try {
      val dot = name.lastIndexOf('.')
      val stem = if (dot >= 0) name.substring(0, dot) else name
      val suffix = if (dot >= 0) name.substring(dot) else ""
      var candidate = File(directory, name)
      var number = 1
      var descriptor: FileDescriptor? = null
      while (descriptor == null) {
        try {
          // O_EXCL creates the destination and opens it as one operation. Unlike
          // exists()/rename(), it cannot replace an unrelated file at this path.
          descriptor = Os.open(candidate.absolutePath, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL, 0x1B6)
        } catch (e: ErrnoException) {
          if (e.errno != OsConstants.EEXIST) throw e
          candidate = File(directory, "$stem (${number++})$suffix")
        }
      }
      destination = candidate
      val opened = requireNotNull(descriptor)
      FileOutputStream(opened).use { output ->
        val stat = Os.fstat(opened)
        createdIdentity = stat.st_dev to stat.st_ino
        copyFiles(files, output, size)
      }
      MediaScannerConnection.scanFile(context, arrayOf(candidate.absolutePath), arrayOf(mime), null)
      return Uri.fromFile(candidate)
    } catch (e: Exception) {
      // Only remove the inode we created, not a file someone replaced it with.
      destination?.let { file ->
        val current = runCatching { Os.stat(file.absolutePath) }.getOrNull()
        if (current != null && createdIdentity == (current.st_dev to current.st_ino)) file.delete()
      }
      throw e
    }
  }

  suspend fun cleanupRedundantCache(id: String) = withContext(Dispatchers.IO) {
    lock.withLock {
      if (state(id) == FileState.PRESENT) {
        activePlayback.ifNotRetained(id) {
          try { cache.removeResource(id) }
          catch (e: Exception) { Timber.w(e, "Unable to clean private download cache") }
        }
      }
    }
  }

  suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
    lock.withLock {
      val uri = recordedUri(id)
      if (uri == null) {
        pendingRemovals.edit().remove(id).commit()
        return@withLock true
      }
      val fileState = state(id)
      if (fileState == FileState.UNKNOWN) return@withLock false
      if (fileState == FileState.PRESENT && !deleteUri(uri)) return@withLock false
      val removed = preferences.edit().remove(id).commit()
      if (removed) pendingRemovals.edit().remove(id).commit()
      removed
    }
  }

  private fun deleteUri(uri: Uri): Boolean {
    return try {
      if (uri.scheme == "file") {
        val file = uri.path?.let(::File) ?: return false
        val removed = !file.exists() || file.delete()
        if (removed) MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
        removed
      } else {
        context.contentResolver.delete(uri, null, null) > 0
      }
    } catch (_: Exception) {
      false
    }
  }
}
