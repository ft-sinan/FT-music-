package echo.music.iad1tya.ui.player

import androidx.datastore.preferences.core.mutablePreferencesOf
import echo.music.iad1tya.constants.DefaultPlayerAppearance
import echo.music.iad1tya.constants.PlayerBackgroundStyle
import echo.music.iad1tya.constants.PlayerBackgroundStyleKey
import echo.music.iad1tya.constants.UseNewPlayerDesignKey
import echo.music.iad1tya.extensions.toEnum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultPlayerAppearanceTest {
  @Test fun unsetPreferencesUseAppleInspiredLayoutAndBackground() {
    val preferences = mutablePreferencesOf()
    val useNewDesign = preferences[UseNewPlayerDesignKey] ?: DefaultPlayerAppearance.USE_NEW_PLAYER_DESIGN
    val background = preferences[PlayerBackgroundStyleKey].toEnum(DefaultPlayerAppearance.BACKGROUND_STYLE)
    assertFalse(useNewDesign)
    assertEquals(PlayerBackgroundStyle.APPLE_MUSIC, background)
  }

  @Test fun unknownBackgroundFallsBackToAppleInspired() {
    val preferences = mutablePreferencesOf(PlayerBackgroundStyleKey to "UNKNOWN_STYLE")
    assertEquals(
      PlayerBackgroundStyle.APPLE_MUSIC,
      preferences[PlayerBackgroundStyleKey].toEnum(DefaultPlayerAppearance.BACKGROUND_STYLE),
    )
  }

  @Test fun explicitAppearanceChoicesArePreserved() {
    val preferences = mutablePreferencesOf(
      UseNewPlayerDesignKey to true,
      PlayerBackgroundStyleKey to PlayerBackgroundStyle.GRADIENT.name,
    )
    val useNewDesign = preferences[UseNewPlayerDesignKey] ?: DefaultPlayerAppearance.USE_NEW_PLAYER_DESIGN
    val background = preferences[PlayerBackgroundStyleKey].toEnum(DefaultPlayerAppearance.BACKGROUND_STYLE)
    assertTrue(useNewDesign)
    assertEquals(PlayerBackgroundStyle.GRADIENT, background)
  }
}
