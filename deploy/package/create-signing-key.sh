#!/usr/bin/env bash
set -euo pipefail

KEY_DIR="$HOME/.droidassist-signing"
KEYSTORE="$KEY_DIR/droidassist-release.jks"
KEY_ALIAS="droidassist"
KEYCHAIN_SERVICE="com.stonebirds.droidassist.release"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "This helper stores the password in macOS Keychain and must run on macOS." >&2
  exit 1
fi
if [[ -e "$KEYSTORE" ]]; then
  echo "Signing key already exists: $KEYSTORE"
  exit 0
fi

KEYTOOL="${JAVA_HOME:-}/bin/keytool"
if [[ ! -x "$KEYTOOL" ]]; then
  KEYTOOL="/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool"
fi
[[ -x "$KEYTOOL" ]] || { echo "keytool not found; install Android Studio or JDK 21." >&2; exit 1; }

mkdir -p "$KEY_DIR"
chmod 700 "$KEY_DIR"
password="$(openssl rand -base64 36 | tr -d '\n')"

"$KEYTOOL" -genkeypair -v \
  -keystore "$KEYSTORE" \
  -storepass "$password" \
  -keypass "$password" \
  -alias "$KEY_ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -dname "CN=stonebirds, OU=DroidAssist, O=stonebirds, C=CN"

chmod 600 "$KEYSTORE"
security add-generic-password -U \
  -a "$USER" \
  -s "$KEYCHAIN_SERVICE" \
  -w "$password" >/dev/null
unset password

echo "Release signing key created: $KEYSTORE"
echo "Its password is stored in macOS Keychain under: $KEYCHAIN_SERVICE"
echo "Back up the keystore and Keychain password before distributing the APK."
