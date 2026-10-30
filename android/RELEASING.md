# Releasing FT Music Android

This Android app is a modified [Echo Music](https://github.com/EchoMusicApp/Echo-Music) fork. It is distributed under GPLv3; preserve the upstream attribution and license in `UPSTREAM.md` and `LICENSE`, and make the exact corresponding source and notices available for each APK release. **The Echo-based build and release workflow must be verified before publishing.**

## Updates and library compatibility

The app's **Settings → Updates** opens FT Music's GitHub releases page in the browser; it does not silently download or install an update. Pre-releases are visible there. Android enforces the application ID (`com.ftmusic.app`), signing certificate and increasing version code when installing over an earlier release. Replacing v0.4 with the Echo-based app does not import the old likes, playlists, history, downloads, lyrics or separate FT account. The old private files are not intentionally deleted during an in-place update but are not read by the new app. Warn users to back up anything important; do not claim migration.

## Signing-key safety

The existing FT Music certificate SHA-256 is pinned in `signing-cert-sha256.txt`; `scripts/sign-release.sh` refuses to sign with a different certificate or the wrong package/version. The **plaintext private key must never be committed, logged, attached unencrypted to a release or pasted into chat**.

The public repository contains an AES-256/GnuPG-encrypted backup at `.github/ft-music-signing-bundle.tar.gz.gpg`, protected by the `FT_MUSIC_KEY_PASSPHRASE` Actions secret. Anyone can download the ciphertext; its security depends on the passphrase remaining strong and secret. GitHub cannot reveal a saved secret. Keep the passphrase in a secure independent password manager or offline backup. Losing both the secret and its independent backup prevents future in-place updates.

Only reviewed workflows should ever access the secret. The release workflow builds without the passphrase, then signs the resulting artifact in a **separate fresh runner**. Gradle and its third-party plugins do not run on the signing runner. Review the scripts and workflow on `main` before dispatching it; never release from an untrusted branch. For offline recovery, decrypt the encrypted backup only on a trusted computer, keep the output private and securely delete temporary key material afterward.

## Normal release (after verification)

1. Test the app on real devices, including upgrade installation, YouTube sign-in/sync, playback and downloads. Review the GPL source/notice requirements and third-party terms. No such physical-device verification has yet been completed for this fork.
2. Increase `versionName` in `version.properties`. The build computes `major × 1,000,000 + minor × 1,000 + patch` as the Android version code. Published versions must increase; for example `0.5.0` has code `5000`.
3. Review and merge the release commit to `main`. Confirm `.github/release-notes.md` is accurate and discloses the fresh library and unverified features.
4. Run **Publish Android release** on the reviewed `main` commit, using pre-release status for unverified builds. It tests and builds the universal GMS APK in an unprivileged job, transfers the unsigned artifact to a fresh job, verifies the pinned certificate and APK identity, signs, then publishes the release. It rejects duplicate version tags.
5. Verify the tag points at the reviewed source, the `FT-Music-vMAJOR.MINOR.PATCH.apk` asset is present, and Android package/version/certificate match. Install over the previous release on a physical device before calling it stable.

For manual builds, use JDK 21, Android SDK Platform 37.0, NDK `28.2.13676358` and CMake `3.22.1`:

```bash
cd android
./gradlew testUniversalGmsDebugUnitTest assembleUniversalGmsRelease
```

From the repository root, run `android/scripts/sign-release.sh /private/path/temp-key.pk8 /private/path/temp-cert.der /private/output` with Android SDK build-tools 35.0.0 installed. The script signs exactly one unsigned universal GMS output, or the file specified by `FT_UNSIGNED_APK`, and verifies package, version, certificate, alignment and signature. Never commit the key or APK.
