#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
RUNTIME_DIR="$ROOT_DIR/.runtime"
launchctl remove com.stonebirds.droidassist.tunnel 2>/dev/null || true
launchctl remove com.stonebirds.droidassist.server 2>/dev/null || true
rm -f "$RUNTIME_DIR/cloudflared.pid" "$RUNTIME_DIR/server.pid" "$RUNTIME_DIR/public-url"
echo "DroidAssist stopped."
