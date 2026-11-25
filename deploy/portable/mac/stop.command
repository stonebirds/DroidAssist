#!/bin/bash
set -u

DATA_DIR="$HOME/Library/Application Support/DroidAssist"
RUNTIME_DIR="$DATA_DIR/runtime"

stop_from_pid_file() {
  local label="$1"
  local pid_file="$2"
  if [[ -f "$pid_file" ]]; then
    local pid
    pid="$(cat "$pid_file" 2>/dev/null || true)"
    if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
      for _ in $(seq 1 20); do
        kill -0 "$pid" 2>/dev/null || break
        sleep 0.25
      done
      kill -9 "$pid" 2>/dev/null || true
      echo "$label 已停止。"
    fi
    rm -f "$pid_file"
  fi
}

stop_from_pid_file "公网隧道" "$RUNTIME_DIR/tunnel.pid"
stop_from_pid_file "本地服务" "$RUNTIME_DIR/server.pid"
rm -f "$RUNTIME_DIR/public-url"

echo "DroidAssist 已停止，数据库仍保存在：$DATA_DIR/data"
echo
read -r -p "按回车键关闭窗口..." _
