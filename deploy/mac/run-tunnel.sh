#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
export PATH="/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin"
cd "$ROOT_DIR"
exec cloudflared tunnel --url http://127.0.0.1:8080 --no-autoupdate >>"$ROOT_DIR/.runtime/cloudflared.log" 2>&1
