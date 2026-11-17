package echo.music.iad1tya.playback

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpusRepublisherTest {
  @Test
  fun remuxIsAStreamCopyArgumentArray() {
    val args = OpusRepublisher.remuxArguments("/in.webm", "/out.opus")
    assertEquals(listOf("-hide_banner", "-i", "/in.webm", "-c", "copy", "/out.opus"), args)
    // One token per element and nothing to escape: no shell and no quote parser ever sees a path.
    assertTrue(args.none { it.contains('\'') || it.contains('"') || it.contains('\\') })
    assertEquals("-c", args[3])
    assertEquals("copy", args[4])
  }

  @Test
  fun webmAudioIsDetectedWhateverTheCasingOrParameters() {
    listOf("audio/webm", "video/webm", "audio/webm; codecs=opus", "VIDEO/WEBM", " audio/webm ")
      .forEach { assertTrue(it, SharedDownloadNames.isWebmAudio(it)) }
    listOf("audio/mp4", "audio/ogg", "application/ogg", "audio/mpeg", "video/mp4", "")
      .forEach { assertFalse(it, SharedDownloadNames.isWebmAudio(it)) }
  }

  @Test
  fun publishedOpusNamesKeepTheFingerprintSuffix() {
    val name = SharedDownloadNames.fileName("id123", "Don't Stop", "Queen", SharedDownloadNames.OPUS_EXTENSION)
    assertTrue(name, name.endsWith("].opus"))
    assertTrue(name, name.startsWith("Queen - Don't Stop ["))
    assertEquals("audio/ogg", SharedDownloadNames.OPUS_MIME_TYPE)
  }

  @Test
  fun oggMagicIsRecognisedAndWebmIsNot() {
    val dir = File(System.getProperty("java.io.tmpdir"), "ft-opus-repub-test").apply { mkdirs() }
    val ogg = File(dir, "a.opus").apply { writeBytes("OggS".toByteArray(Charsets.US_ASCII) + ByteArray(4)) }
    val webm = File(dir, "b.webm").apply { writeBytes(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0, 0)) }
    val empty = File(dir, "c.opus").apply { writeBytes(ByteArray(0)) }
    assertTrue(OpusRepublisher.looksLikeOgg(ogg))
    assertFalse(OpusRepublisher.looksLikeOgg(webm))
    assertFalse(OpusRepublisher.looksLikeOgg(empty))
    assertFalse(OpusRepublisher.looksLikeOgg(File(dir, "missing.opus")))
  }
}
