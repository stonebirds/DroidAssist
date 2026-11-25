#!/bin/bash
set -u

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
DATA_DIR="$HOME/Library/Application Support/DroidAssist"
RUNTIME_DIR="$DATA_DIR/runtime"
SERVER_PID_FILE="$RUNTIME_DIR/server.pid"
TUNNEL_PID_FILE="$RUNTIME_DIR/tunnel.pid"
PUBLIC_URL_FILE="$RUNTIME_DIR/public-url"
SERVER_LOG="$RUNTIME_DIR/server.log"
TUNNEL_LOG="$RUNTIME_DIR/cloudflared.log"

pause_and_exit() {
  echo
  read -r -p "按回车键关闭窗口..." _
  exit "${1:-0}"
}

is_running() {
  local pid_file="$1"
  [[ -f "$pid_file" ]] || return 1
  local pid
  pid="$(cat "$pid_file" 2>/dev/null || true)"
  [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null
}

for required in "$ROOT_DIR/runtime/bin/java" "$ROOT_DIR/tools/cloudflared" "$ROOT_DIR/server/lib"; do
  if [[ ! -e "$required" ]]; then
    echo "缺少运行文件：$required"
    echo "请重新下载并完整解压 DroidAssist 服务包。"
    pause_and_exit 1
  fi
done

mkdir -p "$RUNTIME_DIR" "$DATA_DIR/data"
xattr -dr com.apple.quarantine "$ROOT_DIR" 2>/dev/null || true
chmod +x "$ROOT_DIR/runtime/bin/java" "$ROOT_DIR/tools/cloudflared" 2>/dev/null || true

if is_running "$SERVER_PID_FILE" && curl -fsS http://127.0.0.1:8080/health >/dev/null 2>&1; then
  echo "DroidAssist 本地服务已经运行。"
else
  rm -f "$SERVER_PID_FILE"
  : >"$SERVER_LOG"
  export DROIDASSIST_HOST="127.0.0.1"
  export DROIDASSIST_PORT="8080"
  export DROIDASSIST_DB="$DATA_DIR/data/droidassist.db"
  nohup "$ROOT_DIR/runtime/bin/java" \
    -cp "$ROOT_DIR/server/lib/*" \
    com.leefly.droidassist.server.ApplicationKt \
    >>"$SERVER_LOG" 2>&1 </dev/null &
  echo $! >"$SERVER_PID_FILE"
fi

echo "正在检查本地服务..."
server_ready=false
for _ in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:8080/health >/dev/null 2>&1; then
    server_ready=true
    break
  fi
  sleep 1
done
if [[ "$server_ready" != true ]]; then
  echo "本地服务启动失败，日志位置：$SERVER_LOG"
  tail -n 30 "$SERVER_LOG" 2>/dev/null || true
  pause_and_exit 1
fi

if is_running "$TUNNEL_PID_FILE" && [[ -s "$PUBLIC_URL_FILE" ]]; then
  PUBLIC_URL="$(cat "$PUBLIC_URL_FILE")"
  echo "公网隧道已经运行。"
else
  rm -f "$TUNNEL_PID_FILE" "$PUBLIC_URL_FILE"
  : >"$TUNNEL_LOG"
  nohup "$ROOT_DIR/tools/cloudflared" tunnel \
    --url http://127.0.0.1:8080 \
    --no-autoupdate \
    >>"$TUNNEL_LOG" 2>&1 </dev/null &
  echo $! >"$TUNNEL_PID_FILE"

  echo "正在创建公网地址，通常需要几秒钟..."
  PUBLIC_URL=""
  for _ in $(seq 1 90); do
    PUBLIC_URL="$(sed -nE 's/.*(https:\/\/[a-z0-9-]+\.trycloudflare\.com).*/\1/p' "$TUNNEL_LOG" | tail -1)"
    [[ -n "$PUBLIC_URL" ]] && break
    sleep 1
  done
  if [[ -z "$PUBLIC_URL" ]]; then
    echo "公网隧道启动失败，日志位置：$TUNNEL_LOG"
    tail -n 30 "$TUNNEL_LOG" 2>/dev/null || true
    pause_and_exit 1
  fi
  echo "$PUBLIC_URL" >"$PUBLIC_URL_FILE"
fi

QR_URL="https://api.qrserver.com/v1/create-qr-code/?size=260x260&data=$PUBLIC_URL"
sed \
  -e "s|__PUBLIC_URL__|$PUBLIC_URL|g" \
  -e "s|__QR_URL__|$QR_URL|g" \
  "$ROOT_DIR/web/setup-template.html" >"$RUNTIME_DIR/setup.html"

printf '%s' "$PUBLIC_URL" | pbcopy 2>/dev/null || true
open "$RUNTIME_DIR/setup.html"

echo
echo "DroidAssist 服务已启动：$PUBLIC_URL"
echo "地址已复制到 Mac 剪贴板，并已打开使用页面。"
echo "日志目录：$RUNTIME_DIR"
pause_and_exit 0
