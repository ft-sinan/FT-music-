package echo.music.iad1tya.localmedia

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Metadata comparison deliberately keeps version words such as live, remix and cover. */
data class MatchTrack(
  val id: String,
  val title: String,
  val artist: String,
  val durationSeconds: Int,
)

data class LocalMatchResult(
  val confident: MatchTrack?,
  val candidates: List<MatchTrack>,
)

object LocalMatchEngine {
  fun normalize(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKC)
      .lowercase(Locale.ROOT)
      .replace(Regex("[^\\p{L}\\p{N}\\p{M}]+"), " ")
      .trim()
      .replace(Regex("\\s+"), " ")

  fun match(online: MatchTrack, device: List<MatchTrack>): LocalMatchResult {
    val title = normalize(online.title)
    if (title.length < 3) return LocalMatchResult(null, emptyList())
    val artist = normalize(online.artist)
    val ranked = device.mapNotNull { local ->
      val localTitle = normalize(local.title)
      // A related title can be offered for confirmation, never selected automatically.
      val exactTitle = localTitle == title
      val relatedTitle =
        !exactTitle && minOf(title.length, localTitle.length) >= 6 &&
          (localTitle.startsWith("$title ") || title.startsWith("$localTitle "))
      if (!exactTitle && !relatedTitle) return@mapNotNull null
      val localArtist = normalize(local.artist)
      val sameArtist = artist.isNotBlank() && artist == localArtist
      val durationKnown = online.durationSeconds > 0 && local.durationSeconds > 0
      val durationMatches = durationKnown && abs(online.durationSeconds - local.durationSeconds) <= 3
      val confident = exactTitle && sameArtist && durationMatches
      val score = (if (exactTitle) 4 else 1) + (if (sameArtist) 2 else 0) + (if (durationMatches) 1 else 0)
      Triple(local, confident, score)
    }.sortedByDescending { it.third }
    val certain = ranked.filter { it.second }
    return LocalMatchResult(
      confident = certain.singleOrNull()?.first,
      candidates = ranked.take(8).map { it.first },
    )
  }
}
