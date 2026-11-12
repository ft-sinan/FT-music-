package echo.music.iad1tya.echomusic.updater

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The updater installs code, so the parts that decide *which* file and *whether it may be
 * installed* are held to exact answers here.
 */
class UpdateReleaseSourceTest {

  private val publishedApks =
    listOf(
      "FT-Music-v0.7.0-arm64-v8a.apk" to 78_123_456L,
      "FT-Music-v0.7.0-armeabi-v7a.apk" to 74_000_000L,
      "FT-Music-v0.7.0-universal.apk" to 210_000_000L,
      "FT-Music-v0.7.0-x86.apk" to 79_000_000L,
      "FT-Music-v0.7.0-x86_64.apk" to 82_000_000L,
    )

  private fun asset(name: String, size: Long) =
    JSONObject()
      .put("name", name)
      .put("size", size)
      .put("browser_download_url", "https://github.com/$FT_MUSIC_REPO/releases/download/v0.7.0/$name")

  /** A release shaped like the ones the pipeline actually publishes. */
  private fun release(
    tag: String = "v0.7.0",
    prerelease: Boolean = false,
    body: String = "## What's new\n- Fixed a thing",
    withChecksums: Boolean = true,
    withApks: Boolean = true,
  ): JSONObject {
    val assets = JSONArray()
    if (withApks) publishedApks.forEach { (name, size) -> assets.put(asset(name, size)) }
    if (withChecksums) {
      assets.put(
        JSONObject()
          .put("name", CHECKSUM_ASSET_NAME)
          .put("size", 400)
          .put(
            "browser_download_url",
            "https://github.com/$FT_MUSIC_REPO/releases/download/$tag/$CHECKSUM_ASSET_NAME"
          )
      )
    }
    return JSONObject()
      .put("tag_name", tag)
      .put("name", "FT Music $tag")
      .put("body", body)
      .put("published_at", "2026-10-30T12:00:00Z")
      .put("prerelease", prerelease)
      .put("draft", false)
      .put("html_url", "https://github.com/$FT_MUSIC_REPO/releases/tag/$tag")
      .put("assets", assets)
  }

  private fun sumsFile(vararg names: String): String =
    names.joinToString("\n") { name ->
      // A different digest per name so a wrong pairing cannot pass by luck.
      val digest = sha256Hex(name.toByteArray()).uppercase()
      "$digest  $name"
    }

  private fun fetcher(
    latest: JSONObject? = null,
    listing: JSONArray? = null,
    sums: String? = null,
  ): (String) -> String = { url ->
    when {
      url.endsWith("/releases/latest") ->
        latest?.toString() ?: throw HttpStatusException(404, null)
      url.contains("/releases?") ->
        listing?.toString() ?: throw HttpStatusException(404, null)
      url.endsWith(CHECKSUM_ASSET_NAME) -> sums ?: throw HttpStatusException(404, null)
      else -> throw HttpStatusException(404, null)
    }
  }

  // ---------------------------------------------------------------- which file

  @Test
  fun picksThePackageForTheDevicesOwnAbi() {
    assertEquals(
      "FT-Music-v0.7.0-arm64-v8a.apk",
      chooseApkAsset(publishedApks.map { it.first }, listOf("arm64-v8a", "armeabi-v7a"))
    )
  }

  @Test
  fun x86NeverClaimsTheX86_64Package() {
    // A 32-bit package on a 64-bit-only device is refused by Android, and the reverse wastes the
    // user's download, so the match has to be on the whole ABI suffix.
    assertEquals(
      "FT-Music-v0.7.0-x86.apk",
      chooseApkAsset(publishedApks.map { it.first }, listOf("x86", "arm64-v8a"))
    )
    assertEquals(
      "FT-Music-v0.7.0-x86_64.apk",
      chooseApkAsset(publishedApks.map { it.first }, listOf("x86_64", "x86"))
    )
  }

  @Test
  fun fallsBackToUniversalWhenNoAbiPackageExists() {
    assertEquals(
      "FT-Music-v0.7.0-universal.apk",
      chooseApkAsset(publishedApks.map { it.first }, listOf("mips64"))
    )
  }

