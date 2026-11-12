package echo.music.iad1tya.echomusic.updater

import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the signature check reads a real certificate and hashes it the way the release pipeline
 * does. The digest method is the thing that cannot be wrong: if it differed from
 * `openssl x509 -inform DER -fingerprint -sha256`, every genuine FT Music update would look forged.
 *
 * The APK under test is this build's own, so it is a real signed package - debug-signed in CI,
 * release-signed on a phone. Either way the two independent ways Android exposes the certificate
 * must agree with what the updater computes from the file.
 */
@RunWith(AndroidJUnit4::class)
class UpdateVerificationInstrumentedTest {

  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  private fun installedCertificateSha256(): String? {
    val packageName = context.packageName
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      val info = context.packageManager.getPackageInfo(
        packageName, PackageManager.GET_SIGNING_CERTIFICATES
      )
      val signingInfo = info.signingInfo ?: return null
      if (signingInfo.hasMultipleSigners()) return null
      signingInfo.apkContentsSigners?.firstOrNull()?.toByteArray()?.let { sha256Hex(it) }
    } else {
      @Suppress("DEPRECATION")
      val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
      @Suppress("DEPRECATION")
      info.signatures?.firstOrNull()?.toByteArray()?.let { sha256Hex(it) }
    }
  }

  @Test
  fun theCertificateReadFromTheApkFileMatchesTheOneAndroidReportsForTheInstalledApp() {
    val apkPath = context.packageManager.getApplicationInfo(context.packageName, 0).sourceDir
    val apk = File(apkPath)
    assertTrue("expected an APK at $apkPath", apk.exists())

    val fromFile = apkSigningCertificateSha256(context, apk)
    assertNotNull("this device would not report the APK's certificate", fromFile)
    assertEquals(64, fromFile!!.length)
    assertTrue(fromFile.matches(Regex("^[0-9a-f]{64}$")))
    assertEquals(installedCertificateSha256(), fromFile)
  }

  @Test
  fun aPackageSignedBySomebodyElseIsNeverReportedAsFtMusic() {
    // This build is not signed with the release key, so it must not be mistaken for one. The
    // assertion is about the verdict, not about which certificate a debug build happens to carry.
    val apk = File(context.packageManager.getApplicationInfo(context.packageName, 0).sourceDir)
    val verdict = checkApkSignature(context, apk)
    if (verdict == SignatureMatch.FT_MUSIC) {
      assertEquals(PINNED_SIGNING_CERT_SHA256, apkSigningCertificateSha256(context, apk))
    } else {
      assertTrue(verdict == SignatureMatch.DIFFERENT || verdict == SignatureMatch.UNREADABLE)
      assertNotEquals(PINNED_SIGNING_CERT_SHA256, apkSigningCertificateSha256(context, apk))
    }
  }

  @Test
  fun aCorruptFileIsNotMistakenForASignedPackage() {
    val corrupt = File.createTempFile("ft-update-corrupt", ".apk", context.cacheDir)
    try {
      corrupt.writeBytes("this is not a zip archive".toByteArray())
      val digest = apkSigningCertificateSha256(context, corrupt)
      // Either no certificate could be read, or one that is not FT Music's. What must never happen
      // is a corrupt file being reported as FT Music's own.
      if (digest != null) assertNotEquals(PINNED_SIGNING_CERT_SHA256, digest)
      assertNotEquals(SignatureMatch.FT_MUSIC, checkApkSignature(context, corrupt))
    } finally {
      corrupt.delete()
    }
  }

  @Test
  fun aMissingFileIsReportedAsUnreadableRatherThanAccepted() {
    val absent = File(context.cacheDir, "ft-update-does-not-exist.apk")
    assertNull(apkSigningCertificateSha256(context, absent))
    assertEquals(SignatureMatch.UNREADABLE, checkApkSignature(context, absent))
  }

  @Test
  fun theChecksumOfARealFileMatchesAnIndependentDigest() {
    // SHA-256 of "abc", the standard test vector, computed through the same streaming path the
    // updater uses for a downloaded APK.
    val file = File.createTempFile("ft-update-digest", ".bin", context.cacheDir)
    try {
      file.writeBytes("abc".toByteArray())
      assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        sha256Hex(file)
      )
      assertTrue(matchesPublishedChecksum(file, sha256Hex("abc".toByteArray())))
      assertTrue(
        matchesPublishedChecksum(
          file,
          "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"
        )
      )
    } finally {
      file.delete()
    }
  }
}
