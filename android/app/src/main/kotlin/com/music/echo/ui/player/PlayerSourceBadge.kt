package echo.music.iad1tya.ui.player

import echo.music.iad1tya.playback.PlaybackOrigin

/** Source labels are deliberately limited to confirmed local playback or casting. */
internal enum class PlayerSourceBadge { LOCAL, CASTING }

internal fun playerSourceBadge(origin: PlaybackOrigin?, isCasting: Boolean): PlayerSourceBadge? =
  when {
    isCasting -> PlayerSourceBadge.CASTING
    origin == PlaybackOrigin.DEVICE || origin == PlaybackOrigin.FT_DOWNLOAD -> PlayerSourceBadge.LOCAL
    else -> null
  }

internal fun playerDetailsText(sourceLabel: String?, qualityLabel: String): String =
  if (sourceLabel == null) qualityLabel else "$sourceLabel · $qualityLabel"
