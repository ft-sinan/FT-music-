package echo.music.iad1tya.echomusic.updater

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Decides whether a downloaded APK may be handed to Android's installer.
 *
 * Two independent checks, both fail-closed. The SHA-256 must match the release's own
 * `SHA256SUMS.txt`, which proves the bytes are the published file and arrived whole. The signing
 * certificate must be FT Music's pinned one, which proves it is still FT Music that signed them.
 *
 * Android would also refuse a changed signature, but only *after* asking the user to uninstall the
 * app first - and uninstalling destroys their library, likes, playlists and downloads. Refusing
 * here means that is never offered.
 */

/**
 * SHA-256 of FT Music's DER signing certificate, the same value `scripts/sign-release.sh` checks
 * before publishing. A static test compares this constant with `android/signing-cert-sha256.txt`.
 */
const val PINNED_SIGNING_CERT_SHA256 =
  "d4c1f03eae6424f2806e4aa20b54c5eee25397c2b52f93d9b8edc40b9e1a757f"

/** The release asset FT Music publishes checksums in. */
const val CHECKSUM_ASSET_NAME = "SHA256SUMS.txt"

private val SAFE_APK_NAME = Regex("^[A-Za-z0-9._-]+\\.apk$")

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

/**
 * The package that fits this device: the first ABI Android lists that has its own APK, else the
 * universal one, else nothing. Guessing wrong here means Android refuses the install, so a device
 * with no match is reported rather than handed a random architecture.
 */
fun chooseApkAsset(assetNames: List<String>, supportedAbis: List<String>): String? {
  val apks = assetNames.filter {
    it.endsWith(".apk", ignoreCase = true) && !it.contains("debug", ignoreCase = true)
  }
  for (abi in supportedAbis) {
    // Suffix match keeps x86 from claiming the x86_64 package.
    apks.firstOrNull { it.endsWith("-$abi.apk", ignoreCase = true) }?.let { return it }
  }
  return apks.firstOrNull { it.endsWith("-universal.apk", ignoreCase = true) }
}

/** Parses `<64 hex><whitespace><name>` lines, tolerating the `*` binary marker. */
fun parseSha256Sums(text: String): Map<String, String> {
  val sums = mutableMapOf<String, String>()
  text.lineSequence().forEach { line ->
    val parts = line.trim().split(Regex("\\s+"), limit = 2)
    if (parts.size != 2) return@forEach
    val hash = parts[0]
    if (hash.length != 64 || hash.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) {
      return@forEach
    }
    val name = parts[1].removePrefix("*").trim().substringAfterLast('/')
    if (name.isNotEmpty()) sums[name] = hash
  }
  return sums
}

/** The published checksum for one package, or null when the release does not list it. */
fun expectedSha256For(sums: Map<String, String>, apkName: String): String? = sums[apkName]

/**
 * A file name that is safe to create inside the download directory. Anything containing a path
 * separator or an unexpected shape is refused outright rather than trimmed into a different name:
 * the file on disk has to be recognisable as the asset the release published.
 */
fun safeApkFileName(name: String?): String? = name?.takeIf {
  '/' !in it && '\\' !in it && SAFE_APK_NAME.matches(it) && !it.startsWith(".")
}

private fun ByteArray.toHexString(): String {
  val chars = CharArray(size * 2)
  for (i in indices) {
    val value = this[i].toInt() and 0xff
    chars[i * 2] = HEX_DIGITS[value ushr 4]
    chars[i * 2 + 1] = HEX_DIGITS[value and 0x0f]
  }
  return String(chars)
}

fun sha256Hex(bytes: ByteArray): String =
  MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

/** Streams, so a large APK is never held in memory just to hash it. */
fun sha256Hex(file: File): String? = runCatching {
  val digest = MessageDigest.getInstance("SHA-256")
  FileInputStream(file).use { input ->
    val buffer = ByteArray(64 * 1024)
    while (true) {
      val read = input.read(buffer)
      if (read <= 0) break
      digest.update(buffer, 0, read)
    }
  }
  digest.digest().toHexString()
}.getOrNull()

/** True only when a published checksum exists and the file matches it exactly. */
fun matchesPublishedChecksum(file: File, expectedSha256: String?): Boolean {
  if (expectedSha256.isNullOrBlank()) return false
  val actual = sha256Hex(file) ?: return false
  return actual.equals(expectedSha256.trim(), ignoreCase = true)
}

/** Whether the APK's signing certificate could be read, and whether it is FT Music's. */
enum class SignatureMatch {
  /** The certificate is FT Music's pinned one. */
  FT_MUSIC,

  /** A certificate was read and it is not FT Music's. Never install this. */
  DIFFERENT,

  /** This device would not report the certificate, so nothing is known either way. */
  UNREADABLE
}

/**
 * The SHA-256 of the certificate the APK is signed with, computed the same way the
 * `openssl x509 -inform DER -fingerprint -sha256` check in the signing script does. Null when this
 * device will not report it.
 */
fun apkSigningCertificateSha256(context: Context, file: File): String? = runCatching {
  val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    context.packageManager.getPackageArchiveInfo(
      file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES
    )
  } else {
    @Suppress("DEPRECATION")
    context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
  } ?: return@runCatching null

  val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    // Multiple signers mean the file is not the single-signed package that was published.
    val signingInfo = info.signingInfo ?: return@runCatching null
    if (signingInfo.hasMultipleSigners()) return@runCatching null
    signingInfo.apkContentsSigners
  } else {
    @Suppress("DEPRECATION")
    info.signatures
  } ?: return@runCatching null

  signatures.firstOrNull()?.toByteArray()?.let { sha256Hex(it) }
}.getOrNull()

fun checkApkSignature(context: Context, file: File): SignatureMatch {
  val actual = apkSigningCertificateSha256(context, file) ?: return SignatureMatch.UNREADABLE
  return if (PINNED_SIGNING_CERT_SHA256.equals(actual, ignoreCase = true)) {
    SignatureMatch.FT_MUSIC
  } else {
    SignatureMatch.DIFFERENT
  }
}
