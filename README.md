# FT Music

FT Music contains a responsive React web companion and a native Android app. The Android app is a GPLv3-licensed, rebranded fork of Echo Music; the web companion is a separate project with a small public-domain catalog.

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

## Android app: Echo Music fork

The Android app is based on [Echo Music](https://github.com/EchoMusicApp/Echo-Music) commit `c0de840532aadc36a84475b63291fbc6452a1668`, rebranded as FT Music. Its authors and contributors retain credit; this fork is not affiliated with or endorsed by Echo Music. The Android source is licensed under GPLv3: see [`android/LICENSE`](android/LICENSE) and [`android/UPSTREAM.md`](android/UPSTREAM.md). Every distributed Android APK must be accompanied by its corresponding source and notices as required by that license.

Echo's optional YouTube account sign-in and sync are included. Signing in is optional, uses Google's sign-in page inside the app, and gives the app access to that account's YouTube Music data. When sync is enabled, actions in FT Music can also change liked songs and synced playlists in the YouTube account. YouTube and the app's other providers receive network requests, can block or change access, and are subject to their own terms and privacy policies. Verify account sync, discovery, playback and downloads on a real device before relying on them; download only content you have permission to save. The app stores account sessions in its private settings. Echo’s manual ZIP backups and Android backups may include those sessions; keep backups private and never share them. No Android Auto integration is advertised; normal phone notification and lock-screen media controls are retained.

**Fresh library:** This is a replacement of the old Android prototype, not an upgrade of its storage format. Existing FT Music v0.4 likes, playlists, history, downloads, lyrics and the separate FT account sync do not appear in the Echo-based app. The old private files are not intentionally deleted by an in-place update, but this fork does not read them. Back up anything important first. The separate `sync-server/` remains in the repository for the old prototype and is **not connected** to this fork. The independent web companion is unaffected.

**Shared downloads and device playback (development branch):** Completed FT downloads are copied without re-encoding to `Downloads/FT Music` using their actual container format. Android 10 and older need storage permission. The private cache is retained if the copy cannot be verified, permission is denied, or storage is unavailable; check Storage settings for a warning and retry. The app removes its shared file when its download is removed, and reconciles files deleted outside the app on resume or when the Downloaded list is refreshed. Existing FT Music download caches are handled in the same way; Echo Music's separate private downloads are not imported. Android backups exclude device-specific file mappings. Allow audio access to look for device copies when selecting an online song: an exact title, artist and duration match can play automatically; ambiguous matches are offered for confirmation or the original online version (which may already be cached). Source and quality information reflects the selected playback track when available, rather than a guessed bitrate. In the Local library, a song’s menu offers **Delete from device**; this deletes the actual file after confirmation, not just the listing, and removes its FT Music playlist and listening-history entries. Android may also request storage permission or display its own delete confirmation. Canceling or denying permission leaves the file in place. These new behaviors still require real-device verification.

Firebase is optional; the upstream `google-services.json` is deliberately excluded because it belongs to Echo Music and does not match FT Music's app ID. Configure an FT-owned project if desired. The old upstream Last.fm client credentials are not bundled. Some optional integrations depend on third-party services and may require FT-owned credentials or configuration; they have not been verified for this fork.

### Android builds and updates

Use JDK 21, Android SDK Platform 37.0, NDK `28.2.13676358` and CMake `3.22.1`:

```bash
cd android
./gradlew testUniversalGmsDebugUnitTest assembleUniversalGmsDebug
```

The universal GMS variant retains Echo's optional Google Cast support; `assembleUniversalFossDebug` is the upstream FOSS variant. A debug APK has a different package suffix and signing key and is **not** an in-place update. `assembleUniversalGmsRelease` generates an unsigned release; [`android/RELEASING.md`](android/RELEASING.md) describes the protected signing key and release process. Emulator checks covered launch, the Google sign-in page, local playback and system media controls, plus a signed in-place upgrade from v0.4 with a synthetic legacy library. The new fork has not yet been verified on a physical phone.

The app's **Settings → Updates** opens [FT Music releases](https://github.com/ft-sinan/FT-music-/releases) manually, not Echo's APK site. Published in-place updates must use the same application ID (`com.ftmusic.app`), signing certificate and a higher version code. The selected fresh-library behavior still applies. Android asks the user to confirm installation.

## Web companion music and artwork credits

The web companion recordings are public-domain/CC0 tracks. They were re-encoded to 112 kbps MP3:

- **HoliznaCC0:** “Blue Skies”, “City In The Rearview”, “Morning Coffee”, “Day Dreams”, “Deja Vu”, “Night Life”, “Breath”, and “Happy Dance”. Source: [Alvana Audio](https://github.com/seyedpouramir/alvana-audio), which credits [Holizna](https://opengameart.org/users/holizna) and identifies these recordings as [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/).
- **DURU-AI:** “Roomscene Lo-fi” and “Rhythm Fever”. Source: [DURU-AI CC0 BGM](https://github.com/uncle-sheepsky/duru-ai-cc0-bgm), released under [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/).

Playlist artwork and the featured image were generated for this starter app; they are not the original artists' album covers.
