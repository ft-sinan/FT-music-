package echo.music.iad1tya.echomusic.updater

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * Finds the newest FT Music release on GitHub and works out which of its published packages belongs
 * on this device.
 *
 * Everything is read from the same `github.com/ft-sinan/FT-music-` releases the browser download
 * uses, so the in-app updater cannot end up pointing somewhere else. Nothing here trusts a URL that
 * was not derived from the release's own asset list.
 */

const val FT_MUSIC_REPO = "ft-sinan/FT-music-"

private const val API_BASE = "https://api.github.com/repos/$FT_MUSIC_REPO"

/** The release page, kept for "Open in browser" and as a fallback when the API is unavailable. */
const val FT_MUSIC_RELEASES_URL = "https://github.com/$FT_MUSIC_REPO/releases"

private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 30_000
private const val MAX_REDIRECTS = 5

/** The package to install, as the release publishes it, with its published checksum. */
data class UpdateApk(
  /** Asset name, e.g. `FT-Music-v0.7.0-arm64-v8a.apk`. Also the local file name. */
  val name: String,
  val downloadUrl: String,
  /** Bytes as published, or 0 when the release does not say. Never estimated. */
  val sizeBytes: Long,
  /** The SHA-256 from this release's `SHA256SUMS.txt`. Null means no proof exists. */
  val sha256: String?,
)

data class UpdateRelease(
  /** The tag as published, e.g. `v0.7.0`. */
  val tag: String,
  val name: String?,
  val body: String?,
  val publishedAt: String?,
  val isPrerelease: Boolean,
  val releaseUrl: String,
  val apk: UpdateApk?,
  /** Set when a package exists but none of it fits this device. */
  val noPackageForThisDevice: Boolean,
)

/**
 * The newest release worth offering.
 *
 * Kept separate from [discoverUpdate] so the selection rules - never a draft, pre-releases only when
 * the user asked for them, and newest by number rather than by string - can be tested on their own.
 */
fun selectUpdateRelease(
  releases: List<JSONObject>,
  includePrereleases: Boolean,
): JSONObject? =
  releases
    .filter { !it.optBoolean("draft", false) }
    .filter { includePrereleases || !it.optBoolean("prerelease", false) }
    .filter { it.optString("tag_name").isNotBlank() }
    .maxByOrNull { candidate -> versionWeight(candidate.optString("tag_name")) }

/** Orders tags numerically, so v0.10.0 is newer than v0.7.0. */
fun versionWeight(tag: String): Long =
  tag.removePrefix("v").removePrefix("b").split(".").map { it.toIntOrNull() ?: 0 }
    .fold(0L) { acc, part -> acc * 1_000L + part }

/** Why a release could not be turned into something installable. */
sealed interface UpdateDiscoveryProblem {
  /** GitHub could not be reached, or answered with an error. */
  data class Unreachable(val detail: String?) : UpdateDiscoveryProblem
  /** The API answered, but not in a shape this code understands. */
  object UnexpectedResponse : UpdateDiscoveryProblem
  /** No stable (or beta, when enabled) release exists yet. */
  object NoRelease : UpdateDiscoveryProblem
}

data class UpdateDiscovery(
  val release: UpdateRelease?,
  val problem: UpdateDiscoveryProblem?,
)

/**
 * The newest release worth offering.
 *
 * GitHub's `/releases/latest` answers 404 while every published release is a pre-release, which is
 * exactly how this project published v0.5.0 and v0.6.0 - so the release list is read as well and
 * filtered here instead of relying on that endpoint alone. `includePrereleases` is only ever true
 * when the user turned on Beta updates.
 */
