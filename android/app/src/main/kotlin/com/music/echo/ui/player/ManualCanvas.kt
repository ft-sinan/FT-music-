package echo.music.iad1tya.ui.player

import android.content.Context
import android.util.AtomicFile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import echo.music.iad1tya.applecanvas.AppleMusicCanvasProvider
import echo.music.iad1tya.canvas.CanvasArtwork
import echo.music.iad1tya.canvas.TidalCanvasProvider
import echo.music.iad1tya.echomusiccanvas.echomusicCanvasProvider
import echo.music.iad1tya.models.MediaMetadata
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class ManualCanvasSelection(val artwork: CanvasArtwork, val generation: Long)

/** Explicit selections are independent of the automatic Canvas preference. */
internal object ManualCanvasSelections {
  private val generation = AtomicLong()
  private val mutableSelections = MutableStateFlow<Map<String, ManualCanvasSelection>>(emptyMap())
  val selections: StateFlow<Map<String, ManualCanvasSelection>> = mutableSelections

  @Synchronized
  fun select(id: String, artwork: CanvasArtwork) {
    val entries = LinkedHashMap(mutableSelections.value)
    entries.remove(id)
    entries[id] = ManualCanvasSelection(artwork, generation.incrementAndGet())
    while (entries.size > 64) entries.remove(entries.keys.first())
    mutableSelections.value = entries
    CanvasArtworkPlaybackCache.put(id, artwork)
  }

  private fun file(context: Context, id: String): File {
    val hash = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return File(context.filesDir, "ft_canvas_$hash.json")
  }

  suspend fun save(context: Context, id: String, artwork: CanvasArtwork): Boolean =
    withContext(Dispatchers.IO) {
      val atomic = AtomicFile(file(context, id))
      var stream: java.io.FileOutputStream? = null
      try {
        stream = atomic.startWrite()
        stream.write(Json.encodeToString(artwork).toByteArray(Charsets.UTF_8))
        atomic.finishWrite(stream)
        true
      } catch (_: Exception) {
        stream?.let(atomic::failWrite)
        false
      }
    }

  suspend fun load(context: Context, id: String): ManualCanvasSelection? =
    withContext(Dispatchers.IO) {
      try {
        val input = file(context, id)
        if (!input.isFile || input.length() !in 1L..65_536L) return@withContext null
        val artwork = Json.decodeFromString<CanvasArtwork>(input.readText())
        safeCanvasUrls(artwork)?.let { ManualCanvasSelection(it, 0L) }
      } catch (_: Exception) {
        null
      }
    }
}

@Composable
internal fun rememberManualCanvas(mediaId: String?): ManualCanvasSelection? {
  val context = LocalContext.current
  val inMemory by ManualCanvasSelections.selections.collectAsState()
  // Keep the ID with an asynchronous disk result, so a previous song cannot flash on a new one.
  val loaded by produceState<Pair<String, ManualCanvasSelection?>?>(null, mediaId) {
    value = mediaId?.let { it to ManualCanvasSelections.load(context, it) }
  }
  return mediaId?.let { inMemory[it] } ?: loaded?.takeIf { it.first == mediaId }?.second
}

internal fun safeCanvasUrls(artwork: CanvasArtwork): CanvasArtwork? {
  fun valid(value: String?): String? = value?.trim()?.takeIf { url ->
    try {
      val parsed = URI(url)
      parsed.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
        !parsed.host.isNullOrBlank() && parsed.userInfo == null
    } catch (_: Exception) {
      false
    }
  }
  val safe = artwork.copy(animated = valid(artwork.animated), videoUrl = valid(artwork.videoUrl))
  return safe.takeIf { it.animated != null || it.videoUrl != null }
}

/** Require an artist and a song or album match rather than showing unrelated motion art. */
internal fun canvasMatches(metadata: MediaMetadata, artwork: CanvasArtwork): Boolean {
  val requestedArtists = metadata.artists.flatMap { splitAndNormalizeArtists(it.name) }
  val returnedArtists = splitAndNormalizeArtists(artwork.artist.orEmpty())
  val artistMatches = requestedArtists.any { requested ->
    returnedArtists.any { it.equals(requested, ignoreCase = true) }
  }
  fun same(a: String?, b: String?): Boolean {
    val left = normalizeCanvasSongTitle(a.orEmpty())
    val right = normalizeCanvasSongTitle(b.orEmpty())
    return left.isNotBlank() && right.isNotBlank() && left.equals(right, ignoreCase = true)
  }
  val songMatches = same(metadata.title, artwork.name)
  val albumMatches = metadata.album?.title?.let { album ->
    same(album, artwork.albumName) || same(album, artwork.name)
  } ?: false
  return artistMatches && (songMatches || albumMatches)
}

/** Search providers sequentially and validate each result, so a bad first hit cannot block others. */
internal suspend fun searchManualCanvas(metadata: MediaMetadata): CanvasArtwork? =
  withContext(Dispatchers.IO) {
    withTimeoutOrNull(25_000L) {
      val title = normalizeCanvasSongTitle(metadata.title)
      val artist = normalizeCanvasArtistName(metadata.artists.joinToString { it.name })
      val album = metadata.album?.title
      if (title.isBlank() || artist.isBlank()) return@withTimeoutOrNull null
      val storefront = Locale.getDefault().country.lowercase(Locale.ROOT).takeIf { it.length == 2 } ?: "us"
      fun candidate(value: CanvasArtwork?): CanvasArtwork? =
        value?.let(::safeCanvasUrls)?.takeIf { canvasMatches(metadata, it) }
      candidate(echomusicCanvasProvider.getBySongArtist(title, artist))
        ?: candidate(TidalCanvasProvider.getBySongArtist(title, artist, album))
        ?: candidate(AppleMusicCanvasProvider.getBySongArtist(title, artist, album, storefront))
    }
  }
