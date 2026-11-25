#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
CACHE_DIR="$ROOT_DIR/.runtime/package-cache"
BUILD_DIR="$ROOT_DIR/.runtime/portable-build"
DIST_DIR="$ROOT_DIR/dist"
VERSION="$(awk -F'"' '/versionName = / { print $2; exit }' "$ROOT_DIR/app/build.gradle.kts")"
CLOUDFLARED_VERSION="2026.11.2"
QRCODE_VERSION="1.4.4"

JRE_MAC_ARM64_URL="https://cdn.azul.com/zulu/bin/zulu21.54.201-ca-jre21.0.13.1-macosx_aarch64.tar.gz"
JRE_MAC_ARM64_SHA="2fe02aa720d3b3e4ed5f87f013a5ebd9e5a26159380192d01d7a908591e0b04d"
JRE_MAC_X64_URL="https://cdn.azul.com/zulu/bin/zulu21.54.201-ca-jre21.0.13.1-macosx_x64.tar.gz"
JRE_MAC_X64_SHA="e6b4c7cd15ace6ae2a1e6f724c41e444b1f7ce4831f5b0cf10f26727556d8ee5"
JRE_WINDOWS_X64_URL="https://cdn.azul.com/zulu/bin/zulu21.54.201-ca-jre21.0.13.1-win_x64.zip"
JRE_WINDOWS_X64_SHA="888995f738298e22c34c3e4bf72230573360e4b63a19455ec76c0bf59235a972"

CLOUDFLARED_MAC_ARM64_URL="https://github.com/cloudflare/cloudflared/releases/download/$CLOUDFLARED_VERSION/cloudflared-darwin-arm64.tgz"
CLOUDFLARED_MAC_ARM64_SHA="f6d58eeec91ad1ec5fd1f7e4a587303772929b2625be5d24068033851c36ff55"
CLOUDFLARED_MAC_X64_URL="https://github.com/cloudflare/cloudflared/releases/download/$CLOUDFLARED_VERSION/cloudflared-darwin-amd64.tgz"
CLOUDFLARED_MAC_X64_SHA="6153d9ec629174882a3adbff2f7dc5a24b4f3bdd7ccc38eaa84fba26eb890311"
CLOUDFLARED_WINDOWS_X64_URL="https://github.com/cloudflare/cloudflared/releases/download/$CLOUDFLARED_VERSION/cloudflared-windows-amd64.exe"
CLOUDFLARED_WINDOWS_X64_SHA="555b46c85582461d189cac30d14ad05480d612613801be9f110b8ce08c5b2113"
QRCODE_JS_URL="https://cdn.jsdelivr.net/npm/qrcode-generator@$QRCODE_VERSION/qrcode.min.js"
QRCODE_JS_SHA="bb2365e4902f4f84852cf4025e6f6a60325a682aeafa43fb63b7fc8f098d1ef2"

mkdir -p "$CACHE_DIR" "$BUILD_DIR" "$DIST_DIR"

sha256() {
  shasum -a 256 "$1" | awk '{ print $1 }'
}

download() {
  local url="$1"
  local destination="$2"
  local expected="$3"
  if [[ -f "$destination" ]] && [[ "$(sha256 "$destination")" == "$expected" ]]; then
    echo "Using cached $(basename "$destination")"
    return
  fi
  rm -f "$destination"
  echo "Downloading $(basename "$destination")"
  curl -fL --retry 5 --retry-delay 2 --connect-timeout 20 "$url" -o "$destination"
  local actual
  actual="$(sha256 "$destination")"
  if [[ "$actual" != "$expected" ]]; then
    echo "Checksum mismatch for $destination" >&2
    echo "Expected: $expected" >&2
    echo "Actual:   $actual" >&2
    rm -f "$destination"
    exit 1
  fi
}

copy_common() {
  local package_dir="$1"
  mkdir -p "$package_dir/server" "$package_dir/runtime" "$package_dir/tools" "$package_dir/web"
  cp -R "$ROOT_DIR/server/build/install/server/." "$package_dir/server/"
  cp "$ROOT_DIR/deploy/portable/common/setup-template.html" "$package_dir/web/"
  cp "$CACHE_DIR/qrcode.min.js" "$package_dir/web/"
  cp "$ROOT_DIR/deploy/portable/common/THIRD_PARTY_NOTICES.txt" "$package_dir/"
  cp "$ROOT_DIR/LICENSE" "$package_dir/"
}

install_jre_tar() {
  local archive="$1"
  local package_dir="$2"
  local extract_dir="$BUILD_DIR/jre-extract"
  rm -rf "$extract_dir"
  mkdir -p "$extract_dir"
  tar -xzf "$archive" -C "$extract_dir"
  local java_binary
  java_binary="$(find "$extract_dir" -type f -path '*/bin/java' -print -quit)"
  [[ -n "$java_binary" ]] || { echo "Unable to locate Java in $archive" >&2; exit 1; }
  local java_root
  java_root="$(cd "$(dirname "$java_binary")/.." && pwd)"
  cp -R "$java_root/." "$package_dir/runtime/"
}

