package echo.music.iad1tya.playback

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import echo.music.iad1tya.constants.DownloadQuality
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/** Identifies the representation selected for one download, not just the song. */
internal data class DownloadSourceIdentity(
  val itag: Int?,
  val mimeType: String,
  val length: Long?,
  val revision: String?,
) {
  companion object {
    fun from(url: String, itag: Int, mimeType: String, length: Long?): DownloadSourceIdentity {
      val uri = Uri.parse(url)
      return DownloadSourceIdentity(
        itag = itag.takeIf { it > 0 } ?: uri.getQueryParameter("itag")?.toIntOrNull()?.takeIf { it > 0 },
        mimeType = mimeType.substringBefore(';').trim().lowercase(Locale.ROOT),
        length = length?.takeIf { it > 0 } ?: uri.getQueryParameter("clen")?.toLongOrNull()?.takeIf { it > 0 },
        revision = uri.getQueryParameter("lmt")?.takeIf { it.isNotBlank() },
      )
    }
  }
}

/** Do not append to an existing partial file unless the new source can be identified as the same format. */
internal fun canResumeDownload(old: DownloadSourceIdentity, new: DownloadSourceIdentity): Boolean =
  old.itag != null && old.itag == new.itag &&
    old.mimeType.isNotBlank() && old.mimeType == new.mimeType &&
    old.length != null && old.length > 0 && old.length == new.length &&
    (old.revision == null || old.revision == new.revision)

/**
 * The streaming format row is shared with the player and can change during a download. Keep the
 * download's own selection and source identity until it is removed. Commit before fetching bytes,
 * so a process death cannot leave an unidentifiable newly-created partial download.
 */
@Singleton
class DownloadSourceRegistry @Inject constructor(@ApplicationContext context: Context) {
  private val preferences = context.getSharedPreferences("ft_download_sources", Context.MODE_PRIVATE)

  private data class Record(val quality: DownloadQuality, val source: DownloadSourceIdentity?)

  private fun read(id: String): Record? {
    val raw = preferences.getString(id, null) ?: return null
    try {
      val json = JSONObject(raw)
      val quality = DownloadQuality.valueOf(json.getString("quality")).canonical()
      val item = json.optJSONObject("source")
      val source = item?.let {
        DownloadSourceIdentity(
          itag = it.optInt("itag", 0).takeIf { value -> value > 0 },
          mimeType = it.getString("mime"),
          length = it.optLong("length", 0).takeIf { value -> value > 0 },
          revision = it.optString("revision", "").takeIf { value -> value.isNotBlank() },
        )
      }
      return Record(quality, source)
    } catch (e: Exception) {
      // Never guess a format if its persistent identity is damaged.
      throw IOException("The unfinished download's source identity is unavailable", e)
    }
  }

  private fun write(id: String, record: Record) {
    val json = JSONObject().put("quality", record.quality.canonical().name)
    record.source?.let { source ->
      json.put("source", JSONObject().apply {
        source.itag?.let { put("itag", it) }
        put("mime", source.mimeType)
        source.length?.let { put("length", it) }
        source.revision?.let { put("revision", it) }
      })
    }
    if (!preferences.edit().putString(id, json.toString()).commit()) {
      throw IOException("Could not save the download's source identity")
    }
  }

  /** A changed setting affects a new download, not a download that has already started. */
  @Synchronized
  internal fun qualityFor(id: String, requested: DownloadQuality): DownloadQuality {
    val existing = read(id)
    if (existing != null) return existing.quality
    return requested.canonical().also { write(id, Record(it, null)) }
  }

  /** Returns false without touching cached bytes if an existing partial cannot be safely resumed. */
  @Synchronized
  internal fun acceptSource(id: String, source: DownloadSourceIdentity, hasCachedBytes: Boolean): Boolean {
    val record = read(id) ?: throw IOException("The download has no locked quality")
    if (hasCachedBytes && (record.source == null || !canResumeDownload(record.source, source))) return false
    write(id, record.copy(source = source))
    return true
  }

  @Synchronized
  internal fun sourceFor(id: String): DownloadSourceIdentity? = read(id)?.source

  @Synchronized
  internal fun hasRecord(id: String): Boolean = preferences.contains(id)

  /** Clean up only records for downloads no longer present in Media3's persistent index. */
  @Synchronized
  internal fun prune(activeIds: Set<String>) {
    val orphaned = preferences.all.keys - activeIds
    if (orphaned.isEmpty()) return
    val editor = preferences.edit()
    orphaned.forEach(editor::remove)
    if (!editor.commit()) throw IOException("Could not clear old download identities")
  }

  @Synchronized
  internal fun remove(id: String) {
    if (!preferences.edit().remove(id).commit()) throw IOException("Could not clear the download's source identity")
  }
}
