#!/bin/bash
# Linux 侧目标应用启动样例（fault-agent 自动解析落库并同步挂载 sandbox 模块）
# 用法：./start-target.sh [app.jar 路径] [额外 JVM 参数]
APP=${1:-/home/lys2/test-app.jar}
AGENT=${AGENT:-/home/lys2/fault-agent-1.0.0.jar}
shift 2>/dev/null

exec java \
  -javaagent:"$AGENT"=lib.whitelist=fault-test-lib \
  "$@" \
  -jar "$APP"