install_jre_zip() {
  local archive="$1"
  local package_dir="$2"
  local extract_dir="$BUILD_DIR/jre-extract"
  rm -rf "$extract_dir"
  mkdir -p "$extract_dir"
  unzip -q "$archive" -d "$extract_dir"
  local java_binary
  java_binary="$(find "$extract_dir" -type f -iname 'java.exe' -path '*/bin/*' -print -quit)"
  [[ -n "$java_binary" ]] || { echo "Unable to locate java.exe in $archive" >&2; exit 1; }
  local java_root
  java_root="$(cd "$(dirname "$java_binary")/.." && pwd)"
  cp -R "$java_root/." "$package_dir/runtime/"
}

archive_package() {
  local package_name="$1"
  local output="$DIST_DIR/$package_name.zip"
  rm -f "$output"
  (cd "$BUILD_DIR" && zip -qry "$output" "$package_name")
  echo "Created $output"
}

build_mac() {
  local architecture="$1"
  local jre_url jre_sha cloudflared_url cloudflared_sha
  if [[ "$architecture" == "arm64" ]]; then
    jre_url="$JRE_MAC_ARM64_URL"; jre_sha="$JRE_MAC_ARM64_SHA"
    cloudflared_url="$CLOUDFLARED_MAC_ARM64_URL"; cloudflared_sha="$CLOUDFLARED_MAC_ARM64_SHA"
  else
    jre_url="$JRE_MAC_X64_URL"; jre_sha="$JRE_MAC_X64_SHA"
    cloudflared_url="$CLOUDFLARED_MAC_X64_URL"; cloudflared_sha="$CLOUDFLARED_MAC_X64_SHA"
  fi
  local jre_archive="$CACHE_DIR/zulu-jre-macos-$architecture.tar.gz"
  local cloudflared_archive="$CACHE_DIR/cloudflared-macos-$architecture.tgz"
  download "$jre_url" "$jre_archive" "$jre_sha"
  download "$cloudflared_url" "$cloudflared_archive" "$cloudflared_sha"

  local package_name="DroidAssist-Server-macOS-$architecture-$VERSION"
  local package_dir="$BUILD_DIR/$package_name"
  rm -rf "$package_dir"
  copy_common "$package_dir"
  install_jre_tar "$jre_archive" "$package_dir"
  tar -xzf "$cloudflared_archive" -C "$package_dir/tools"
  mv "$package_dir/tools/cloudflared"* "$package_dir/tools/cloudflared" 2>/dev/null || true

  cp "$ROOT_DIR/deploy/portable/mac/start.command" "$package_dir/1-Start-DroidAssist.command"
  cp "$ROOT_DIR/deploy/portable/mac/status.command" "$package_dir/2-Status.command"
  cp "$ROOT_DIR/deploy/portable/mac/stop.command" "$package_dir/3-Stop-DroidAssist.command"
  cp "$ROOT_DIR/deploy/portable/mac/使用说明.txt" "$package_dir/README-ZH.txt"
  chmod +x "$package_dir/"*.command "$package_dir/runtime/bin/java" "$package_dir/tools/cloudflared"
  archive_package "$package_name"
}

build_windows() {
  local jre_archive="$CACHE_DIR/zulu-jre-windows-x64.zip"
  local cloudflared_binary="$CACHE_DIR/cloudflared-windows-x64.exe"
  download "$JRE_WINDOWS_X64_URL" "$jre_archive" "$JRE_WINDOWS_X64_SHA"
  download "$CLOUDFLARED_WINDOWS_X64_URL" "$cloudflared_binary" "$CLOUDFLARED_WINDOWS_X64_SHA"

  local package_name="DroidAssist-Server-Windows-x64-$VERSION"
  local package_dir="$BUILD_DIR/$package_name"
  rm -rf "$package_dir"
  copy_common "$package_dir"
  install_jre_zip "$jre_archive" "$package_dir"
  cp "$cloudflared_binary" "$package_dir/tools/cloudflared.exe"
  mkdir -p "$package_dir/scripts"
  cp "$ROOT_DIR/deploy/portable/windows/start.cmd" "$package_dir/1-Start-DroidAssist.cmd"
  cp "$ROOT_DIR/deploy/portable/windows/status.cmd" "$package_dir/2-Status.cmd"
  cp "$ROOT_DIR/deploy/portable/windows/stop.cmd" "$package_dir/3-Stop-DroidAssist.cmd"
  cp "$ROOT_DIR/deploy/portable/windows/scripts/"*.ps1 "$package_dir/scripts/"
  cp "$ROOT_DIR/deploy/portable/windows/使用说明.txt" "$package_dir/README-ZH.txt"
  archive_package "$package_name"
}

echo "Building server distribution"
(cd "$ROOT_DIR" && ./gradlew :server:installDist)
download "$QRCODE_JS_URL" "$CACHE_DIR/qrcode.min.js" "$QRCODE_JS_SHA"

if [[ $# -eq 0 ]]; then
  set -- macos-arm64 macos-x64 windows-x64
fi
for target in "$@"; do
  case "$target" in
    macos-arm64) build_mac arm64 ;;
    macos-x64) build_mac x64 ;;
    windows-x64) build_windows ;;
    *) echo "Unknown target: $target" >&2; exit 1 ;;
  esac
done

(cd "$DIST_DIR" && shasum -a 256 DroidAssist-Server-*-"$VERSION".zip >SHA256SUMS.txt)
echo "Portable packages are ready in $DIST_DIR"
