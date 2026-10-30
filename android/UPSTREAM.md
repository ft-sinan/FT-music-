# Android upstream and license

The Android app is a modified fork of [Echo Music](https://github.com/EchoMusicApp/Echo-Music), copyright its authors and contributors. The source snapshot comes from commit `c0de840532aadc36a84475b63291fbc6452a1668` (2026-10-29). Original copyright notices and upstream contributor credits remain in the source and About screen. This fork is not affiliated with or endorsed by Echo Music.

Echo Music is licensed under the GNU General Public License, version 3. The full license is in [LICENSE](LICENSE). The Android fork and modifications are distributed under that license; anyone distributing an APK must also comply with the GPL's source and notice requirements. Keep the exact corresponding source available for every distributed build.

FT Music changes include its name, icon, package ID, versioning, update destination and signing integration. Android Auto discovery and its autoplay setting are removed; normal phone notification and lock-screen controls remain. Echo's optional YouTube account sign-in and sync are retained. The original FT Music v0.4 library is not imported into this fork. No existing private files are intentionally deleted during an in-place update, but old likes, playlists, history, downloads, lyrics and separate FT account sync are not available in the new interface.

The upstream `google-services.json` is intentionally omitted: it belongs to Echo's Firebase project and is not configured for FT Music. Firebase requires an FT-owned configuration. The upstream Last.fm client credentials are not reused; supply FT-owned values in local properties or the environment if that integration is desired. Never commit private credentials. Some other optional integrations use third-party services or upstream-operated endpoints and may need independent configuration or permission; their availability has not been verified for this fork.

The bundled font `google_sans_flex.ttf` in the upstream snapshot states that it is not open source and is omitted from this fork. The default uses the official SIL-OFL Google Sans Flex font instead; corresponding font notices are in `third-party/fonts/`.
