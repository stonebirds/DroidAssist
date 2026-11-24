#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
RUNTIME_DIR="$ROOT_DIR/.runtime"
if curl -fsS http://127.0.0.1:8080/health; then
  echo
  [[ -f "$RUNTIME_DIR/public-url" ]] && echo "Public URL: $(cat "$RUNTIME_DIR/public-url")"
else
  echo "DroidAssist server is not reachable."
  exit 1
fi
