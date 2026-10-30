# Releasing FT Music Android

The app checks the public GitHub releases page and opens a matching release for the user to install; it does not install updates silently. Releases must use this repository, numeric `vMAJOR.MINOR.PATCH` tags, and an uploaded asset named `FT-Music-vMAJOR.MINOR.PATCH.apk`. Drafts and releases without that asset are not offered. Pre-releases are offered but clearly marked. The APK must keep application ID `com.ftmusic.app` and be signed by the existing FT Music key. Android enforces the signature and increasing version code during installation.

## Signing-key safety

The certificate SHA-256 fingerprint is pinned in `signing-cert-sha256.txt`; `scripts/sign-release.sh` refuses to sign with a different certificate. The **plaintext private key is never in this repository** and must never be committed, attached unencrypted to a release, logged, or pasted into chat.

The public repository contains an AES-256/GnuPG-encrypted key backup at `.github/ft-music-signing-bundle.tar.gz.gpg`. It is encrypted with a long randomly generated passphrase stored separately as the repository's `FT_MUSIC_KEY_PASSPHRASE` Actions secret. Anyone can download the ciphertext; its security depends on the passphrase remaining strong and secret. GitHub does not let you retrieve a saved secret. The owner must keep the passphrase in a secure independent password manager or offline backup. If both the GitHub secret and the passphrase backup are lost, the signing key cannot be recovered and future APKs cannot update existing installs in place.

Repository administrators and workflows with access to the secret can use the key, so restrict write and workflow permissions. To make an offline backup, save a copy of the encrypted `.gpg` file and the passphrase separately in secure storage. If you need to decrypt it locally for manual signing, run `gpg --output signing-bundle.tar.gz --decrypt .github/ft-music-signing-bundle.tar.gz.gpg` from a trusted computer, entering the passphrase only into GnuPG's prompt. Keep the decrypted key files private, delete temporary copies securely, and never commit them.

## Normal release

1. Review and test the Android app on real devices and with live providers. This prototype has not yet had that verification. Review public release notes and legal/provider terms.
2. Increase `versionName` in `version.properties`. The Android build computes `major × 1,000,000 + minor × 1,000 + patch`; major is at most 2099 and minor/patch at most 999. Every published release must increase this number. For example `0.3.0` is version code `3000`, and `0.3.1` is `3001`.
3. Commit and push the change to the reviewed release branch. The workflow must exist on the default branch for GitHub's manual workflow-dispatch UI to offer it; merge the release workflow through the normal review process first.
4. In GitHub Actions, run **Publish Android release** on the reviewed commit. Leave **pre-release** enabled for unverified builds. It checks existing release versions, runs the native unit test and Gradle release build, decrypts the key only in the ephemeral runner, verifies the pinned certificate, signs the APK, and creates the tag and GitHub Release. The workflow requires `contents: write` and permission to use Actions secrets. Do not run a workflow from an untrusted branch while exposing the passphrase.
5. Confirm the release asset, tag, application ID, version and certificate; install over the previous version on a real device to verify update behavior before calling it stable.

For manual signing after `cd android && ./gradlew testDebugUnitTest assembleRelease`, run `android/scripts/sign-release.sh /private/path/temp-key.pk8 /private/path/temp-cert.der /private/output` from the repository root with Android SDK build-tools 35.0.0 installed. It checks the APK package/version and certificate, aligns, signs and verifies the output. Do not commit the generated APK or private files.
