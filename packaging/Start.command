#!/bin/sh
# Finder opens this file in Terminal; the application itself remains a CLI.
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
WORKSPACE="$HOME/JavaCodingAgentWorkspace"
mkdir -p "$WORKSPACE"
cd "$WORKSPACE"
printf 'Java AI Coding Agent %s\n' "$(cat "$APP_HOME/VERSION")"
printf '默认工作目录：%s\n' "$WORKSPACE"
status=0
"$APP_HOME/bin/agent" || status=$?
printf '\n程序已退出（状态 %s），按回车关闭此窗口。' "$status"
IFS= read -r answer || :
exit "$status"
