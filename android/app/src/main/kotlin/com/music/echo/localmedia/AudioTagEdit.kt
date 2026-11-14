package echo.music.iad1tya.localmedia

import java.util.Locale

/** What the user asked to write into a local audio file's tags. Audio bytes are never re-encoded. */
data class AudioTagEdit(
  val title: String,
  val artists: String,
  val album: String?,
  val year: Int?,
  /** JPEG or PNG bytes. Null keeps whatever cover the file already has. */
  val coverBytes: ByteArray? = null,
  val coverMimeType: String? = null,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is AudioTagEdit) return false
    return title == other.title && artists == other.artists && album == other.album &&
      year == other.year && coverBytes.contentEquals(other.coverBytes) &&
      coverMimeType == other.coverMimeType
  }

  override fun hashCode(): Int {
    var result = title.hashCode()
    result = 31 * result + artists.hashCode()
    result = 31 * result + (album?.hashCode() ?: 0)
    result = 31 * result + (year ?: 0)
    result = 31 * result + (coverBytes?.contentHashCode() ?: 0)
    result = 31 * result + (coverMimeType?.hashCode() ?: 0)
    return result
  }
}

/**
 * Containers FT Music can retag. Chosen from what the bundled ffmpeg build really muxes, not from
 * what would be nice to support: a container listed here must accept text tags, and only the ones
 * marked [supportsCover] accept an embedded picture.
 */
enum class TagContainer(val supportsCover: Boolean) {
  MP3(true),
  MP4(true),
  FLAC(true),
  OGG(false),
  WAV(false),
  MATROSKA(false),
  ;

  companion object {
    /** Null means "cannot be retagged", which the UI reports instead of guessing. */
    fun from(mimeType: String?, displayName: String?): TagContainer? {
      val extension =
        displayName
          ?.substringAfterLast('.', missingDelimiterValue = "")
          ?.lowercase(Locale.ROOT)
          .orEmpty()
      val mime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
      return when {
        mime == "audio/mpeg" || mime == "audio/x-mpeg" || extension == "mp3" -> MP3
        mime == "audio/flac" || mime == "audio/x-flac" || extension == "flac" -> FLAC
        mime == "audio/mp4" || mime == "audio/x-m4a" || mime == "video/mp4" ||
          extension in setOf("m4a", "m4b", "m4p", "mp4") -> MP4
        mime == "audio/ogg" || mime == "application/ogg" || mime == "audio/opus" ||
          extension in setOf("ogg", "oga", "opus") -> OGG
        mime == "audio/wav" || mime == "audio/x-wav" || mime == "audio/wave" ||
          extension == "wav" -> WAV
        mime == "audio/webm" || mime == "video/webm" || mime == "audio/x-matroska" ||
          mime == "audio/x-mka" || extension in setOf("webm", "weba", "mka") -> MATROSKA
        else -> null
      }
    }
  }
}

/** Why a tag edit could not be written. Every reason is reported to the user, never swallowed. */
enum class AudioTagEditFailure {
  /** The container is one FT Music cannot retag, so the file was left exactly as it was. */
  UNSUPPORTED_FORMAT,

  /** Android would not let FT Music write this file. Nothing was changed. */
  PERMISSION_DENIED,

  /** The file could not be found or read. */
  NO_FILE,

  /** The tagging step itself failed. The original file is untouched. */
  WRITE_FAILED,

  /** The rewritten file did not read back correctly, so it was discarded. */
  VERIFY_FAILED,
}

sealed interface AudioTagEditResult {
  /** Tags are in the file, the audio stream is the same stream, and the copy was verified. */
  data class Written(val container: TagContainer, val coverWritten: Boolean) : AudioTagEditResult

  data class Failed(val reason: AudioTagEditFailure, val detail: String?) : AudioTagEditResult
}

/**
 * Builds the ffmpeg argument list that writes [edit] into [input] and writes the result to
 * [output], one argv element per flag and per value.
 *
 * This is an argument list and not a command string on purpose. `FFmpegKit.execute(String)` splits
 * its string with `FFmpegKitConfig.parseArguments`, which treats `'` as a quote delimiter and does
 * not implement the POSIX `'\''` idiom, so hand-escaping a value leaves a literal backslash in
 * front of every apostrophe. A song titled "Don't Stop Me Now" was therefore tagged
 * "Don\'t Stop Me Now". Passing argv means no parser ever sees the value, so nothing needs
 * escaping and nothing can be mangled. Upstream fixed the same bug in `AudioExportService` this
 * way in its security-audit commit `10ecaef1`.
 *
 * The audio stream is always copied (`-c:a copy`), never decoded or re-encoded, so tagging cannot
 * cost quality. When no new cover is supplied every existing stream is mapped through, which keeps
 * a cover the file already had; supplying one replaces it. A container that cannot hold a picture
 * gets its text tags and the caller says so.
 */
internal fun buildTagArguments(
  input: String,
  output: String,
  edit: AudioTagEdit,
  container: TagContainer,
  coverPath: String?,
): List<String> {
  val metadata = mutableListOf<String>()
  metadata += listOf("-metadata", "title=${edit.title}")
  metadata += listOf("-metadata", "artist=${edit.artists}")
  edit.album?.takeIf { it.isNotBlank() }?.let { metadata += listOf("-metadata", "album=$it") }
  edit.year?.takeIf { it > 0 }?.let {
    metadata += listOf("-metadata", "date=$it")
    // MP3 and Matroska readers look at different year fields; write both where they are valid.
    if (container == TagContainer.MP3 || container == TagContainer.MATROSKA) {
      metadata += listOf("-metadata", "year=$it")
    }
  }
  val mp3Id3 = if (container == TagContainer.MP3) listOf("-id3v2_version", "3") else emptyList()
  val embedCover = coverPath != null && container.supportsCover
  return if (embedCover) {
    val codec = if (edit.coverMimeType?.contains("png", ignoreCase = true) == true) "png" else "mjpeg"
    listOf("-y", "-i", input, "-i", coverPath!!) +
      listOf(
        "-map", "0:a:0",
        "-map", "1:v:0",
        "-c:a", "copy",
        "-c:v", codec,
        "-disposition:v", "attached_pic",
      ) +
      mp3Id3 +
      metadata +
      listOf("-metadata:s:v", "title=Album cover", "-metadata:s:v", "comment=Cover (front)") +
      output
  } else {
    // Keep every stream the file already has - including an existing cover - byte for byte.
    listOf("-y", "-i", input, "-map", "0", "-c", "copy") + mp3Id3 + metadata + output
  }
}
