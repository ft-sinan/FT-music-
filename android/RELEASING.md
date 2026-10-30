# Releasing FT Music Android

The Android app can check the public GitHub releases page and open a matching release for a user to install. It does not install updates silently. Releases must use this repository, numeric `vMAJOR.MINOR.PATCH` tags, and an uploaded asset named `FT-Music-vMAJOR.MINOR.PATCH.apk`. Drafts and releases without that asset are not offered. Pre-releases are offered but clearly marked. The APK must keep application ID `com.ftmusic.app` and be signed by the existing FT Music key. Android enforces the signature and increasing version code during installation.

## Signing-key safety

The certificate SHA-256 fingerprint is pinned in `signing-cert-sha256.txt`; `scripts/sign-release.sh` refuses to sign with a different certificate. The **private key is not in this repository** and must never be committed, attached to a public release, logged, or pasted into chat. The repository's `FT_MUSIC_SIGNING_BUNDLE` Actions secret contains a base64-encoded gzip tar with exactly `temp-key.pk8` (PKCS#8 private key) and `temp-cert.der` (X.509 certificate). GitHub encrypts Actions secrets; repository administrators and workflows with access to the secret can nonetheless use the key, so restrict write and workflow permissions. GitHub does not let you retrieve a saved secret. Keep an independently secured offline backup of the private key; if the secret and backup are both lost, future APKs cannot update existing installations in place. Backups must be encrypted, stored outside Git and controlled by the owner.

If you have a secure local copy of the original key and certificate and need to configure the secret yourself, from a trusted computer and without putting secret content in terminal history or chat:

```bash
umask 077
# Set KEY_DIR to the private directory containing temp-key.pk8 and temp-cert.der.
tar -czf /tmp/ft-music-signing-bundle.tar.gz -C "$KEY_DIR" temp-key.pk8 temp-cert.der
base64 < /tmp/ft-music-signing-bundle.tar.gz | tr -d '\n' | gh secret set FT_MUSIC_SIGNING_BUNDLE --repo ft-sinan/FT-music-
rm -f /tmp/ft-music-signing-bundle.tar.gz
```

Only repository administrators should set or rotate this secret. If Actions secret access is not granted to the GitHub connection, a repository administrator must set it through a trusted environment; do not send the key or GitHub credentials in chat. Accidentally switching keys will break in-place updates, which is why the release script pins the existing certificate.

## Normal release

1. Review and test the Android app on real devices and with live providers. This prototype has not yet had that verification. Review public release notes and legal/provider terms.
2. Increase `versionName` in `version.properties`. The Android build computes `major × 1,000,000 + minor × 1,000 + patch`; major is at most 2099 and minor/patch at most 999. Every published release must increase this number. For example `0.3.0` is version code `3000`, and `0.3.1` is `3001`.
3. Commit and push the change to the reviewed release branch. The workflow must exist on the default branch for GitHub's manual workflow-dispatch UI to offer it; merge the release workflow through the normal review process first.
4. In GitHub Actions, run **Publish Android release** on the reviewed commit. Leave **pre-release** enabled for unverified builds. It checks existing release versions, runs the native unit test and Gradle release build, signs with the protected secret, verifies the pinned certificate, and creates the tag and GitHub Release with its APK. The workflow requires `contents: write` and permission to use Actions secrets. Do not run a workflow from an untrusted branch while exposing a signing secret.
5. Confirm the release asset, tag, application ID, version and certificate; install over the previous version on a real device to verify update behavior before calling it stable.

For manual signing after `cd android && ./gradlew testDebugUnitTest assembleRelease`, run `android/scripts/sign-release.sh /private/path/temp-key.pk8 /private/path/temp-cert.der /private/output` from the repository root with Android SDK build-tools 35.0.0 installed. It checks the APK package/version and certificate, aligns, signs and verifies the output. Do not commit the generated APK or private files.
