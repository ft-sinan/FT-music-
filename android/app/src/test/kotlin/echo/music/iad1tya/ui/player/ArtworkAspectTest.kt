package echo.music.iad1tya.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkAspectTest {
  @Test fun detectsWideArtWithoutChangingSquareOrPortraitCovers() {
    assertTrue(isSixteenByNine(1920, 1080))
    assertTrue(isSixteenByNine(1280, 720))
    assertFalse(isSixteenByNine(1000, 1000))
    assertFalse(isSixteenByNine(800, 1200))
    assertFalse(isSixteenByNine(400, 300))
    assertFalse(isSixteenByNine(0, 720))
  }
}
