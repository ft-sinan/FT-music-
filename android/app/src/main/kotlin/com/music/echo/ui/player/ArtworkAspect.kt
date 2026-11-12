package echo.music.iad1tya.ui.player

/** Match ordinary 16:9 artwork without changing the rendering of square or portrait covers. */
internal fun isSixteenByNine(width: Int, height: Int): Boolean {
  if (width <= 0 || height <= 0) return false
  val ratio = width.toDouble() / height
  return ratio in 1.72..1.84
}
