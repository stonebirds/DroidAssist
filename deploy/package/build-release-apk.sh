#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
DIST_DIR="$ROOT_DIR/dist"
VERSION="$(awk -F'"' '/versionName = / { print $2; exit }' "$ROOT_DIR/app/build.gradle.kts")"
KEYCHAIN_SERVICE="com.stonebirds.droidassist.release"

export DROIDASSIST_KEYSTORE_PATH="${DROIDASSIST_KEYSTORE_PATH:-$HOME/.droidassist-signing/droidassist-release.jks}"
export DROIDASSIST_KEY_ALIAS="${DROIDASSIST_KEY_ALIAS:-droidassist}"

if [[ -z "${DROIDASSIST_KEYSTORE_PASSWORD:-}" ]] && command -v security >/dev/null 2>&1; then
  DROIDASSIST_KEYSTORE_PASSWORD="$(security find-generic-password -w -a "$USER" -s "$KEYCHAIN_SERVICE" 2>/dev/null || true)"
  export DROIDASSIST_KEYSTORE_PASSWORD
fi
export DROIDASSIST_KEY_PASSWORD="${DROIDASSIST_KEY_PASSWORD:-${DROIDASSIST_KEYSTORE_PASSWORD:-}}"

[[ -f "$DROIDASSIST_KEYSTORE_PATH" ]] || { echo "Release keystore not found. Run deploy/package/create-signing-key.sh." >&2; exit 1; }
[[ -n "${DROIDASSIST_KEYSTORE_PASSWORD:-}" ]] || { echo "Release keystore password is unavailable." >&2; exit 1; }

mkdir -p "$DIST_DIR"
(cd "$ROOT_DIR" && ./gradlew :app:assembleRelease)

source_apk="$ROOT_DIR/app/build/outputs/apk/release/app-release.apk"
output_apk="$DIST_DIR/DroidAssist-$VERSION-release.apk"
[[ -f "$source_apk" ]] || { echo "Release APK was not generated." >&2; exit 1; }
cp "$source_apk" "$output_apk"

apksigner_path=""
if [[ -n "${ANDROID_HOME:-}" ]]; then
  apksigner_path="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner -print 2>/dev/null | sort -V | tail -1)"
fi
if [[ -n "$apksigner_path" ]]; then
  "$apksigner_path" verify --verbose --print-certs "$output_apk"
else
  echo "apksigner not found; Gradle completed signing but independent verification was skipped." >&2
fi

(cd "$DIST_DIR" && shasum -a 256 "$(basename "$output_apk")" >APK-SHA256.txt)
echo "Release APK is ready: $output_apk"
