package echo.music.iad1tya.ui.screens

/**
 * The hosts the sign-in WebView may load on its own. Everything else is handed to the system
 * browser, which keeps a page FT Music did not expect out of a WebView that is holding Google
 * credentials.
 *
 * Matched as a registrable-domain suffix, never as a substring or an unanchored pattern: a pattern
 * looking for "google." anywhere in the host also accepts `google.com.attacker.example`, which is
 * attacker.example's host and not Google's. Upstream ships exactly that pattern.
 *
 * The list is deliberately short, and deliberately `google.com` only. Google runs sign-in from
 * accounts.google.com worldwide, and every host the flow really touches - accounts, apis, support,
 * ssl.gstatic, googleusercontent, music.youtube - is covered here. A rule broad enough to admit
 * Google's ~190 country domains would be a rule that also admits `google.xyz` and `google.net`,
 * which are not Google's. A country page that turns out to matter is added when there is evidence
 * it does, rather than allowed now on speculation; until then it opens in the browser, which works.
 */
private val ALLOWED_LOGIN_HOST_SUFFIXES =
  listOf(
    "google.com",
    "youtube.com",
    "gstatic.com",
    "googleusercontent.com",
    "googleapis.com",
    "googlevideo.com",
    "g.co",
  )

internal fun isAllowedLoginHost(host: String): Boolean {
  val normalized = host.trim().trimEnd('.').lowercase()
  if (normalized.isEmpty()) return false
  return ALLOWED_LOGIN_HOST_SUFFIXES.any { normalized == it || normalized.endsWith(".$it") }
}