  @Test
  fun aDeviceNothingWasBuiltForIsReportedRatherThanGuessed() {
    assertNull(chooseApkAsset(listOf("FT-Music-v0.7.0-arm64-v8a.apk"), listOf("mips64")))
  }

  @Test
  fun debugBuildsAreNeverOffered() {
    assertNull(
      chooseApkAsset(
        listOf("FT-Music-v0.7.0-arm64-v8a-debug.apk", "FT-Music-v0.7.0-universal-debug.apk"),
        listOf("arm64-v8a")
      )
    )
  }

  // ------------------------------------------------------------- checksum list

  @Test
  fun readsThePublishedChecksumList() {
    val text =
      """
      |${sha256Hex("a".toByteArray())}  FT-Music-v0.7.0-arm64-v8a.apk
      |${sha256Hex("b".toByteArray())} *FT-Music-v0.7.0-universal.apk
      |not-a-hash  FT-Music-v0.7.0-x86.apk
      |garbage
      |
      """.trimMargin()
    val sums = parseSha256Sums(text)
    assertEquals(sha256Hex("a".toByteArray()), sums["FT-Music-v0.7.0-arm64-v8a.apk"])
    // The `*` binary marker sha256sum emits is accepted, not treated as part of the name.
    assertEquals(sha256Hex("b".toByteArray()), sums["FT-Music-v0.7.0-universal.apk"])
    // A malformed line is skipped instead of poisoning the map.
    assertFalse(sums.containsKey("FT-Music-v0.7.0-x86.apk"))
    assertEquals(2, sums.size)
  }

  @Test
  fun aNameIsNeverAllowedToEscapeTheDownloadDirectory() {
    assertEquals("FT-Music-v0.7.0-arm64-v8a.apk", safeApkFileName("FT-Music-v0.7.0-arm64-v8a.apk"))
    assertNull(safeApkFileName("../../evil.apk"))
    assertNull(safeApkFileName("evil.apk/../../x"))
    assertNull(safeApkFileName("..\\..\\evil.apk"))
    assertNull(safeApkFileName(".hidden.apk"))
    assertNull(safeApkFileName("not an apk.txt"))
    assertNull(safeApkFileName(null))
  }

  // ---------------------------------------------------------------- discovery

  @Test
  fun findsTheStableReleaseAndItsChecksumForThisDevice() {
    val sums = sumsFile("FT-Music-v0.7.0-arm64-v8a.apk")
    val discovery =
      discoverUpdate(
        currentVersion = "0.6.0",
        supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
        fetchText = fetcher(latest = release(), sums = sums)
      )

    assertNull(discovery.problem)
    val found = discovery.release!!
    assertEquals("v0.7.0", found.tag)
    assertTrue(found.isNewerThan("0.6.0"))
    val apk = found.apk!!
    assertEquals("FT-Music-v0.7.0-arm64-v8a.apk", apk.name)
    assertEquals(78_123_456L, apk.sizeBytes)
    assertEquals(sha256Hex("FT-Music-v0.7.0-arm64-v8a.apk".toByteArray()).uppercase(), apk.sha256)
    assertTrue(apk.downloadUrl.endsWith("/FT-Music-v0.7.0-arm64-v8a.apk"))
  }

  @Test
  fun aReleaseThatPublishesNoChecksumCannotBeOfferedForDownload() {
    // Without a published digest there is nothing to compare the download against, so the APK is
    // reported with no checksum and the screen refuses to download it.
    val discovery =
      discoverUpdate(
        currentVersion = "0.6.0",
        supportedAbis = listOf("arm64-v8a"),
        fetchText = fetcher(latest = release(), sums = null)
      )
    assertNull(discovery.release!!.apk!!.sha256)
  }

  @Test
  fun aChecksumListForADifferentFileIsNotSubstituted() {
    val discovery =
      discoverUpdate(
        currentVersion = "0.6.0",
        supportedAbis = listOf("arm64-v8a"),
        fetchText = fetcher(latest = release(), sums = sumsFile("FT-Music-v0.7.0-x86.apk"))
      )
    assertNull(discovery.release!!.apk!!.sha256)
  }

