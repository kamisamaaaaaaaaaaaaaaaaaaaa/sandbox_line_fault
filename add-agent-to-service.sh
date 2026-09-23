#!/usr/bin/env bash
# add-agent-to-service.sh — 修改 systemd service 文件以接入故障注入 agent
#
# 用法: ./add-agent-to-service.sh <agentJar绝对路径> <service文件路径> <轮次tag> [重启间隔RestartSec] [-D参数...]
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
#   4) 写入/替换 -Dfault.tag=<tag>（幂等；**轮次 tag 必填**——agent 硬保护要求，缺失时模块
#      挂载完成后会直接 kill 进程）
#   5) 第 5 个参数起为追加/替换的 JVM 参数（可选，数量不限），形如 -Dkey=value 或 key=value
#      （不带 -D 前缀会自动补上）；ExecStart 已有同名 -Dkey= 时替换其值，否则插到 -jar 之前。
#      覆盖 Spring 属性的用法：-D 系统属性优先级高于 application.yaml（含 -Dspring.config.location
#      指定的外部文件）
#   6) 提供第 4 个参数时，替换 RestartSec=<值>（幂等；值限数字与时间单位后缀，如 5 / 5s / 500ms）；
#      不提供则不修改。故障注入每命中一行都 kill 进程，RestartSec 决定两次故障之间的间隔时间
#
# 修改前自动备份为 <service>.bak.<时间戳>；改完需 systemctl daemon-reload + restart 生效。
set -euo pipefail

usage() {
    echo "用法: $0 <agentJar绝对路径> <service文件路径> <轮次tag> [重启间隔RestartSec] [-D参数...]"
    echo "示例: $0 /app/deploy/fault-agent-1.0.0.jar /etc/systemd/system/focus@.service round-001"
    echo "      $0 /app/deploy/fault-agent-1.0.0.jar /etc/systemd/system/focus@.service round-001 5s"
    echo "      $0 /app/deploy/fault-agent-1.0.0.jar /etc/systemd/system/focus@.service round-001 5s -Dframework.zk.url=xxx -Dx.y=1"
    echo "说明: 轮次tag 必填（agent 硬保护要求，缺失时模块挂载完即 kill 进程）；重启间隔可省略"
    echo "      （省略时不修改 RestartSec）；第 5 个参数起为 JVM 参数（-Dkey=value，可多个），"
    echo "      ExecStart 已存在的同名参数会被替换"
    exit 1
}

[ $# -ge 3 ] || usage

AGENT_JAR="$1"
SERVICE_FILE="$2"
TAG="$3"
shift 3

# 第 4 个剩余参数：若形如 RestartSec 值（数字+可选单位、不以 -D 开头）则视为重启间隔，否则跳过
RESTART_SEC=""
if [ $# -gt 0 ]; then
    case "$1" in
        -D*) : ;;
        *)
            if printf '%s' "$1" | grep -Eq '^[0-9]+([a-z]+[0-9]*)*$'; then
                RESTART_SEC="$1"
                shift
            fi
            ;;
    esac
fi

# 剩余参数：JVM 参数（-Dkey=value 或 key=value，自动补 -D 前缀）。
# 白名单校验：key 限字母数字与 . _ -；值不允许空格与 shell 特殊字符（& | \ 引号 $ 反引号 分号
# 等会破坏 ExecStart 或 sed 替换）——含此类字符的值请直接手工编辑 service
JVM_ARGS=()
for spec in "$@"; do
    case "$spec" in
        -D*) ARG="$spec" ;;
        *)   ARG="-D$spec" ;;
    esac
    if ! printf '%s' "$ARG" | grep -Eq '^-D[A-Za-z0-9._-]+=[A-Za-z0-9._:/=,+@%^*?!~-]*$'; then
        echo "错误: 非法的 JVM 参数: $spec（应为 -Dkey=value；key 限字母数字._-，"
        echo "      值不含空格与特殊字符 & | \\ 引号 \$ \` 分号——含此类字符的值请手工编辑 service）"
        exit 1
    fi
    JVM_ARGS+=("$ARG")
done

[ -f "$AGENT_JAR" ] || { echo "错误: agent jar 不存在: $AGENT_JAR"; exit 1; }
[ -f "$SERVICE_FILE" ] || { echo "错误: service 文件不存在: $SERVICE_FILE"; exit 1; }
[ -w "$SERVICE_FILE" ] || { echo "错误: 无写权限（需要 root 或文件属主）: $SERVICE_FILE"; exit 1; }
# 轮次 tag 必填：agent 硬保护策略要求 JVM 参数带 -Dfault.tag=<轮次标识>，缺失时模块挂载完会 kill 进程
[ -n "$TAG" ] || { echo "错误: 轮次tag 必填（agent 硬保护要求，缺失时模块挂载完即 kill 进程）"; usage; }
# RestartSec 值格式校验：数字 + 可选时间单位后缀（systemd 支持的写法，如 5 / 5s / 500ms / 1min）；
# 不允许空格与特殊字符（该值会进入 sed 替换，同时避免写出非法 service）
if [ -n "$RESTART_SEC" ] && ! printf '%s' "$RESTART_SEC" | grep -Eq '^[0-9]+([a-z]+[0-9]*)*$'; then
    echo "错误: 非法的 RestartSec 值: $RESTART_SEC（应为数字+可选单位，如 5 / 5s / 500ms / 1min）"
    exit 1
