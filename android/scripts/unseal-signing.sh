#!/usr/bin/env bash
# Decrypt the repository's encrypted signing backup into a private temporary directory.
# FT_MUSIC_KEY_PASSPHRASE must come from an environment secret, never from command-line arguments.
set -euo pipefail
if [[ $# -ne 1 ]]; then echo "Usage: $0 <private-output-directory>" >&2; exit 2; fi
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
ENCRYPTED="$ROOT/.github/ft-music-signing-bundle.tar.gz.gpg"
PASSPHRASE=${FT_MUSIC_KEY_PASSPHRASE:-}
[[ ${#PASSPHRASE} -ge 40 && ${#PASSPHRASE} -le 512 && "$PASSPHRASE" != *$'\n'* ]] || { echo 'Missing or invalid signing passphrase.' >&2; exit 1; }
[[ -f "$ENCRYPTED" ]] || { echo 'Encrypted signing backup is missing.' >&2; exit 1; }
umask 077
DIR=$1
mkdir -p "$DIR"
chmod 700 "$DIR"
gpg --quiet --batch --yes --pinentry-mode loopback --passphrase-fd 3 \
  --output "$DIR/signing-bundle.tar.gz" --decrypt "$ENCRYPTED" 3<<<"$PASSPHRASE"
tar -tzf "$DIR/signing-bundle.tar.gz" | LC_ALL=C sort > "$DIR/members.txt"
printf 'temp-cert.der\ntemp-key.pk8\n' > "$DIR/expected.txt"
cmp "$DIR/expected.txt" "$DIR/members.txt"
tar -xzf "$DIR/signing-bundle.tar.gz" --no-same-owner --no-same-permissions -C "$DIR"
ACTUAL=$(openssl x509 -inform DER -in "$DIR/temp-cert.der" -noout -fingerprint -sha256 | sed 's/^.*=//;s/://g' | tr '[:upper:]' '[:lower:]')
EXPECTED=$(tr -d '[:space:]' < "$ROOT/android/signing-cert-sha256.txt")
[[ "$ACTUAL" == "$EXPECTED" ]] || { echo 'Decrypted certificate does not match the FT Music signing identity.' >&2; exit 1; }
rm -f "$DIR/signing-bundle.tar.gz" "$DIR/members.txt" "$DIR/expected.txt"
