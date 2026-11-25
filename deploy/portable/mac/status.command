#!/bin/bash
set -u

DATA_DIR="$HOME/Library/Application Support/DroidAssist"
RUNTIME_DIR="$DATA_DIR/runtime"

if curl -fsS http://127.0.0.1:8080/health >/dev/null 2>&1; then
  echo "本地服务：运行正常"
  if [[ -s "$RUNTIME_DIR/public-url" ]]; then
    PUBLIC_URL="$(cat "$RUNTIME_DIR/public-url")"
    echo "公网地址：$PUBLIC_URL"
    printf '%s' "$PUBLIC_URL" | pbcopy 2>/dev/null || true
    [[ -f "$RUNTIME_DIR/setup.html" ]] && open "$RUNTIME_DIR/setup.html"
  else
    echo "公网地址：尚未生成"
  fi
  echo "日志目录：$RUNTIME_DIR"
else
  echo "DroidAssist 当前没有运行，请双击启动脚本。"
fi

echo
read -r -p "按回车键关闭窗口..." _
