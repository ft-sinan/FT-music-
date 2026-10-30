#!/usr/bin/env bash
# Sign an already-built Gradle release with the established FT Music key.
# The key must remain private and is never copied into the output APK.
set -euo pipefail
if [[ $# -ne 3 ]]; then
  echo "Usage: $0 <private-key.pk8> <certificate.der> <output-directory>" >&2
  exit 2
fi
KEY=$1
CERT=$2
OUTPUT=$3
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
TOOLS="${SDK}/build-tools/35.0.0"
UNSIGNED="$ROOT/android/app/build/outputs/apk/release/app-release-unsigned.apk"
[[ -n "$SDK" && -x "$TOOLS/zipalign" && -x "$TOOLS/apksigner" && -x "$TOOLS/aapt" ]] || { echo "Android build-tools 35.0.0 is required." >&2; exit 1; }
[[ -f "$UNSIGNED" && -f "$KEY" && -f "$CERT" ]] || { echo "Unsigned APK, private key or certificate is missing." >&2; exit 1; }
VERSION=$(python3 "$ROOT/android/scripts/release-version.py")
CODE=$(python3 "$ROOT/android/scripts/release-version.py" --code-only)
EXPECTED=$(tr -d '[:space:]' < "$ROOT/android/signing-cert-sha256.txt")
ACTUAL=$(openssl x509 -inform DER -in "$CERT" -noout -fingerprint -sha256 | sed 's/^.*=//;s/://g' | tr '[:upper:]' '[:lower:]')
[[ "$ACTUAL" == "$EXPECTED" ]] || { echo "Signing certificate does not match the installed FT Music app. Refusing to publish an incompatible update." >&2; exit 1; }
BADGING=$("$TOOLS/aapt" dump badging "$UNSIGNED")
[[ "$BADGING" == *"name='com.ftmusic.app'"* && "$BADGING" == *"versionCode='$CODE'"* && "$BADGING" == *"versionName='$VERSION'"* ]] || { echo "APK identity/version does not match version.properties." >&2; exit 1; }
mkdir -p "$OUTPUT"
TEMP=$(mktemp -d)
trap 'rm -rf "$TEMP"' EXIT
"$TOOLS/zipalign" -f -p 4 "$UNSIGNED" "$TEMP/aligned.apk"
APK="$OUTPUT/FT-Music-v${VERSION}.apk"
"$TOOLS/apksigner" sign --key "$KEY" --cert "$CERT" --out "$APK" "$TEMP/aligned.apk"
"$TOOLS/apksigner" verify --verbose --print-certs "$APK"
"$TOOLS/zipalign" -c -p 4 "$APK"
echo "Signed release: $APK"