  @Test
  fun allPrereleasesMeansTheListIsReadBecauseLatestAnswers404() {
    // GitHub's /releases/latest 404s while every published release is a pre-release, which is how
    // v0.5.0 and v0.6.0 were published.
    val listing = JSONArray().put(release(prerelease = true))
    val discovery =
      discoverUpdate(
        currentVersion = "0.5.0",
        supportedAbis = listOf("arm64-v8a"),
        includePrereleases = true,
        fetchText = fetcher(listing = listing, sums = sumsFile("FT-Music-v0.7.0-arm64-v8a.apk"))
      )
    assertEquals("v0.7.0", discovery.release!!.tag)
    assertTrue(discovery.release!!.isPrerelease)
  }

  @Test
  fun preReleasesAreSkippedUnlessTheUserAskedForThem() {
    val listing = JSONArray().put(release(prerelease = true))
    val sums = sumsFile("FT-Music-v0.7.0-arm64-v8a.apk")

    val stable =
      discoverUpdate(
        currentVersion = "0.5.0",
        supportedAbis = listOf("arm64-v8a"),
        fetchText = fetcher(listing = listing, sums = sums)
      )
    assertEquals(UpdateDiscoveryProblem.NoRelease, stable.problem)

    val beta =
      discoverUpdate(
        currentVersion = "0.5.0",
        supportedAbis = listOf("arm64-v8a"),
        includePrereleases = true,
        fetchText = fetcher(listing = listing, sums = sums)
      )
    assertEquals("v0.7.0", beta.release!!.tag)
  }

  @Test
  fun draftsAreNeverOffered() {
    val listing = JSONArray().put(release().put("draft", true))
    val discovery =
      discoverUpdate(
        currentVersion = "0.5.0",
        supportedAbis = listOf("arm64-v8a"),
        includePrereleases = true,
        fetchText = fetcher(listing = listing, sums = sumsFile("FT-Music-v0.7.0-arm64-v8a.apk"))
      )
    assertEquals(UpdateDiscoveryProblem.NoRelease, discovery.problem)
  }

  @Test
  fun theNewestTagWinsWhenSeveralReleasesAreListed() {
    val listing =
      JSONArray()
        .put(release(tag = "v0.6.0"))
        .put(release(tag = "v0.10.0"))
        .put(release(tag = "v0.7.0"))
    val discovery =
      discoverUpdate(
        currentVersion = "0.6.0",
        supportedAbis = listOf("arm64-v8a"),
        fetchText = fetcher(listing = listing, sums = sumsFile("FT-Music-v0.7.0-arm64-v8a.apk"))
      )
    // Numeric comparison, so 0.10.0 is newer than 0.7.0.
    assertEquals("v0.10.0", discovery.release!!.tag)
  }

  @Test
  fun aReleaseWithNoPackageForThisDeviceSaysSo() {
    val onlyOtherAbis =
      release().put(
        "assets",
        JSONArray()
          .put(asset("FT-Music-v0.7.0-x86.apk", 1L))
          .put(asset(CHECKSUM_ASSET_NAME, 1L))
      )
    val discovery =
      discoverUpdate(
        currentVersion = "0.6.0",
        supportedAbis = listOf("mips64"),
        fetchText = fetcher(latest = onlyOtherAbis, sums = sumsFile("FT-Music-v0.7.0-x86.apk"))
      )
    assertNull(discovery.release!!.apk)
    assertTrue(discovery.release!!.noPackageForThisDevice)
  }

  @Test
  fun anUnreachableGitHubIsNotReportedAsUpToDate() {
    val discovery =
      discoverUpdate(
        currentVersion = "0.6.0",
        supportedAbis = listOf("arm64-v8a"),
        fetchText = { throw java.io.IOException("no network") }
      )
    assertNull(discovery.release)
    assertTrue(discovery.problem is UpdateDiscoveryProblem.Unreachable)
  }

