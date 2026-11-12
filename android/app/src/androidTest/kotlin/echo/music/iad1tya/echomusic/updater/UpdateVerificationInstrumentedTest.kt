package echo.music.iad1tya.echomusic.updater

import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

  /** The same flags the updater uses, so this compares two answers to the same question. */
  private val bothSigningFlags =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
    } else {
      PackageManager.GET_SIGNATURES
    }

  /** What Android reports for the installed app, through the same path the updater reads. */
  private fun installedSigningDigests(): List<String> =
    archiveSignerDigests(
      context.packageManager.getPackageInfo(context.packageName, bothSigningFlags)
    )

  @Test
  fun theCertificateReadFromTheApkFileMatchesTheOneAndroidReportsForTheInstalledApp() {
    val apkPath = context.packageManager.getApplicationInfo(context.packageName, 0).sourceDir
    val apk = File(apkPath)
    assertTrue("expected an APK at $apkPath", apk.exists())

    val installed = installedSigningDigests()
    assertTrue("Android would not report the installed app's own certificate", installed.isNotEmpty())

    val fromFile = apkSigningDigests(context, apk)
    assertNotNull(
      "this device would not report the certificate of the very APK it is running, so an update " +
        "here could only be verified by its checksum (API ${Build.VERSION.SDK_INT})",
      fromFile
    )
    // The digest is computed the same way the signing script computes it, so reading the file and
    // asking Android about the installed app have to agree. Compared as sets because signature
    // ordering is documented as not guaranteed.
    assertEquals(installed.toSet(), fromFile!!.toSet())
    fromFile.forEach {
      assertEquals(64, it.length)
      assertTrue(it.matches(Regex("^[0-9a-f]{64}$")))
    }
  }

  @Test
  fun aPackageForADifferentApplicationIsNeverTreatedAsThisUpdate() {
    // The check compares the archive's package name with this app's, so a signed APK belonging to
    // somebody else cannot be passed off as an FT Music update even if the digest matched.
    val apkPath = context.packageManager.getApplicationInfo(context.packageName, 0).sourceDir
    val info = context.packageManager.getPackageArchiveInfo(apkPath, bothSigningFlags)
    assertNotNull(info)
    assertEquals(context.packageName, info!!.packageName)
  }

  @Test
  fun aPackageSignedWithADifferentKeyIsReportedAsDifferentNotAsUnreadable() {
    // CI installs a debug build, which is signed with the debug key. Being told "different" is the
    // point: had this been reported as "unreadable", the app would fall back to the checksum alone
    // and offer to install a package signed by somebody else.
    val apk = File(context.packageManager.getApplicationInfo(context.packageName, 0).sourceDir)
    val digests = apkSigningDigests(context, apk)
    assertNotNull("this device would not report the APK's signers at all", digests)
    assertTrue(digests!!.isNotEmpty())
    assertFalse(
      "a debug build must not carry the release certificate",
      digests.contains(PINNED_SIGNING_CERT_SHA256)
    )
    assertEquals(SignatureMatch.DIFFERENT, checkApkSignature(context, apk))
    assertNull(apkSigningCertificateSha256(context, apk))
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
