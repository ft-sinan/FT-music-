package echo.music.iad1tya.utils

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.core.content.FileProvider
import echo.music.iad1tya.playback.SharedDownloadStore
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ShareableAudio(val uri: Uri, val mimeType: String)

/** Only real YouTube video IDs are shareable as YouTube Music links. */
internal fun youtubeSongLink(id: String): String? =
  id.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
    ?.let { "https://music.youtube.com/watch?v=$it" }

/**
 * Returns an accessible device audio file or an FT published download whose fingerprint still
 * matches. A local file's full playability cannot be guaranteed by a container check alone.
 * Partial Media3 cache spans are deliberately not exposed as files. Complete private downloads
 * are copied and verified separately before this check. Local file:// paths are converted to
 * temporary read-only content:// grants through the existing FileProvider.
 */
internal suspend fun findShareableAudio(
  context: Context,
  store: SharedDownloadStore,
  id: String,
  mimeHint: String? = null,
): ShareableAudio? = withContext(Dispatchers.IO) {
  try {
    val raw = if (id.isLocalMediaId()) {
      Uri.parse(id)
    } else {
      store.verifiedUriForSharing(id) ?: return@withContext null
    }
    val uri = when (raw.scheme?.lowercase(Locale.ROOT)) {
      "content" -> raw
      "file" -> {
        val file = raw.path?.let(::File) ?: return@withContext null
        if (!file.isFile || !file.canRead() || file.length() <= 0) return@withContext null
        FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", file)
      }
      else -> return@withContext null
    }
    context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
      if (descriptor.statSize == 0L) return@withContext null
    } ?: return@withContext null

    // A contiguous byte count alone is not enough: verify that Android can see an audio track.
    val extractor = MediaExtractor()
    val mediaMime = try {
      extractor.setDataSource(context, uri, null)
      (0 until extractor.trackCount)
        .mapNotNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
        .firstOrNull { it.startsWith("audio/") }
    } finally {
      extractor.release()
    } ?: return@withContext null
    val containerMime = context.contentResolver.getType(uri)?.substringBefore(';')
      ?.takeIf { it.startsWith("audio/") }
      ?: mimeHint?.substringBefore(';')?.takeIf { it.startsWith("audio/") }
      ?: mediaMime
    ShareableAudio(uri, containerMime)
  } catch (_: Exception) {
    null
  }
}

internal fun audioShareIntent(context: Context, audio: ShareableAudio): Intent =
  Intent(Intent.ACTION_SEND).apply {
    type = audio.mimeType
    putExtra(Intent.EXTRA_STREAM, audio.uri)
    clipData = ClipData.newUri(context.contentResolver, null, audio.uri)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  }
