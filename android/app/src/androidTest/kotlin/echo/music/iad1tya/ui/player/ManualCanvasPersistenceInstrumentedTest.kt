package echo.music.iad1tya.ui.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import echo.music.iad1tya.canvas.CanvasArtwork
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualCanvasPersistenceInstrumentedTest {
  @Test fun localUriIdsAreSavedOnlyUnderAnAppPrivateHash() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val id = "content://media/../../${UUID.randomUUID()}"
    val artwork = CanvasArtwork(name = "Test", artist = "Artist", videoUrl = "https://example.com/art.mp4")
    val hash = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    val file = File(context.filesDir, "ft_canvas_$hash.json")
    try {
      assertTrue(ManualCanvasSelections.save(context, id, artwork))
      assertEquals(artwork, ManualCanvasSelections.load(context, id)?.artwork)
      assertNull(ManualCanvasSelections.load(context, "$id-different"))
      assertTrue(file.isFile)
    } finally {
      file.delete()
    }
  }
}
