## FT Music v0.8.0

This Android release is a modified fork of [Echo Music](https://github.com/EchoMusicApp/Echo-Music), based on upstream commit `c0de840532aadc36a84475b63291fbc6452a1668`. Source and changes are available under GPLv3; see `android/LICENSE` and `android/UPSTREAM.md` for the license and attribution. FT Music is not affiliated with Echo Music.

### Which APK should I download?

Download **one** APK. All use the same FT Music package ID and signing certificate; the architecture-specific files are intended to be smaller. Android 8.0 or newer is required.

- **universal** — all four architectures; choose this if you are unsure.
- **arm64-v8a** — 64-bit ARM (ARMv8), used by most modern phones.
- **armeabi-v7a** — 32-bit ARM, for older phones or phones running 32-bit Android.
- **x86_64** — 64-bit Intel/AMD Android devices and emulators.
- **x86** — 32-bit Intel/AMD Android devices and emulators.

If you use ADB, `adb shell getprop ro.product.cpu.abilist` lists the ABIs supported by the **installed Android system**. A 64-bit processor can still run a 32-bit system; choose a listed ABI or use the universal APK. `SHA256SUMS.txt` contains the download checksums.

### What's new

**Downloads you choose, in the format YouTube actually served**

- Every **Download** tap opens a chooser listing the audio that is really available for that song — normally YouTube High (suggested) and Data Saver — with the codec, bitrate, sample rate and file size when YouTube reports them, and "Size unknown" when it does not. Nothing is estimated and nothing is labelled lossless. There is no 15 MB limit, so you can pick a larger file.
- A queued, in-progress or finished download is left alone, and a song already locked to a quality keeps it. A download that has no audio format, or a lookup that failed, is reported separately with a retry.
- Completed downloads are copied to `Downloads/FT Music` **without re-encoding**, in the container YouTube served (`.webm`, `.m4a`, `.ogg` and so on). Converting to MP3 would discard quality already paid for, so **Export as MP3** stays a separate, explicit per-song action — 320 kbps with ID3v2 tags and embedded cover — and is off by default in **Settings → Player and audio**. Files already in `Downloads/FT Music` are not converted.

**Cached is not downloaded**

- A song you only streamed is never listed as a download. The Cached list is what remains after real downloads are subtracted from it, and on every start the stored downloaded flag is reconciled against the live download list, so a song whose download record disappeared while the app was closed stops being shown as downloaded.
- Streamed audio is temporary: a player-cache entry is removed **one hour after it was first cached**. Replaying a song does not extend that hour. Downloads and files already on your device are never removed by it, and removal waits while a song is playing, crossfading or prebuffered. Ages survive a restart, tolerate a clock that jumped backwards, and are excluded from Android backups. **Settings → Storage** explains this.

**Edit info for local songs**

- **Library → Local → a song's menu → Edit info** can search YouTube Music, let you accept one result, and let you correct the title, artists, album, year and cover before **Apply**. Nothing is written until then.
- The tags are written **into the audio file itself**, so other players see them too. The audio stream is copied and never decoded or re-encoded, so tagging cannot cost quality, and the rewritten file is checked before it replaces the original — if it does not read back, it is discarded and your file is unchanged.
- MP3, MP4/M4A and FLAC accept an embedded cover. Formats that cannot hold a picture (`.ogg`, `.opus`, `.wav`, `.webm`, `.mka`) keep their text tags and tell you the cover will be left out. Formats FT Music cannot retag are refused and left untouched. Android 10 and newer may show its own permission prompt for a file another app owns; a refusal, a failed write or a result that does not read back is reported rather than hidden.

**Player**

- **Apple Music Inspired** is now the default player style; an explicit earlier choice is kept. 16:9 artwork is shown in full with a blurred, colour-matched backdrop, and other cover layouts are unchanged.
- **⋮ → Load animation** looks for matching animated artwork when a song is playing and falls back to the still cover. The audio file is never modified.
- The playback source badge is simpler: **Local** for a downloaded song or a file on your device, with the real quality details preserved. The confusing "Streaming / cache" label is gone.
- **Song DNA** and beat-matched **Automix** are removed. Ordinary crossfade remains, and YouTube's own automix/radio queues keep their separate names.

**Updating from inside the app**

- **Settings → App updates → Check for updates** shows the newest FT Music release with its version, release date, notes and the exact package it will install, and downloads it there. The APK is chosen for your phone from `Build.SUPPORTED_ABIS`, falling back to the universal package, so you are not asked to work out which architecture you have. If a release publishes nothing that fits your device, that is said plainly instead of offering a file Android would refuse.
- Nothing installs silently, and nothing unverified installs at all. The download must match the SHA-256 that the release publishes in `SHA256SUMS.txt`, and the file's signing certificate must be FT Music's pinned one. A mismatch is discarded and reported rather than offered. Only then is the file handed to Android, which still shows its own confirmation and still needs "install unknown apps" permission.
- This matters because Android's own refusal for a changed signature asks you to **uninstall first**, which would destroy your library, likes, playlists and downloads. Refusing before that point means you are never offered that choice by mistake.
- Update notifications are on by default and check GitHub at most once a day when the app opens; finding an update never downloads or installs anything. Turn it off in **Settings → App updates**, where the automatic check on opening that screen is off by default and pre-releases stay opt-in under **Beta updates**. Tapping a notification opens the update screen, not the installer, so there is exactly one gate and it is the verified one.
- Downloaded update packages are pruned: anything older than a day, and anything for a release other than the one now installed. The browser route — **Open FT Music releases** — still works exactly as before.

**Widgets, sharing and naming**

- New home-screen widgets in a mix of styles and sizes, including one that shows lyrics with a clear state when none are available. Existing widgets remain.
- The notification player follows the same rounded, artwork-forward look as far as Android permits; media-session, notification and lock-screen controls still work as before.
- **Share** on a downloaded or cached song offers a **file** or a **link**, preferring `https://music.youtube.com/watch?v=SONG_ID`. A file is only offered when a complete playable copy exists. Android decides which apps can open what you share.
- Echo naming is no longer shown in the app, including in the credits. The GPLv3 attribution to the upstream project remains in the source and in these notes.

### Important limitations

The features above passed automated checks on emulators running Android 9, 10 and 15, including real downloads, offline playback, one-hour cache expiry, local-file deletion against the physical file, and tag editing against real published audio files. The updater's release discovery, ABI choice, checksum parsing and refusal paths are unit-tested, and its certificate check is tested against a real signed APK on an emulator. What has **not** been verified on a real device: a physical-phone upgrade end to end, YouTube sign-in and sync, and streaming against live YouTube. The updater could not be exercised against a published release before that release exists, so the first real use of in-app updating will be to move **from** v0.8.0 **to** a later version. Back up anything important before installing.

Echo Music's optional YouTube account sign-in and sync are included. With sync enabled, actions in FT Music can also change liked songs and synced playlists in your YouTube account. Android Auto integration is not included; normal phone notification and lock-screen controls remain. YouTube and other third-party services may change or refuse requests at any time; check the provider's terms and download only content you are permitted to save.

**Upgrading:** v0.8.0 installs in place over v0.5, v0.6 and v0.7 — same package ID (`com.ftmusic.app`) and signing certificate, higher version code. This fork starts a fresh library: it does not import the pre-Echo v0.4 likes, playlists, downloads, lyrics, history or separate FT Music account sync, and Echo Music's own private download folder is not read. An in-place update does not intentionally erase those old files, but they will not appear in the new app. The original version remains available in the v0.4.0 release. In-app updating arrives **with** v0.8.0, so getting to v0.8.0 itself still means downloading an APK from this release page; every version after it can be installed from inside the app.
