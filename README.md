# FT Music

FT Music contains a responsive React web app and a separate native Android prototype. Both start with a small, full-length public-domain catalog, so you can listen without an account or API key.

## Run locally

```bash
npm install
npm run dev
```

Open the URL printed by Vite. To build and serve the production version:

```bash
npm run build
npm run preview
```

Run the regression tests with `npm test`.

## What's included

- Discover curated mood mixes or search the bundled songs, artists, albums, and playlists. The YouTube section can also play supported YouTube and YouTube Music video links, and search YouTube when an API key is configured.
- Play full tracks, seek, adjust volume, shuffle, repeat, and manage the upcoming queue. Supported browsers also expose playback controls on the lock screen or in system media controls.
- Like tracks, save curated mixes, create playlists, add/remove songs, and revisit listening history. These are stored in the browser's local storage on the current device.
- Responsive desktop and mobile web layouts, with an expanded mobile player. The web production build includes a manifest and caches its application shell for installation; **web audio is streamed from the server and is not downloaded for offline playback**.

## YouTube video playback and search (web)

Open **YouTube** in the navigation. Paste a YouTube or YouTube Music **video** URL (for example, a `watch?v=...` or `youtu.be/...` link) and select **Search / play**. Link playback does not need an API key. Playlist-only links are not supported. The **Search on YouTube Music** link offers another way to find a video and copy its link.

To search for YouTube videos inside FT Music, configure the official **YouTube Data API v3**:

