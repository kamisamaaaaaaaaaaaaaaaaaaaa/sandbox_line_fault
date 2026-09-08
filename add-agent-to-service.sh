#!/usr/bin/env bash
# add-agent-to-service.sh — 修改 systemd service 文件以接入故障注入 agent
#
# 用法: ./add-agent-to-service.sh <agentJar绝对路径> <service文件路径> [轮次tag]
#
# 做的事：
#   1) 删除重启次数限制行（StartLimitIntervalSec / StartLimitInterval / StartLimitBurst）
#   2) ExecStart 的 java 命令插入 -javaagent:<agentJar>（已有则整参替换——包括原有的
#      agentArgs，幂等；旧式的 -javaagent= 等号写法是 JVM 非法参数，会被一并纠正为冒号）
#   3) 模块副本清理配置（幂等，防 /tmp 被副本写满——见 README 6.4）：
#      - ExecStartPre=/bin/mkdir -p tmp      保证 java.io.tmpdir 存在（相对 WorkingDirectory）
#      - ExecStart 插入 -Djava.io.tmpdir=tmp 模块副本落进应用独占目录，不写共享 /tmp
#      - ExecStopPost 删除 tmp 下的模块副本  systemd 重启的停止阶段会连坐杀死 reaper
#        （KillMode=mixed），清理必须在停止阶段由 ExecStopPost 完成
#      前提：service 有 WorkingDirectory=（三行的 tmp 相对它解析，路径零硬编码）；
#      应用已自带 -Djava.io.tmpdir 时不覆盖，输出提醒人工核对 ExecStopPost 目录
#   4) 提供第 3 个参数时，写入/替换 -Dfault.tag=<tag>（幂等）；不提供则仅输出提醒
#
# 修改前自动备份为 <service>.bak.<时间戳>；改完需 systemctl daemon-reload + restart 生效。
set -euo pipefail

usage() {
    echo "用法: $0 <agentJar绝对路径> <service文件路径> [轮次tag]"
    echo "示例: $0 /app/deploy/fault-agent-1.0.0.jar /etc/systemd/system/focus@.service round-001"
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
# JVM 语法为 -javaagent:<jarpath>[=<options>]，jar 路径前必须用冒号；
# [:] 同时匹配旧的等号错误写法（-javaagent=），替换后统一纠正为冒号
if grep -Eq '^[[:space:]]*ExecStart=.*-javaagent[:=]' "$SERVICE_FILE"; then
    sed -i -E "s|(^[[:space:]]*ExecStart=.*)-javaagent[:=][^[:space:]]+|\1-javaagent:${AGENT_ESC}|" "$SERVICE_FILE"
    echo "已替换 ExecStart 中原有的 -javaagent"
elif grep -Eq '^[[:space:]]*ExecStart=' "$SERVICE_FILE"; then
    sed -i -E "s|(^[[:space:]]*ExecStart=[^[:space:]]*java)([[:space:]])|\1 -javaagent:${AGENT_ESC}\2|" "$SERVICE_FILE"
    echo "已在 ExecStart 的 java 命令后插入 -javaagent"
else
    echo "错误: 未找到 ExecStart 行: $SERVICE_FILE"
    exit 1
fi

# --- 3) 模块副本清理配置（防 /tmp 写满，见 README 6.4）---
# 三行均使用相对 WorkingDirectory 的 tmp，路径零硬编码（换应用/换部署根目录无需改动）
if ! grep -Eq '^[[:space:]]*WorkingDirectory=' "$SERVICE_FILE"; then
    echo "警告: service 无 WorkingDirectory=，跳过副本清理配置（tmp 相对路径无解析基准）"
else
    # 3a) 启动前创建 tmp（幂等）
    if grep -Eq '^[[:space:]]*ExecStartPre=/bin/mkdir -p tmp$' "$SERVICE_FILE"; then
        echo "ExecStartPre（创建 tmp 目录）已存在，跳过"
    else
        sed -i "/^[[:space:]]*ExecStart=/i ExecStartPre=/bin/mkdir -p tmp" "$SERVICE_FILE"
        echo "已添加 ExecStartPre（创建 tmp 目录）"
    fi

    # 3b) ExecStart 插入 -Djava.io.tmpdir=tmp（幂等：应用已有该参数时不覆盖）
    if grep -Eq -- '-Djava\.io\.tmpdir=' "$SERVICE_FILE"; then
        echo "警告: ExecStart 已含 -Djava.io.tmpdir=，未覆盖——请人工确认 ExecStopPost 清扫目录与之匹配"
    else
        sed -i -E "s|(^[[:space:]]*ExecStart=.*)(-jar )|\1-Djava.io.tmpdir=tmp \2|" "$SERVICE_FILE"
        echo "已插入 -Djava.io.tmpdir=tmp（模块副本落进应用独占目录）"
    fi

    # 3c) ExecStopPost 清扫（幂等）——必须插入 [Service] 节内（ExecStart 之前），
    #     追加到文件尾会落入 [Install] 节被 systemd 忽略
    if grep -q 'sandbox_module_jar_' "$SERVICE_FILE"; then
        echo "ExecStopPost 副本清扫已存在，跳过"
    else
        sed -i "/^[[:space:]]*ExecStart=/i ExecStopPost=/bin/sh -c 'rm -f tmp/sandbox_module_jar_*.jar'" "$SERVICE_FILE"
        echo "已添加 ExecStopPost（停止阶段清扫模块副本）"
    fi
fi

# --- 4) ExecStart 写入/替换 -Dfault.tag（提供了第 3 参数时）---
if [ -n "$TAG" ]; then
    if grep -Eq '^[[:space:]]*ExecStart=.*-Dfault\.tag=' "$SERVICE_FILE"; then
        sed -i -E "s|(^[[:space:]]*ExecStart=.*)-Dfault\.tag=[^[:space:]]+|\1-Dfault.tag=${TAG_ESC}|" "$SERVICE_FILE"
        echo "已替换 ExecStart 中原有的 -Dfault.tag"
    else
        sed -i -E "s|(^[[:space:]]*ExecStart=.*-javaagent:[^[:space:]]+)|\1 -Dfault.tag=${TAG_ESC}|" "$SERVICE_FILE"
        echo "已在 -javaagent 后追加 -Dfault.tag=${TAG}"
    fi
fi

# --- 校验写入结果 ---
if ! grep -Fq -- "-javaagent:${AGENT_JAR}" "$SERVICE_FILE"; then
    echo "错误: -javaagent 修改未生效，已保留备份 $BACKUP"
    exit 1
fi
if [ -n "$TAG" ] && ! grep -Fq -- "-Dfault.tag=${TAG}" "$SERVICE_FILE"; then
    echo "错误: -Dfault.tag 修改未生效，已保留备份 $BACKUP"
    exit 1
fi

echo "修改完成: $SERVICE_FILE"
grep -nE '^(StartLimit|ExecStart|ExecStartPre|ExecStopPost)=' "$SERVICE_FILE" || true
echo
echo "后续步骤（需 root）："
echo "  1. systemctl daemon-reload"
echo "  2. systemctl restart <服务名>      # 如 focus@app1"
if [ -z "$TAG" ]; then
    echo
    echo "提醒: 未提供轮次 tag —— agent 硬保护策略要求 JVM 参数带 -Dfault.tag=<轮次标识>，"
    echo "      否则故障模块挂载完成后会直接 kill 进程。重新执行本脚本并传入第 3 个参数即可写入。"
fi
