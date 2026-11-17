package echo.music.iad1tya.playback

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Lossless container swap for published downloads.
 *
 * YouTube's best audio is Opus inside a WebM container, and Android's MediaStore derives a file's
 * MIME type from its extension: every `.webm` copy of a pure-audio song is filed under Videos by
 * every file manager, behind a video icon. Re-publishing the same packets - no decode, no
 * re-encode, bytes of audio untouched - in an Ogg container makes the copy what it already is:
 * audio. A remux that cannot run, times out or fails simply leaves the original container in
 * place, exactly as published before this existed.
 */
object OpusRepublisher {
  private const val REMUX_TIMEOUT_MS = 120_000L

  /** Stream-copy arguments; an array, never a command string, so nothing is re-split or escaped. */
  fun remuxArguments(input: String, output: String): List<String> =
    listOf("-hide_banner", "-i", input, "-c", "copy", output)

  /** Ogg pages start with the magic `OggS`; a WebM file starts with an EBML header instead. */
  fun looksLikeOgg(file: File): Boolean {
    val magic = ByteArray(4)
    return try {
      file.inputStream().use { input ->
        var read = 0
        while (read < magic.size) {
          val count = input.read(magic, read, magic.size - read)
          if (count < 0) return false
          read += count
        }
      }
      magic.contentEquals("OggS".toByteArray(Charsets.US_ASCII))
    } catch (_: Exception) {
      false
    }
  }

  /** @return true when [output] now holds [input]'s audio packets in an Ogg container. */
  suspend fun remux(input: File, output: File): Boolean {
    if (output.exists() && !output.delete()) return false
    return try {
      withTimeoutOrNull(REMUX_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
          val session = FFmpegKit.executeWithArgumentsAsync(
            remuxArguments(input.absolutePath, output.absolutePath).toTypedArray()
          ) { completed ->
            if (!continuation.isActive) return@executeWithArgumentsAsync
            val returnCode = completed.returnCode
            val remuxed = returnCode != null && ReturnCode.isSuccess(returnCode) &&
              output.length() > 0 && looksLikeOgg(output)
            continuation.resume(remuxed)
          }
          continuation.invokeOnCancellation { runCatching { FFmpegKit.cancel(session.sessionId) } }
        }
      } ?: false
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      Timber.w(error, "Opus remux could not run; keeping the original container")
      false
    }
  }
}