1. In Google Cloud, create or select a project and enable **YouTube Data API v3**.
2. Create an API key, restrict it to **YouTube Data API v3**, and add website (HTTP referrer) restrictions for the origins where the app will run. Include your local development origin and each deployed/preview origin as appropriate. See [Google's API key restrictions guidance](https://cloud.google.com/docs/authentication/api-keys#api_key_restrictions).
3. Copy `.env.example` to `.env.local` and set `VITE_YOUTUBE_API_KEY` in that local file. Never paste your key into chat or commit it to the repository. Vite embeds variables prefixed `VITE_` into browser code, so this key is **visible to visitors**: it is not a secret, and API/referrer restrictions and quota monitoring are essential.
4. Restart the dev server, or rebuild/redeploy the app after changing the key. YouTube search uses API quota; quota exhaustion, a blocked origin, or a disabled API can prevent search. Video links continue to work without the key.

YouTube does not offer a public general-purpose YouTube Music audio-streaming API. This integration uses YouTube Data API video search (filtered to embeddable music-category videos) and YouTube's visible embedded video player; it is **not** the complete YouTube Music catalog or a YouTube Music account. Search results and playback can vary by region and video, and the owner can disable embedding. YouTube controls ads and buffering. The video stops when the player is closed or the tab becomes hidden; background/audio-only and offline YouTube playback are not supported. FT Music's likes, playlists, queue, and local playback controls apply only to the bundled tracks, not YouTube videos. Opening a YouTube video pauses local audio.

Using the YouTube section sends requests to YouTube/Google and is subject to the [YouTube Terms of Service](https://www.youtube.com/t/terms) and [Google Privacy Policy](https://policies.google.com/privacy). An internet connection is required for search and video playback. A larger catalog of downloadable audio, user accounts, and cross-device synchronization would require appropriate provider permissions/licensing and a backend.

## Native Android prototype

The `android/` directory is a separate Android application inspired by Prism's phone-first home and player, not a copy of Prism's code or assets. It includes a native home screen, search, on-device likes, playlists and recent history, a queue with shuffle/repeat, an expanded player, and a media playback service with notification and lock-screen controls. The ten CC0 recordings and artwork are included at build time. The Android and web libraries are separate; the optional account service below syncs **Android devices only**.

### Lyrics and downloads

- **Lyrics:** Open the player and tap **View lyrics**. FT Music checks for an imported LRC file first; otherwise it sends the song title and artist to [LRCLIB](https://lrclib.net/) to look for matching lyrics. Timed lines follow the playback position and tapping a line seeks. Plain lyrics are explicitly marked *unsynced*; instrumental, unavailable or mismatched tracks may have no lyrics. **Import LRC** copies a chosen UTF-8, line-timed `.lrc` file into private app storage; imported lyrics do not sync.
- **Downloads:** For a YouTube search result, open the track's **•••** menu and choose **Download for offline**. Download only content you have permission to save, and comply with YouTube's terms. This experimental feature accepts only directly returned, unencrypted compatible audio (no DRM or signature-cipher bypass); YouTube may refuse the request or return no suitable stream. It rejects partial/unknown-size responses and files over 200 MB. Keep the app open until it finishes: Android can interrupt a job, which must then be retried. Finished audio is private to the app, appears under **Library → Downloads**, and is preferred during playback; use the track menu to delete it. Downloads do not transfer to another device and are removed if the app is uninstalled.

Android YouTube Music search and playback use **anonymous, undocumented interfaces**, not a supported Google API. They can change or be blocked at any time; availability varies by track and region. The app never asks for a Google/YouTube password. Google receives search, stream and download requests and its terms apply. Live YouTube Music, LRCLIB and Android playback have not been tested in this sandbox. Android Auto integration is not included.

### GitHub releases and updates

On Android, open **Library → Check for updates** to check the public [GitHub Releases](https://github.com/ft-sinan/FT-music-/releases) page. The check is manual, sends a request to GitHub without an account token, labels pre-releases, and opens GitHub in your browser rather than silently installing anything. Download the APK attached to the release and install it over your existing app; Android asks for confirmation and verifies the signing certificate. Do not uninstall first if you want to keep local data. GitHub/network availability and device/browser installation behavior have not been verified on a phone.

Updates require the same application ID (`com.ftmusic.app`), the **same signing key**, and an increasing Android `versionCode`. `android/version.properties` supplies a numeric `major.minor.patch`; the build derives `major × 1,000,000 + minor × 1,000 + patch` as the version code. Releases must have a matching tag such as `v0.3.1` and an uploaded APK named `FT-Music-v0.3.1.apk`. The repository's manual release workflow checks these rules and the existing signing certificate. See [the release guide](android/RELEASING.md) for secure signing and maintenance. **Losing the signing passphrase prevents in-place updates**. The signing-key backup is encrypted before being stored in the public repository; keep the separate passphrase safe and never publish the plaintext key or paste the passphrase into chat.

### Separate private FT Music account

Account sync is optional and separate from any Google or YouTube account. There is **no hosted FT Music service** in this repository: you must run and secure the included `sync-server/` yourself. Only liked songs and playlists (including track metadata) are synced. History, downloaded audio and imported lyrics remain local. The app stores the server address, username, and a local merge baseline; passwords and session tokens remain in memory only. Sign in again after closing/restarting the app or when a 12-hour session expires. Server restarts also invalidate sessions.

The first sync with a given account is **additive** and merges the device's existing likes and playlists with the server. Later syncs use a saved common baseline, propagate removals, merge independent changes, and use revision checks to prevent a stale upload silently overwriting another device. A playlist deleted on one device and changed on another is reported as a conflict instead of being overwritten; review the playlist on the devices before trying again. If a device is reinstalled or its baseline is lost, the next sync is additive again and may restore items that were deleted elsewhere. Edits made on a device during an in-flight sync are kept locally and can be merged by syncing again.

**Private service setup (Node.js 18+):**

1. On a machine you administer, choose a private, backed-up data directory outside the repository. By default the service listens on `127.0.0.1:8787` only and stores salted scrypt password hashes and **unencrypted library data** in `accounts.json` (mode 0600). Restrict access to the host and backups.
2. Start the loopback service, for example:

   ```bash
   DATA_DIR=/path/to/private/ft-music-data node sync-server/server.mjs
   ```

3. Make it reachable from your Android devices **over HTTPS** with a certificate trusted by Android. Use a trusted local TLS reverse proxy or configure TLS directly, e.g. `TLS_CERT=/path/fullchain.pem TLS_KEY=/path/privkey.pem HOST=0.0.0.0 PORT=8787 DATA_DIR=/path/to/private/ft-music-data node sync-server/server.mjs`. An HTTP server is deliberately limited to loopback. Control network access with a firewall/VPN as appropriate. Do not disable certificate verification; a self-signed certificate is not normally trusted by Android. No service has been deployed for you.
4. In **Library → FT account & sync**, enter the HTTPS origin (no URL path), a 3–32 character username, and a unique password of at least 12 characters, then choose **Create account**. On another device use the same server and **Sign in & sync**. Use **Sync now** after changes. The service uses in-memory sessions, basic per-IP rate limiting and single-process, atomic file updates; do not run multiple instances against the same directory. It has no password-reset, MFA, automatic backups or security audit and is a prototype, not a production identity provider.

Run server tests with `node --test sync-server/server.node-test.mjs`. They cover account separation, authentication, persistence, optimistic revisions and validation. They do not substitute for a production security review.

### Build Android

Open `android/` in Android Studio with JDK 17 and Android SDK Platform 35 installed, or run:

```bash
cd android
./gradlew assembleDebug
```

The debug APK will be at `android/app/build/outputs/apk/debug/app-debug.apk`. The build copies the bundled `public/audio` and `public/images` assets from the repository; keep the web assets alongside the Android project. `./gradlew testDebugUnitTest` runs the native core tests.

This is an **early prototype**, not a full Prism replacement. The Android Gradle build and unit tests passed in GitHub Actions, but device installation, UI/playback and live providers have not been verified. Parsers and merge logic were also tested with fixtures, and the sync client was exercised against a temporary local HTTPS server. A separately assembled trial APK, when provided, uses a temporary development signing key. A later build with a different key requires uninstalling the trial, which removes its local library and downloads. Back up anything important before experimenting.

## Music and artwork credits

The included recordings are public-domain/CC0 tracks. They were re-encoded to 112 kbps MP3 for the app:

- **HoliznaCC0:** “Blue Skies”, “City In The Rearview”, “Morning Coffee”, “Day Dreams”, “Deja Vu”, “Night Life”, “Breath”, and “Happy Dance”. Source: [Alvana Audio](https://github.com/seyedpouramir/alvana-audio), which credits [Holizna](https://opengameart.org/users/holizna) and identifies these recordings as [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/).
- **DURU-AI:** “Roomscene Lo-fi” and “Rhythm Fever”. Source: [DURU-AI CC0 BGM](https://github.com/uncle-sheepsky/duru-ai-cc0-bgm), released under [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/).

Playlist artwork and the featured image were generated for this starter app; they are not the original artists' album covers.
