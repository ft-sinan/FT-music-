package echo.music.iad1tya.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Proves the publish-time container swap with the ffmpeg actually shipped in the APK. */
@RunWith(AndroidJUnit4::class)
class OpusRepublisherInstrumentedTest {
  private fun directory(): File {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    return File(context.cacheDir, "ft-opus-remux-test").apply { mkdirs() }
  }

  @Test
  fun opusPacketsSurviveTheContainerSwapAndStillDecode() = runBlocking {
    val dir = directory()
    val input = File(dir, "source.webm")
    val create = FFmpegKit.execute(
      "-y -f lavfi -i sine=frequency=523:duration=2 -c:a libopus -b:a 96k ${input.absolutePath}"
    )
    assumeTrue(
      "bundled ffmpeg cannot mint an Opus/WebM probe on this device",
      ReturnCode.isSuccess(create.returnCode) && input.length() > 0,
    )
    val output = File(dir, "source.opus")
    assertTrue("remux reported failure", OpusRepublisher.remux(input, output))
    assertTrue("output is not an Ogg container", OpusRepublisher.looksLikeOgg(output))
    assertFalse("webm input must not look like ogg", OpusRepublisher.looksLikeOgg(input))
    val decode = FFmpegKit.execute("-i ${output.absolutePath} -f null -")
    assertTrue("remuxed file does not decode", ReturnCode.isSuccess(decode.returnCode))
  }

  @Test
  fun aFileThatIsNotWebmFallsBackInsteadOfPublishingRubbish() = runBlocking {
    val dir = directory()
    val garbage = File(dir, "garbage.webm").apply { writeText("not a matroska container at all") }
    val output = File(dir, "garbage.opus")
    assertFalse(OpusRepublisher.remux(garbage, output))
    assertFalse(output.exists() && output.length() > 0 && OpusRepublisher.looksLikeOgg(output))
  }
}
