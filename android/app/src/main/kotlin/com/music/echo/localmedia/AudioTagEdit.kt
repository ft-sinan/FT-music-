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

private fun String.ffmpegEscape(): String = replace("'", "'\\''")

/**
 * Builds the ffmpeg command that writes [edit] into [input] and writes the result to [output].
 *
 * The audio stream is always copied (`-c:a copy`), never decoded or re-encoded, so tagging cannot
 * cost quality. When no new cover is supplied every existing stream is mapped through, which keeps
 * a cover the file already had; supplying one replaces it. A container that cannot hold a picture
 * gets its text tags and the caller says so.
 */
internal fun buildTagCommand(
  input: String,
  output: String,
  edit: AudioTagEdit,
  container: TagContainer,
  coverPath: String?,
): String {
  val metadata = buildString {
    append(" -metadata title='${edit.title.ffmpegEscape()}'")
    append(" -metadata artist='${edit.artists.ffmpegEscape()}'")
    edit.album?.takeIf { it.isNotBlank() }?.let {
      append(" -metadata album='${it.ffmpegEscape()}'")
    }
    edit.year?.takeIf { it > 0 }?.let {
      append(" -metadata date='$it'")
      // MP3 and MP4 readers look at different year fields; write both where they are valid.
      if (container == TagContainer.MP3 || container == TagContainer.MATROSKA) {
        append(" -metadata year='$it'")
      }
    }
  }
  val mp3Id3 = if (container == TagContainer.MP3) " -id3v2_version 3" else ""
  val embedCover = coverPath != null && container.supportsCover
  return if (embedCover) {
    val codec = if (edit.coverMimeType?.contains("png", ignoreCase = true) == true) "png" else "mjpeg"
    "-y -i '${input.ffmpegEscape()}' -i '${coverPath!!.ffmpegEscape()}'" +
      " -map 0:a:0 -map 1:v:0 -c:a copy -c:v $codec -disposition:v attached_pic" +
      "$mp3Id3$metadata" +
      " -metadata:s:v title='Album cover' -metadata:s:v comment='Cover (front)'" +
      " '${output.ffmpegEscape()}'"
  } else {
    // Keep every stream the file already has - including an existing cover - byte for byte.
    "-y -i '${input.ffmpegEscape()}' -map 0 -c copy$mp3Id3$metadata '${output.ffmpegEscape()}'"
  }
}
