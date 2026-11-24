#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
RUNTIME_DIR="$ROOT_DIR/.runtime"
mkdir -p "$RUNTIME_DIR"

if curl -fsS http://127.0.0.1:8080/health >/dev/null 2>&1; then
  echo "DroidAssist server is already running."
else
  cd "$ROOT_DIR"
  ./gradlew :server:installDist >/dev/null
  launchctl remove com.stonebirds.droidassist.server 2>/dev/null || true
  : >"$RUNTIME_DIR/server.log"
  launchctl submit -l com.stonebirds.droidassist.server -- "$ROOT_DIR/deploy/mac/run-server.sh"
fi

for _ in {1..60}; do
  if curl -fsS http://127.0.0.1:8080/health >/dev/null; then break; fi
  sleep 1
done
curl -fsS http://127.0.0.1:8080/health >/dev/null || { echo "Server failed to start; see $RUNTIME_DIR/server.log"; exit 1; }

launchctl remove com.stonebirds.droidassist.tunnel 2>/dev/null || true
: >"$RUNTIME_DIR/cloudflared.log"
launchctl submit -l com.stonebirds.droidassist.tunnel -- "$ROOT_DIR/deploy/mac/run-tunnel.sh"

for _ in {1..60}; do
  PUBLIC_URL="$(sed -nE 's/.*(https:\/\/[a-z0-9-]+\.trycloudflare\.com).*/\1/p' "$RUNTIME_DIR/cloudflared.log" | tail -1)"
  [[ -n "$PUBLIC_URL" ]] && break
  sleep 1
done

if [[ -z "${PUBLIC_URL:-}" ]]; then
  echo "Tunnel failed to publish; see $RUNTIME_DIR/cloudflared.log"
  exit 1
fi
echo "$PUBLIC_URL" >"$RUNTIME_DIR/public-url"
echo "DroidAssist is ready: $PUBLIC_URL"