  @Test
  fun theSameVersionIsNotAnUpdate() {
    fun releaseTagged(tag: String) =
      UpdateRelease(
        tag = tag,
        name = null,
        body = null,
        publishedAt = null,
        isPrerelease = false,
        releaseUrl = "",
        apk = null,
        noPackageForThisDevice = false
      )

    // The published tag carries a `v`; the installed version does not.
    assertTrue(releaseTagged("v0.7.0").isNewerThan("0.6.0"))
    assertFalse(releaseTagged("v0.7.0").isNewerThan("0.7.0"))
    assertFalse(releaseTagged("v0.6.0").isNewerThan("0.7.0"))
    assertTrue(releaseTagged("v0.10.0").isNewerThan("0.7.0"))
  }

  @Test
  fun releaseSelectionIsNumericAndIgnoresDrafts() {
    val releases =
      listOf(
        release(tag = "v0.6.0"),
        release(tag = "v0.10.0"),
        release(tag = "v0.9.0").put("draft", true),
        release(tag = "v0.7.0").put("prerelease", true),
      )
    assertEquals("v0.10.0", selectUpdateRelease(releases, includePrereleases = false)!!.getString("tag_name"))
    assertEquals("v0.10.0", selectUpdateRelease(releases, includePrereleases = true)!!.getString("tag_name"))
    // A string sort would put v0.9.0 above v0.10.0.
    assertTrue(versionWeight("v0.10.0") > versionWeight("v0.9.0"))
    assertTrue(versionWeight("0.7.0") > versionWeight("v0.6.0"))
    assertNull(selectUpdateRelease(listOf(release(tag = "v0.7.0").put("draft", true)), false))
    assertNull(selectUpdateRelease(emptyList(), includePrereleases = true))
  }

  // -------------------------------------------------------------- pinning

  @Test
  fun thePinnedCertificateIsTheOneTheReleasePipelineSignsWith() {
    // Drift here would make every real update look forged, or - worse - accept somebody else's.
    val file = sequenceOf(
      File("signing-cert-sha256.txt"),
      File("android/signing-cert-sha256.txt"),
      File("../signing-cert-sha256.txt"),
    ).firstOrNull { it.exists() }
    assertTrue("android/signing-cert-sha256.txt was not found from ${File(".").absolutePath}", file != null)
    val published = file!!.readText().trim().lowercase()
    assertEquals(64, published.length)
    assertEquals(published, PINNED_SIGNING_CERT_SHA256)
  }

  @Test
  fun thePinnedCertificateIsRecognisedWhateverPositionItIsReportedIn() {
    // Signature ordering is documented as not guaranteed, and a rotated certificate reports its
    // history, so the pinned digest is searched for rather than assumed to be first.
    val pinned = PINNED_SIGNING_CERT_SHA256
    val other = sha256Hex(byteArrayOf(9, 9, 9))
    assertNotEquals(pinned, other)

    assertEquals(pinned, pinnedDigestAmong(listOf(pinned)))
    assertEquals(pinned, pinnedDigestAmong(listOf(other, pinned)))
    assertEquals(pinned, pinnedDigestAmong(listOf(pinned, other)))
    // Case is not significant: the signing script lowercases, a device might not.
    assertEquals(pinned, pinnedDigestAmong(listOf(pinned.uppercase())))
    // A different signer is a different signer, not a near miss. One hex digit either way is a
    // different certificate, and a prefix of the right one is not the right one.
    assertNull(pinnedDigestAmong(listOf(other)))
    assertNull(pinnedDigestAmong(listOf(pinned.dropLast(1))))
    assertNull(pinnedDigestAmong(listOf("0$pinned")))
    assertNull(pinnedDigestAmong(emptyList()))
  }

  @Test
  fun aFileIsOnlyAcceptedWhenADigestWasPublishedAndMatches() {
    val file = File.createTempFile("ft-update-test", ".apk")
    file.writeBytes("the published bytes".toByteArray())
    file.deleteOnExit()

    val published = sha256Hex("the published bytes".toByteArray())
    assertTrue(matchesPublishedChecksum(file, published))
    assertTrue(matchesPublishedChecksum(file, published.uppercase()))
    // A single changed byte is a different file.
    assertFalse(matchesPublishedChecksum(file, sha256Hex("tampered bytes".toByteArray())))
    // No published digest means no proof, which is not the same as a pass.
    assertFalse(matchesPublishedChecksum(file, null))
    assertFalse(matchesPublishedChecksum(file, ""))
  }
}