fi

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

# --- 4) ExecStart 写入/替换 -Dfault.tag（轮次 tag 必填）---
if grep -Eq '^[[:space:]]*ExecStart=.*-Dfault\.tag=' "$SERVICE_FILE"; then
    sed -i -E "s|(^[[:space:]]*ExecStart=.*)-Dfault\.tag=[^[:space:]]+|\1-Dfault.tag=${TAG_ESC}|" "$SERVICE_FILE"
    echo "已替换 ExecStart 中原有的 -Dfault.tag"
else
    sed -i -E "s|(^[[:space:]]*ExecStart=.*-javaagent:[^[:space:]]+)|\1 -Dfault.tag=${TAG_ESC}|" "$SERVICE_FILE"
    echo "已在 -javaagent 后追加 -Dfault.tag=${TAG}"
fi

# --- 5) 追加/替换用户传入的 JVM 参数（可多个）---
# 值与 key 已在解析阶段做白名单校验，无需额外转义除 & | 外的字符
for ARG in "${JVM_ARGS[@]:-}"; do
    [ -n "$ARG" ] || continue
    KEY="${ARG#-D}"
    KEY="${KEY%%=*}"
    KEY_ESC=$(printf '%s' "$KEY" | sed -e 's/[&|.]/\\&/g')
    ARG_ESC=$(printf '%s' "$ARG" | sed -e 's/[&|]/\\&/g')
    if grep -Eq -- "-D${KEY_ESC}=" "$SERVICE_FILE"; then
        sed -i -E "s|(^[[:space:]]*ExecStart=.*)-D${KEY_ESC}=[^[:space:]]*|\1${ARG_ESC}|" "$SERVICE_FILE"
        echo "已替换 ExecStart 中原有的 -D${KEY}"
    else
        sed -i -E "s|(^[[:space:]]*ExecStart=.*)(-jar )|\1${ARG_ESC} \2|" "$SERVICE_FILE"
        echo "已在 -jar 前追加 ${ARG}"
    fi
done

# --- 6) 替换 RestartSec（提供了第 4 参数时；幂等）---
# 值经格式校验后仅含数字与字母，无需额外转义
RESTART_SEC_APPLIED=0
if [ -n "$RESTART_SEC" ]; then
    if grep -Eq '^[[:space:]]*RestartSec=' "$SERVICE_FILE"; then
        sed -i -E "s|(^[[:space:]]*RestartSec=).*|\1${RESTART_SEC}|" "$SERVICE_FILE"
        echo "已替换 RestartSec=${RESTART_SEC}"
        RESTART_SEC_APPLIED=1
    elif grep -Eq '^[[:space:]]*Restart=' "$SERVICE_FILE"; then
        sed -i -E "/^[[:space:]]*Restart=/a RestartSec=${RESTART_SEC}" "$SERVICE_FILE"
        echo "已添加 RestartSec=${RESTART_SEC}（原 service 无该行，插在 Restart= 之后）"
        RESTART_SEC_APPLIED=1
    else
        echo "警告: service 无 RestartSec= 与 Restart= 行，跳过 RestartSec 修改"
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
if [ "$RESTART_SEC_APPLIED" -eq 1 ] && ! grep -Eq "^[[:space:]]*RestartSec=${RESTART_SEC}[[:space:]]*$" "$SERVICE_FILE"; then
    echo "错误: RestartSec 修改未生效，已保留备份 $BACKUP"
    exit 1
fi
for ARG in "${JVM_ARGS[@]:-}"; do
    [ -n "$ARG" ] || continue
    if ! grep -Fq -- "${ARG}" "$SERVICE_FILE"; then
        echo "错误: JVM 参数 ${ARG} 修改未生效，已保留备份 $BACKUP"
        exit 1
    fi
done

echo "修改完成: $SERVICE_FILE"
grep -nE '^(StartLimit|ExecStart|ExecStartPre|ExecStopPost|RestartSec)=' "$SERVICE_FILE" || true
echo
echo "后续步骤（需 root）："
echo "  1. systemctl daemon-reload"
echo "  2. systemctl restart <服务名>      # 如 focus@app1"
