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
import java.security.MessageDigest
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

  private data class LegacyFingerprint(val size: Long, val modified: Long, val sha256: String)
  private data class MediaFingerprint(val size: Long, val modified: Long, val sha256: String, val name: String)
  private data class Published(
    val uri: Uri,
    val fingerprint: LegacyFingerprint? = null,
    val mediaFingerprint: MediaFingerprint? = null,
  ) {
    fun encode(): String = when {
      fingerprint != null -> "$uri\n${fingerprint.size}\n${fingerprint.modified}\n${fingerprint.sha256}"
      mediaFingerprint != null -> "$uri\n${mediaFingerprint.size}\n${mediaFingerprint.modified}\n${mediaFingerprint.sha256}\n${mediaFingerprint.name}"
      else -> uri.toString()
    }
  }

  private val preferences = context.getSharedPreferences("ft_shared_downloads", Context.MODE_PRIVATE)
  private val pendingRemovals = context.getSharedPreferences("ft_shared_pending_removals", Context.MODE_PRIVATE)
  private val lock = Mutex()

  fun markRemovalPending(id: String) { pendingRemovals.edit().putBoolean(id, true).commit() }
  fun pendingRemovalIds(): Set<String> = pendingRemovals.all.keys.toSet()

  fun requiresLegacyPermission(): Boolean = Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q &&
    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

  fun ids(): Set<String> = preferences.all.keys.toSet()
  fun hasRecord(id: String): Boolean = preferences.contains(id)
  fun recordedUri(id: String): Uri? = preferences.getString(id, null)?.substringBefore('\n')?.let(Uri::parse)

  private fun legacyFingerprint(id: String): LegacyFingerprint? {
    val parts = preferences.getString(id, null)?.split('\n') ?: return null
    if (parts.size != 4) return null
    val size = parts[1].toLongOrNull() ?: return null
    val modified = parts[2].toLongOrNull() ?: return null
    if (size <= 0 || modified <= 0 || !parts[3].matches(Regex("[0-9a-f]{64}"))) return null
    return LegacyFingerprint(size, modified, parts[3])
  }

  private fun mediaFingerprint(id: String): MediaFingerprint? {
    val parts = preferences.getString(id, null)?.split('\n') ?: return null
    if (parts.size != 5) return null
    val size = parts[1].toLongOrNull() ?: return null
    val modified = parts[2].toLongOrNull() ?: return null
    if (size <= 0 || modified < 0 || !parts[3].matches(Regex("[0-9a-f]{64}")) || parts[4].isBlank()) return null
    return MediaFingerprint(size, modified, parts[3], parts[4])
  }

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
      if (!file.exists()) return FileState.MISSING
      if (!file.canRead()) return FileState.UNKNOWN
      val expected = legacyFingerprint(id) ?: return FileState.UNKNOWN
      // If the original was replaced or edited, forget the association without deleting the new file.
      if (file.length() != expected.size || file.lastModified() != expected.modified) return FileState.MISSING
      return FileState.PRESENT
    }
    if (Build.VERSION.SDK_INT < 29 || uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY ||
      uri.lastPathSegment?.toLongOrNull() == null) return FileState.UNKNOWN
    val expected = mediaFingerprint(id) ?: return FileState.UNKNOWN
    return try {
      val columns = arrayOf(
        MediaStore.MediaColumns._ID, MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED,
      )
      context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return FileState.MISSING
        val owner = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.OWNER_PACKAGE_NAME))
        val folder = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)).orEmpty().trimEnd('/')
        // Never let a recycled ID, corrupt record or a moved file authorize deletion elsewhere.
        if (owner != context.packageName || folder != "${Environment.DIRECTORY_DOWNLOADS}/FT Music") return FileState.UNKNOWN
        val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)).orEmpty()
        val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))
        val modified = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED))
        if (name != expected.name || size != expected.size ||
          (expected.modified > 0 && modified > 0 && modified != expected.modified)) return FileState.MISSING
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

      var published: Published? = null
      try {
        published = if (Build.VERSION.SDK_INT >= 29) {
          publishMediaStore(name, format.mimeType, spans.map { it.file!! }, knownLength)
        } else {
          publishLegacy(name, format.mimeType, spans.map { it.file!! }, knownLength)
        }
        // Persist the association before discarding a single private cache byte.
        check(preferences.edit().putString(id, requireNotNull(published).encode()).commit())
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
        published?.let { discardPublished(it) }
        throw e
      } catch (e: Exception) {
        Timber.w(e, "Unable to publish FT download; keeping its private cache")
        published?.let { discardPublished(it) }
        PublishResult.UNAVAILABLE
      }
    }
  }

  private suspend fun copyFiles(files: List<File>, output: OutputStream, expected: Long): String {
    var written = 0L
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    files.forEach { file ->
      file.inputStream().use { input ->
        while (true) {
          currentCoroutineContext().ensureActive()
          val count = input.read(buffer)
          if (count < 0) break
          output.write(buffer, 0, count)
          digest.update(buffer, 0, count)
          written += count
        }
      }
    }
    output.flush()
    check(written == expected) { "Downloaded cache is incomplete" }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }

  private suspend fun publishMediaStore(name: String, mime: String, files: List<File>, size: Long): Published {
    val values = ContentValues().apply {
      put(MediaStore.MediaColumns.DISPLAY_NAME, name)
      put(MediaStore.MediaColumns.MIME_TYPE, mime.substringBefore(';'))
      put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/FT Music/")
      put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
      ?: error("Unable to create shared download")
    var madePublic = false
    try {
      val output = context.contentResolver.openOutputStream(uri, "w") ?: error("Unable to write shared download")
      val sha256 = output.use { copyFiles(files, it, size) }
      val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
      check(context.contentResolver.update(uri, ready, null, null) == 1)
      madePublic = true
      val fingerprint = context.contentResolver.query(uri, arrayOf(
        MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED,
      ), null, null, null)?.use { cursor ->
        check(cursor.moveToFirst())
        MediaFingerprint(cursor.getLong(1), cursor.getLong(2).coerceAtLeast(0), sha256, cursor.getString(0).orEmpty())
      } ?: error("Unable to inspect shared download")
      check(fingerprint.size == size && fingerprint.name.isNotBlank() && '\n' !in fingerprint.name)
      // Verify the published bytes before ever discarding the original private cache.
      check(matchesMediaFile(uri, fingerprint)) { "Shared download did not match the private copy" }
      return Published(uri, mediaFingerprint = fingerprint)
    } catch (e: Exception) {
      // Pending rows are not visible to other apps. Once public, leave an unverifiable
      // copy alone rather than risk removing a file the user may have already edited.
      if (!madePublic) discardPublished(Published(uri))
      throw e
    }
  }

  @Suppress("DEPRECATION")
  private suspend fun publishLegacy(name: String, mime: String, files: List<File>, size: Long): Published {
    val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FT Music")
    check(directory.isDirectory || directory.mkdirs())
    var destination: File? = null
    var createdIdentity: Pair<Long, Long>? = null
    var completeFingerprint: LegacyFingerprint? = null
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
      val sha256 = FileOutputStream(opened).use { output ->
        val stat = Os.fstat(opened)
        createdIdentity = stat.st_dev to stat.st_ino
        copyFiles(files, output, size)
      }
      check(candidate.length() == size && candidate.lastModified() > 0)
      val fingerprint = LegacyFingerprint(size, candidate.lastModified(), sha256)
      completeFingerprint = fingerprint
      val uri = Uri.fromFile(candidate)
      check(matchesLegacyFile(uri, fingerprint))
      MediaScannerConnection.scanFile(context, arrayOf(candidate.absolutePath), arrayOf(mime), null)
      return Published(uri, fingerprint)
    } catch (e: Exception) {
      // Only remove the inode we created, not a file someone replaced it with.
      destination?.let { file ->
        val current = runCatching { Os.stat(file.absolutePath) }.getOrNull()
        val unmodified = completeFingerprint?.let { matchesLegacyFile(Uri.fromFile(file), it) } ?: true
        if (current != null && createdIdentity == (current.st_dev to current.st_ino) && unmodified) file.delete()
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
      if (fileState == FileState.PRESENT) {
        if (uri.scheme == "file") {
          val expected = legacyFingerprint(id) ?: return@withLock false
          if (!matchesLegacyFile(uri, expected)) return@withLock false
        } else {
          val expected = mediaFingerprint(id) ?: return@withLock false
          if (!matchesMediaFile(uri, expected)) return@withLock false
        }
        if (!deleteUri(uri)) return@withLock false
      }
      val removed = preferences.edit().remove(id).commit()
      if (removed) pendingRemovals.edit().remove(id).commit()
      removed
    }
  }

  private fun matchesMediaFile(uri: Uri, expected: MediaFingerprint): Boolean {
    if (Build.VERSION.SDK_INT < 29 || uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY) return false
    fun metadataMatches(): Boolean = context.contentResolver.query(uri, arrayOf(
      MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
      MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED,
    ), null, null, null)?.use { cursor ->
      if (!cursor.moveToFirst()) return@use false
      val modified = cursor.getLong(4)
      cursor.getString(0) == context.packageName &&
        cursor.getString(1).orEmpty().trimEnd('/') == "${Environment.DIRECTORY_DOWNLOADS}/FT Music" &&
        cursor.getString(2) == expected.name && cursor.getLong(3) == expected.size &&
        (expected.modified <= 0 || modified <= 0 || modified == expected.modified)
    } ?: false

    return try {
      if (!metadataMatches()) return false
      val digest = MessageDigest.getInstance("SHA-256")
      context.contentResolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
          val count = input.read(buffer)
          if (count < 0) break
          digest.update(buffer, 0, count)
        }
      } ?: return false
      val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
      actual == expected.sha256 && metadataMatches()
    } catch (_: Exception) { false }
  }

  private fun matchesLegacyFile(uri: Uri, expected: LegacyFingerprint): Boolean {
    return try {
      val file = uri.path?.let(::File) ?: return false
      if (!file.isFile || file.length() != expected.size || file.lastModified() != expected.modified) return false
      val before = Os.stat(file.absolutePath)
      val digest = MessageDigest.getInstance("SHA-256")
      file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
          val count = input.read(buffer)
          if (count < 0) break
          digest.update(buffer, 0, count)
        }
      }
      val after = Os.stat(file.absolutePath)
      val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
      before.st_dev == after.st_dev && before.st_ino == after.st_ino &&
        file.length() == expected.size && file.lastModified() == expected.modified && actual == expected.sha256
    } catch (_: Exception) { false }
  }

  private fun discardPublished(published: Published) {
    val uri = published.uri
    val safe = if (uri.scheme == "file") {
      published.fingerprint?.let { matchesLegacyFile(uri, it) } == true
    } else if (Build.VERSION.SDK_INT >= 29 && uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY) {
      published.mediaFingerprint?.let { matchesMediaFile(uri, it) } ?: try {
        // Only used for a newly-created, still-pending row on the failure path.
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use {
          it.moveToFirst() && it.getString(0) == context.packageName &&
            it.getString(1).orEmpty().trimEnd('/') == "${Environment.DIRECTORY_DOWNLOADS}/FT Music"
        } ?: false
      } catch (_: Exception) { false }
    } else false
    if (safe) deleteUri(uri)
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
