package echo.music.iad1tya.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sign-in WebView holds Google credentials, so the set of hosts it may load on its own is a
 * security boundary and not a convenience: everything outside it is handed to the system browser.
 *
 * The interesting cases are the near misses. A host that merely contains "google." is not a Google
 * host, and a real Google country domain is not an attacker's - the rule has to tell those apart.
 */
class LoginHostsTest {
  @Test
  fun googleAndYouTubeHostsAreAllowed() {
    listOf(
      "accounts.google.com",
      "google.com",
      "apis.google.com",
      "support.google.com",
      "music.youtube.com",
      "www.youtube.com",
      "ssl.gstatic.com",
      "lh3.googleusercontent.com",
      "www.googleapis.com",
      "r1.googlevideo.com",
      "g.co",
    ).forEach { host ->
      assertTrue("$host is part of Google sign-in", isAllowedLoginHost(host))
    }
  }

  @Test
  fun hostsAreNormalisedBeforeTheyAreJudged() {
    listOf(
      "ACCOUNTS.GOOGLE.COM",
      "accounts.google.com.",
      "  music.youtube.com  ",
      "Music.YouTube.COM.",
    ).forEach { host ->
      assertTrue("$host is an allowed host written differently", isAllowedLoginHost(host))
    }
  }

  /**
   * The failure mode a substring or unanchored pattern has: a domain that *ends* in something
   * trusted-looking is not trusted. `google.com.attacker.example` is attacker.example's host.
   */
  @Test
  fun aTrustedNameInsideAnUntrustedDomainIsRefused() {
    listOf(
      "google.com.attacker.example",
      "google.co.uk.evil.net",
      "accounts.google.com.phishing.test",
      "youtube.com.attacker.example",
      "gstatic.com.evil.io",
      "g.co.evil.io",
      "google.com.evil.net",
    ).forEach { host ->
      assertFalse("$host is not a Google host", isAllowedLoginHost(host))
    }
  }

  @Test
  fun lookalikeDomainsAreRefused() {
    listOf(
      "evil-google.com",
      "notgoogle.com",
      // Google's other domains are not sign-in domains, and a rule broad enough to admit country
      // domains would also admit google.xyz and google.net, which are nobody's but an attacker's.
      // These open in the browser instead, which works.
      "google.org",
      "google.co.uk",
      "google.com.au",
      "google.de",
      "google.xyz",
      "google",
      "googlecom",
      "accounts-google.com",
      "youtube.com.evil",
      "google.account.example",
      "attacker.example",
      "localhost",
      "",
      ".",
      "   ",
    ).forEach { host ->
      assertFalse("$host must not load in the sign-in WebView", isAllowedLoginHost(host))
    }
  }
}
