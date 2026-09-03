#!/usr/bin/env bash
# add-agent-to-service.sh — 修改 systemd service 文件以接入故障注入 agent
#
# 用法: ./add-agent-to-service.sh <agentJar绝对路径> <service文件路径> [轮次tag]
#
# 做的事：
#   1) 删除重启次数限制行（StartLimitIntervalSec / StartLimitInterval / StartLimitBurst）
#   2) ExecStart 的 java 命令插入 -javaagent=<agentJar>（已有则替换其值，幂等）
#   3) 提供第 3 个参数时，写入/替换 -Dfault.tag=<tag>（幂等）；不提供则仅输出提醒
#
# 修改前自动备份为 <service>.bak.<时间戳>；改完需 systemctl daemon-reload + restart 生效。
set -euo pipefail

usage() {
    echo "用法: $0 <agentJar绝对路径> <service文件路径> [轮次tag]"
    echo "示例: $0 /app/agent/fault-agent-1.0.0.jar /etc/systemd/system/focus@.service round-001"
    exit 1
}

[ $# -ge 2 ] && [ $# -le 3 ] || usage

AGENT_JAR="$1"
SERVICE_FILE="$2"
TAG="${3:-}"

[ -f "$AGENT_JAR" ] || { echo "错误: agent jar 不存在: $AGENT_JAR"; exit 1; }
[ -f "$SERVICE_FILE" ] || { echo "错误: service 文件不存在: $SERVICE_FILE"; exit 1; }
[ -w "$SERVICE_FILE" ] || { echo "错误: 无写权限（需要 root 或文件属主）: $SERVICE_FILE"; exit 1; }

AGENT_JAR=$(readlink -f "$AGENT_JAR")
SERVICE_FILE=$(readlink -f "$SERVICE_FILE")

BACKUP="${SERVICE_FILE}.bak.$(date +%Y%m%d%H%M%S)"
cp -p "$SERVICE_FILE" "$BACKUP"
echo "已备份: $BACKUP"

# sed 替换文本特殊字符转义（& 与分隔符 |）
AGENT_ESC=$(printf '%s' "$AGENT_JAR" | sed -e 's/[&|]/\\&/g')
TAG_ESC=$(printf '%s' "$TAG" | sed -e 's/[&|]/\\&/g')

# --- 1) 删除重启次数限制（只删未注释的配置行）---
if grep -Eq '^[[:space:]]*StartLimit(IntervalSec|Interval|Burst)=' "$SERVICE_FILE"; then
    sed -i -E '/^[[:space:]]*StartLimit(IntervalSec|Interval|Burst)=[^=]*$/d' "$SERVICE_FILE"
    echo "已删除重启次数限制: StartLimitIntervalSec / StartLimitBurst"
else
    echo "未发现重启次数限制行，跳过"
fi

# --- 2) ExecStart 插入/替换 -javaagent ---
if grep -Eq '^[[:space:]]*ExecStart=.*-javaagent=' "$SERVICE_FILE"; then
    sed -i -E "s|(^[[:space:]]*ExecStart=.*)-javaagent=[^[:space:]]+|\1-javaagent=${AGENT_ESC}|" "$SERVICE_FILE"
    echo "已替换 ExecStart 中原有的 -javaagent"
elif grep -Eq '^[[:space:]]*ExecStart=' "$SERVICE_FILE"; then
    sed -i -E "s|(^[[:space:]]*ExecStart=[^[:space:]]*java)([[:space:]])|\1 -javaagent=${AGENT_ESC}\2|" "$SERVICE_FILE"
    echo "已在 ExecStart 的 java 命令后插入 -javaagent"
else
    echo "错误: 未找到 ExecStart 行: $SERVICE_FILE"
    exit 1
fi

# --- 3) ExecStart 写入/替换 -Dfault.tag（提供了第 3 参数时）---
if [ -n "$TAG" ]; then
    if grep -Eq '^[[:space:]]*ExecStart=.*-Dfault\.tag=' "$SERVICE_FILE"; then
        sed -i -E "s|(^[[:space:]]*ExecStart=.*)-Dfault\.tag=[^[:space:]]+|\1-Dfault.tag=${TAG_ESC}|" "$SERVICE_FILE"
        echo "已替换 ExecStart 中原有的 -Dfault.tag"
    else
        sed -i -E "s|(^[[:space:]]*ExecStart=.*-javaagent=[^[:space:]]+)|\1 -Dfault.tag=${TAG_ESC}|" "$SERVICE_FILE"
        echo "已在 -javaagent 后追加 -Dfault.tag=${TAG}"
    fi
fi

# --- 校验写入结果 ---
if ! grep -Fq -- "-javaagent=${AGENT_JAR}" "$SERVICE_FILE"; then
    echo "错误: -javaagent 修改未生效，已保留备份 $BACKUP"
    exit 1
fi
if [ -n "$TAG" ] && ! grep -Fq -- "-Dfault.tag=${TAG}" "$SERVICE_FILE"; then
    echo "错误: -Dfault.tag 修改未生效，已保留备份 $BACKUP"
    exit 1
fi

echo "修改完成: $SERVICE_FILE"
grep -nE '^(StartLimit|ExecStart=)' "$SERVICE_FILE" || true
echo
echo "后续步骤（需 root）："
echo "  1. systemctl daemon-reload"
echo "  2. systemctl restart <服务名>      # 如 focus@app1"
if [ -z "$TAG" ]; then
    echo
    echo "提醒: 未提供轮次 tag —— agent 硬保护策略要求 JVM 参数带 -Dfault.tag=<轮次标识>，"
    echo "      否则故障模块挂载完成后会直接 kill 进程。重新执行本脚本并传入第 3 个参数即可写入。"
fi
