package echo.music.iad1tya.echomusic.updater

import android.content.Context
import android.content.pm.PackageInfo
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

/**
 * Both signature flag families, so a certificate is collected on every supported API level. The
 * modern flag is spelled out as its value because it only exists from API 28 while this app
 * supports 26; passing an unrecognised bit to an older framework is harmless, and the deprecated
 * flag alongside it is what makes Android 9 and 10 collect anything at all.
 */
/**
 * `PackageManager.GET_SIGNING_CERTIFICATES` (0x08000000, API 28+) combined with the deprecated
 * `PackageManager.GET_SIGNATURES` (0x00000040). Both are spelled as their values so the same flags
 * can be requested on API 26 and 27, where the modern constant does not exist; an older framework
 * simply ignores a bit it does not know. The values are the ones AOSP declares.
 */
private const val ARCHIVE_SIGNING_FLAGS = 0x08000040

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
 * Every certificate digest an APK file reports, or null when this device reports none.
 *
 * Both flag families are requested on every API level, because `getPackageArchiveInfo` collects an
 * archive's certificates only when the deprecated `GET_SIGNATURES` is set on Android 9 and 10 - and
 * again on some Android 13 builds - while `GET_SIGNING_CERTIFICATES` alone leaves `signingInfo` null
 * there. Passing only the modern flag would make every genuine FT Music update look forged on
 * exactly the older phones most likely to need it. The answer from `signingInfo` is preferred
 * wherever it is populated, and the digests are computed the same way the
 * `openssl x509 -inform DER -fingerprint -sha256` check in the signing script does.
 *
 * An empty result and null are kept apart on purpose: null means nothing could be read, which is
 * not the same as reading a certificate that is not FT Music's.
 */
fun apkSigningDigests(context: Context, file: File): List<String>? = runCatching {
  val info = archivePackageInfo(context, file) ?: return@runCatching null
  // A package for somebody else's app is not this update, whatever it is signed with.
  if (info.packageName != context.packageName) return@runCatching null
  archiveSignerDigests(info).takeIf { it.isNotEmpty() }
}.getOrNull()

/** FT Music's pinned digest when it is among this APK's signers, otherwise null. */
fun apkSigningCertificateSha256(context: Context, file: File): String? =
  apkSigningDigests(context, file)?.let { pinnedDigestAmong(it) }

/**
 * Reads the APK with every flag that can produce a certificate on any supported API level.
 *
 * The `PackageInfoFlags` overload added in API 33 is deliberately not used: it resolves against a
 * compile SDK that does not expose it here, and the flag overload below still works on every level
 * including current ones. It is deprecated, not removed.
 */
private fun archivePackageInfo(context: Context, file: File): PackageInfo? {
  @Suppress("DEPRECATION")
  return context.packageManager.getPackageArchiveInfo(file.absolutePath, ARCHIVE_SIGNING_FLAGS)
}

/**
 * FT Music's pinned digest when it is one of the reported signers, otherwise null.
 *
 * Signature ordering is documented as not guaranteed, and a rotated certificate reports its
 * history, so the pinned digest is looked for among the signers rather than assumed to be the first
 * one. Anything else is a different signer. The comparison ignores case, because the signing script
 * lowercases while a device might not, and the canonical constant is what comes back so a caller
 * never has to normalise it. Pure, so the decision that gates an install can be tested without an
 * Android runtime.
 */
fun pinnedDigestAmong(digests: List<String>): String? =
  if (digests.any { PINNED_SIGNING_CERT_SHA256.equals(it, ignoreCase = true) }) {
    PINNED_SIGNING_CERT_SHA256
  } else {
    null
  }

/** Every certificate digest this package reports, through whichever field the device filled in. */
fun archiveSignerDigests(info: PackageInfo): List<String> {
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    val signingInfo = info.signingInfo
    if (signingInfo != null) {
      val signers =
        if (signingInfo.hasMultipleSigners()) {
          signingInfo.apkContentsSigners
        } else {
          // A single signer reports its history, which includes a rotated-out certificate.
          runCatching { signingInfo.signingCertificateHistory }.getOrNull()
            ?: signingInfo.apkContentsSigners
        }
      signers?.mapNotNull { it?.toByteArray()?.let(::sha256Hex) }?.takeIf { it.isNotEmpty() }
        ?.let { return it }
    }
  }
  @Suppress("DEPRECATION")
  val legacy = info.signatures
  return legacy?.mapNotNull { it?.toByteArray()?.let(::sha256Hex) }.orEmpty()
}

/**
 * The verdict that gates an install. Reading a certificate that is not FT Music's and reading none
 * at all are deliberately different answers: the first is a package that must never be installed,
 * the second is a device that cannot say, where the published checksum is the only proof available
 * and the user is told so.
 */
fun checkApkSignature(context: Context, file: File): SignatureMatch {
  val digests = apkSigningDigests(context, file) ?: return SignatureMatch.UNREADABLE
  return if (pinnedDigestAmong(digests) != null) {
    SignatureMatch.FT_MUSIC
  } else {
    SignatureMatch.DIFFERENT
  }
}
