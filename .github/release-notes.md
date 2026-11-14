## FT Music v0.8.1

A fix release. Two things were wrong in v0.8.0, both of which are now fixed, and nothing else changed.

This Android release is a modified fork of [Echo Music](https://github.com/EchoMusicApp/Echo-Music), based on upstream commit `c0de840532aadc36a84475b63291fbc6452a1668`. Source and changes are available under GPLv3; see `android/LICENSE` and `android/UPSTREAM.md` for the license and attribution. FT Music is not affiliated with Echo Music.

### What is fixed

**Apostrophes no longer corrupt your tags**

- **Edit info** and **Export as MP3** built their ffmpeg command as one shell-style string and escaped each value for a POSIX shell. ffmpeg is not run through a shell here: the library that runs it splits that string with its own parser, which reads `'` as a quote delimiter and does not understand the `'\''` escape. The quotes stayed balanced, so nothing crashed and ffmpeg reported success — but every apostrophe reached it with a literal backslash in front.
- So a song titled **Don't Stop Me Now** was written into the file as **Don\'t Stop Me Now**, and the same for artists like **O'Brien** or albums with a quote in the name. Since apostrophes are everywhere in music, this affected most edits. In Edit info the title can also come from a YouTube Music search result, not only from your own typing.
- Both features now hand ffmpeg a list of arguments instead of a string, so no parser ever sits between FT Music and the value. There is nothing left to escape, and the escaping helper is deleted rather than repaired.
- **If you edited tags on v0.8.0:** the corruption is already written into those files. Open **Edit info** again on v0.8.1 and press **Apply** — the tag is rewritten correctly over the old one. The audio itself was never affected, only the text tags.

**Google sign-in works, and signing in no longer kills the app**

- Google rejects sign-in pages that identify themselves as an embedded WebView, and the two-factor step used to stall with no error message. The WebView no longer advertises itself as embedded, so 2FA and the rest of the flow complete.
- A successful sign-in used to relaunch the app and then call a process exit, which tore down playback and every in-memory queue. It now navigates back to where you were.
- Google's accounts and YouTube domains are different origins, so the sign-in cookie counts as third-party and was being dropped; it is now accepted. DOM storage, databases and new windows are enabled, which Google's pages depend on — without them the page failed part way, and help or recovery links did nothing at all when tapped.
- Sign-in can now also finish on `www.youtube.com` rather than only `music.youtube.com`, which is where a two-factor step often lands. It is accepted there only when the cookie really carries an authentication token, and the host is judged by parsing the URL — never by matching a prefix, so a lookalike domain cannot qualify.
- The set of hosts the sign-in WebView may load on its own is now an explicit list of Google and YouTube domains, matched as whole domains. Anything else is handed to your system browser, so a page FT Music did not expect never loads inside a WebView that is holding your Google credentials.
- If your account details cannot be confirmed after sign-in — usually a transient network error — the login is still kept, and you are told the details could not be confirmed rather than being shown a success message FT Music cannot back up.

### Which APK should I download?

Download **one** APK. All use the same FT Music package ID and signing certificate; the architecture-specific files are intended to be smaller. Android 8.0 or newer is required.

- **universal** — all four architectures; choose this if you are unsure.
- **arm64-v8a** — 64-bit ARM (ARMv8), used by most modern phones.
- **armeabi-v7a** — 32-bit ARM, for older phones or phones running 32-bit Android.
- **x86_64** — 64-bit Intel/AMD Android devices and emulators.
- **x86** — 32-bit Intel/AMD Android devices and emulators.

If you use ADB, `adb shell getprop ro.product.cpu.abilist` lists the ABIs supported by the **installed Android system**. A 64-bit processor can still run a 32-bit system; choose a listed ABI or use the universal APK. `SHA256SUMS.txt` contains the download checksums.

### Important limitations

The tag fix is proved against the real bundled ffmpeg on emulators running Android 9, 10 and 15: an apostrophe-bearing title, artist and album are written to a published audio file and read back out of its bytes, with an assertion that no backslash survives. The sign-in host rule is unit-tested, including the near misses — a trusted name inside an attacker's domain, and lookalike domains.

What has **not** been verified: Google sign-in against a live Google account, including a real two-factor challenge. That needs a real account and cannot be automated here, so the sign-in fixes are built from upstream's own fix for the same failure plus the tightened host rule, and they should be treated as unconfirmed until you try them. Back up anything important before installing.

YouTube account sync is unchanged. With sync enabled, actions in FT Music can also change liked songs and synced playlists in your YouTube account. Android Auto integration is not included; normal phone notification and lock-screen controls remain. YouTube and other third-party services may change or refuse requests at any time; check the provider's terms and download only content you are permitted to save.

**Upgrading:** v0.8.1 installs in place over v0.8.0, v0.6.0 and v0.5.0 — same package ID (`com.ftmusic.app`) and signing certificate, higher version code. Uninstalling is never needed and would destroy your library, likes, playlists and downloads.

If you are on v0.8.0, this is the **first update you can install from inside the app**: **Settings → App updates → Check for updates**. It will pick the right APK for your phone, check it against the published SHA-256 and FT Music's signing certificate, and then hand it to Android, which still asks you to confirm. If you are on v0.6.0 or earlier, the app cannot update itself yet, so download an APK from this release page and install it over the top.

There is no v0.7.0 — the version number skips it, because the work that was going to be v0.7.0 shipped in v0.8.0 instead.
