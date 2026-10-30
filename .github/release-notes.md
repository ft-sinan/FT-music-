## FT Music for Android

This experimental release is a rebranded fork of [Echo Music](https://github.com/EchoMusicApp/Echo-Music), based on upstream commit `c0de840532aadc36a84475b63291fbc6452a1668`. The Android source and changes are available under GPLv3; see `android/LICENSE` and `android/UPSTREAM.md` for the license and attribution. FT Music is not affiliated with Echo Music.

Echo Music's optional YouTube account sign-in and sync are included. With sync enabled, actions in FT Music can also change liked songs and synced playlists in your YouTube account. Android Auto integration is not included. YouTube and other third-party services may change or refuse requests; check the provider's terms and download only content you are permitted to save.

**Fresh library:** This fork does not import the FT Music v0.4 likes, playlists, downloads, lyrics, history or separate FT Music account sync. An in-place update does not intentionally erase the old files, but they won't appear in the new app. Back up anything important before installing. The original version remains available in the v0.4.0 release.

The APK uses the same Android application ID and signing certificate as v0.4, with a higher version code. An emulator upgrade from the published v0.4 APK succeeded and retained a synthetic legacy library file, but the old library did not appear in the new app. Back up important data first; physical-device upgrades, YouTube account sync, streaming, downloads, and other optional integrations still need real-device verification.