fun discoverUpdate(
  currentVersion: String,
  supportedAbis: List<String>,
  includePrereleases: Boolean = false,
  fetchText: (String) -> String,
): UpdateDiscovery {
  val releaseJson =
    runCatching { fetchLatestReleaseJson(includePrereleases, fetchText) }
      .getOrElse { error ->
        return UpdateDiscovery(null, classify(error))
      }
  val release = releaseJson ?: return UpdateDiscovery(null, UpdateDiscoveryProblem.NoRelease)

  val tag = release.optString("tag_name").takeIf { it.isNotBlank() }
    ?: return UpdateDiscovery(null, UpdateDiscoveryProblem.UnexpectedResponse)
  val publishedAt = release.optString("published_at").takeIf { it.isNotBlank() }
  val prerelease = release.optBoolean("prerelease", false)
  val htmlUrl = release.optString("html_url").takeIf { it.isNotBlank() }
    ?: "https://github.com/$FT_MUSIC_REPO/releases/tag/$tag"

  val assets = release.optJSONArray("assets")?.let(::assetList).orEmpty()
  val apks = assets.filter {
    it.name.endsWith(".apk", ignoreCase = true) && !it.name.contains("debug", ignoreCase = true)
  }
  val chosenName = chooseApkAsset(apks.map { it.name }, supportedAbis)
  val chosen = chosenName?.let { name -> apks.firstOrNull { it.name == name } }

  val sums = if (chosen != null) {
    assets.firstOrNull { it.name.equals(CHECKSUM_ASSET_NAME, ignoreCase = true) }
      ?.let { runCatching { fetchText(it.downloadUrl) }.getOrNull() }
      ?.let { parseSha256Sums(it) }
      .orEmpty()
  } else {
    emptyMap()
  }

  val updateApk = chosen?.let {
    UpdateApk(
      name = it.name,
      downloadUrl = it.downloadUrl,
      sizeBytes = it.sizeBytes,
      sha256 = expectedSha256For(sums, it.name),
    )
  }

  return UpdateDiscovery(
    release = UpdateRelease(
      tag = tag,
      name = release.optString("name").takeIf { it.isNotBlank() },
      body = release.optString("body").takeIf { it.isNotBlank() },
      publishedAt = publishedAt,
      isPrerelease = prerelease,
      releaseUrl = htmlUrl,
      apk = updateApk,
      noPackageForThisDevice = apks.isNotEmpty() && updateApk == null,
    ),
    problem = null,
  )
}

/** True when the found release is a genuine upgrade over what is installed. */
fun UpdateRelease.isNewerThan(currentVersion: String): Boolean =
  isNewerVersion(tag.removePrefix("v"), currentVersion)

private class ReleaseAsset(
  val name: String,
  val downloadUrl: String,
  val sizeBytes: Long,
)

private fun assetList(array: JSONArray): List<ReleaseAsset> {
  val assets = mutableListOf<ReleaseAsset>()
  for (index in 0 until array.length()) {
    val asset = array.optJSONObject(index) ?: continue
    val name = asset.optString("name").takeIf { it.isNotBlank() } ?: continue
    val url = asset.optString("browser_download_url").takeIf { it.isNotBlank() } ?: continue
    assets.add(ReleaseAsset(name, url, asset.optLong("size", 0L)))
  }
  return assets
}

/**
 * Prefers `/releases/latest` for a stable answer, then falls back to scanning the recent release
 * list so a repository whose newest work is all pre-releases is still handled deliberately.
 * Returns null only when GitHub was reachable and no release qualifies.
 */
private fun fetchLatestReleaseJson(
  includePrereleases: Boolean,
  fetchText: (String) -> String,
): JSONObject? {
  if (!includePrereleases) {
    runCatching { JSONObject(fetchText("$API_BASE/releases/latest")) }
      .getOrNull()
      ?.let { release ->
        if (!release.optBoolean("draft", false) && !release.optBoolean("prerelease", false)) {
          return release
        }
      }
  }
  // A failure here means GitHub could not be reached at all, which must not be reported as
  // "nothing newer" - that would tell the user they are up to date when nobody knows.
  val listing = JSONArray(fetchText("$API_BASE/releases?per_page=15"))
  val releases = (0 until listing.length()).mapNotNull { listing.optJSONObject(it) }
  return selectUpdateRelease(releases, includePrereleases)
}

private fun classify(error: Throwable): UpdateDiscoveryProblem = when (error) {
  is IOException -> UpdateDiscoveryProblem.Unreachable(error.message)
  else -> UpdateDiscoveryProblem.UnexpectedResponse
}

/**
 * Reads a URL as text, following redirects by hand because GitHub sends asset downloads to a
 * different host and `HttpURLConnection` will not cross protocols on its own.
 */
fun httpGetText(url: String, accept: String = "application/vnd.github+json"): String {
  var current = url
  repeat(MAX_REDIRECTS) {
    val connection = URL(current).openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = false
    connection.connectTimeout = CONNECT_TIMEOUT_MS
    connection.readTimeout = READ_TIMEOUT_MS
    connection.setRequestProperty("Accept", accept)
    connection.setRequestProperty("User-Agent", "FT-Music-Android")
    try {
      val status = connection.responseCode
      when (status) {
        in 300..399 -> {
          val location = connection.getHeaderField("Location")
            ?: throw IOException("Redirect without a location from $current")
          current = location
        }
        in 200..299 ->
          return connection.inputStream.bufferedReader().use { it.readText() }
        else -> {
          val detail = runCatching {
            connection.errorStream?.bufferedReader()?.use { it.readText() }
          }.getOrNull()
          throw IOException("HTTP $status", HttpStatusException(status, detail))
        }
      }
    } finally {
      connection.disconnect()
    }
  }
  throw IOException("Too many redirects from $url")
}

/** Carries the status so a 404 can be told apart from a network failure. */
class HttpStatusException(val status: Int, val body: String?) : IOException("HTTP $status")
