## FT Music v0.6.0 pre-release

This experimental Android release is a modified fork of [Echo Music](https://github.com/EchoMusicApp/Echo-Music), based on upstream commit `c0de840532aadc36a84475b63291fbc6452a1668`. Source and changes are available under GPLv3; see `android/LICENSE` and `android/UPSTREAM.md` for the license and attribution. FT Music is not affiliated with Echo Music.

### Which APK should I download?

Download **one** APK. All use the same FT Music package ID and signing certificate; the architecture-specific files are intended to be smaller. Android 8.0 or newer is required.

- **universal** — all four architectures; choose this if you are unsure.
- **arm64-v8a** — 64-bit ARM (ARMv8), used by most modern phones.
- **armeabi-v7a** — 32-bit ARM, for older phones or phones running 32-bit Android.
- **x86_64** — 64-bit Intel/AMD Android devices and emulators.
- **x86** — 32-bit Intel/AMD Android devices and emulators.

If you use ADB, `adb shell getprop ro.product.cpu.abilist` lists the ABIs supported by the **installed Android system**. A 64-bit processor can still run a 32-bit system; choose a listed ABI or use the universal APK. `SHA256SUMS.txt` contains the download checksums.

### What's new

- A simpler FT Music welcome screen and a Creator Space with Sinan's public contacts.
- Completed FT downloads are saved as playable files in `Downloads/FT Music`. Removing a download in FT removes its matching shared file; a file removed in Files is reconciled when FT resumes or refreshes. If saving cannot be verified, FT keeps the private cached copy and shows a storage warning. Android 10 and older need storage access.
- Home and Search can prefer a confident local match; when the match is uncertain, FT asks before playing it and offers the online choice.
- The player shows its playback source and available actual audio format/quality; unknown values are not guessed.
- **Library → Local → More options → Delete from device** deletes the selected file after confirmation and removes its FT playlist and listening-history entries. This is permanent. Android may also request permission or confirmation. Canceling leaves the file in place.

### Important limitations

The features above passed emulator checks on Android 9, 10 and 15, including checking the physical local file after deletion. Physical-phone upgrades, YouTube sign-in/sync, streaming and other integrations still need real-device testing. Back up important data before installing a pre-release.

Echo Music's optional YouTube account sign-in and sync are included. With sync enabled, actions in FT Music can also change liked songs and synced playlists in your YouTube account. Android Auto integration is not included; normal phone notification and lock-screen controls remain. YouTube and other third-party services may change or refuse requests; check the provider's terms and download only content you are permitted to save.

**Upgrading from v0.4:** This Echo-based fork does not import the v0.4 likes, playlists, downloads, lyrics, history or separate FT Music account sync. An in-place update does not intentionally erase the old files, but they won't appear in the new app. Echo Music's separate private downloads are not imported. Back up anything important first; the original version remains available in the v0.4.0 release.
