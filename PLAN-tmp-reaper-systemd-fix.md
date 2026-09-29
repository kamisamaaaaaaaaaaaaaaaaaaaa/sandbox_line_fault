# 修复方案：临时副本清理在 systemd 部署下失效（/tmp 写满 → 挂载 404）

> 状态：待确认执行。
> 背景：2026-09-07 生产事故（test-focus-pet-nwtr-de-app01 / `focus@nwtr-de.service`）。
> 相关文档：README 6.4、DESIGN 3.2.4、TEST_CASES（新增 M 系列）。

## 1. 事故现象

| 时间 | 状态 |
|---|---|
| 16:47 ~ 23:34:48 | 挂载全部成功，每轮 reaper 快照 6 个副本 |
| **23:35:39 起** | 100% 失败：sandbox 返回 404，硬保护 kill，每 30 秒一轮持续 |
| 23:35:07 | 最后一个成功进程（pid=2637757）命中故障 → `KILL current process` |
| 23:35:07 之后 | `fault-module.log` 再无写入——之后所有进程模块从未加载成功 |

404 的准确含义：

```text
00:38:52 default WARN  path=/fault-module/inject is matched module fault-module, but not existed.
```

sandbox 内核里**没有 id 为 fault-module 的已注册模块**。

## 2. 根因链（铁证）

```text
sandbox.log：
loading module-jar occur error! module-jar=/app/.../sandbox-module/fault-module-1.0.0.jar;
java.nio.file.FileSystemException: ... -> /tmp/sandbox_module_jar_15812813955518253142.jar:
  No space left on device
    at com.alibaba.jvm.sandbox.core.classloader.ModuleJarClassLoader.copyToTempFile(...)
```

1. **sandbox 每次 attach 复制 `sandbox-module/` 目录下全部 6 个模块 jar 到 `/tmp`**
   （`sandbox-mgr-module` / `sstx-module` / `DB-info-module` / `fault-module` / `job-start-module` / `AUTO-life-cycle-FaultInjection`，其中 4 个是别人写的 `*-jar-with-dependencies.jar`）——不止我们的 jar
2. **每轮进程死亡后，副本从未被清理**
   - `focus@.service`：`KillMode=mixed`、`Restart=on-failure`、`RestartSec=30s`
   - `KillMode=mixed`：停止时 SIGTERM 给 Main PID，**SIGKILL 给 cgroup 内其余所有进程**
   - TmpReaper 用 `setsid` 只能躲开会话/进程组信号，**躲不开 cgroup 级 SIGKILL**
   - 轮询周期 2 秒 vs systemd cgroup 清理毫秒级 → **reaper 每轮都在 sleep 中被杀，`rm` 从未执行**
3. **累积写满 /tmp**：471 轮 spawn（2810 份快照，467 轮为 6 份；spawn 失败 0 次；快照覆盖完整，排除“快照盲区”）→ 首次 `No space` 23:34:46 → 此后 `copyToTempFile` 必失败 → 模块未注册 → 404 → 硬保护 kill

补充事实：
- 用户未主动 `systemctl stop`；自动 restart 的停止阶段同样清理 cgroup
- `SuccessExitStatus=143` 不含 `kill -9` 的 137 → on-failure 必触发
- 副本文件名是 `File.createTempFile` 的随机数，**ID 不含来源信息**；副本 mtime = 源 jar mtime（`FileUtils.copyFile` 保留时间戳），不能用于推断创建时间
- 失败轮后续：117 轮 `tmp reaper skipped`（/tmp 完全写不下，本进程无副本 fd）

## 3. 方案

### 3.1 主力：ExecStopPost 清扫（消除对长生命周期进程的依赖）

```ini
[Service]
WorkingDirectory=/app/deploy/%i
ExecStartPre=/bin/mkdir -p tmp
ExecStart=/usr/bin/java ... -Djava.io.tmpdir=tmp -javaagent:/app/deploy/fault-agent-1.0.0.jar -Dfault.tag=<tag> -jar application.jar
ExecStopPost=/bin/sh -c 'rm -f tmp/sandbox_module_jar_*.jar'
```

> **tmp 相对 WorkingDirectory 引用，路径零硬编码**：systemd 保证 `ExecStartPre` / `ExecStart` /
> `ExecStopPost` 三个进程的 cwd 都是 `WorkingDirectory`，因此三行配置里的 `tmp` 在运行期解析为
> `<WorkingDirectory>/tmp`——换应用名、换部署根目录，这三行原样可用，`%i` 都不必写。
> JVM 对相对 tmpdir 的行为：`File.createTempFile` 以进程 cwd（= WorkingDirectory）解析相对路径，
> kernel 侧 `/proc/<pid>/fd` 看到的仍是绝对路径，TmpReaper 快照与清扫不受影响。
> `mkdir -p tmp` 幂等自愈，首次部署无需手工建目录。
>
> 备选（若要求绝对路径）：`StateDirectory=nwtr-de`（systemd ≥235）自动在 `/var/lib` 创建属主目录并可用
> `%S` 引用；老 systemd 不支持，默认不采用。

> **tmpdir 直接指向工作目录（推荐，最简）**：`WorkingDirectory` 由 systemd 保证存在，不存在“目录缺失”
> 的问题，无需 ExecStartPre 建目录。副本（`sandbox_module_jar_*.jar`）与 `application.jar`、`logs/` 等混居
> 于工作目录，但清扫通配只匹配 `sandbox_module_jar_` 前缀，不会误删任何其他文件。
> 备选（隔离更干净）：tmpdir 指向子目录 `/app/deploy/%i/tmp`，需加一行
> `ExecStartPre=/bin/mkdir -p /app/deploy/%i/tmp`（JVM 不会自动创建该目录）并垫 premain 的
> `Files.createDirectories` 兜底；应用全部临时文件与模块副本都收在子目录里，工作目录保持整洁。

- **独立 tmpdir**：副本落进应用独占目录，与共享 /tmp 隔离，也不再受别人模块 jar 体积牵连
- **ExecStopPost**：systemd 执行顺序为「停止（cgroup 清理，reaper 在此被连坐）→ **ExecStopPost** → RestartSec → 拉起」，故它不受连坐影响，**本轮泄漏本轮清，零延迟**

退出路径全覆盖：

| 退出方式 | 副本清理 |
|---|---|
| 命中故障 kill -9（自杀） | systemd 判 on-failure → 停止阶段 → ExecStopPost ✓ |
| 运维 `systemctl restart/stop` | ✓ |
| 运维直接 `kill -9 <pid>`（绕过 systemd） | systemd 检测 Main PID 消失 → 同样走 on-failure ✓ |
| 演练结束、卸载 agent、正常停服务 | ✓（连“最后一轮”都有人收尾） |

### 3.2 兜底：TmpReaper 保留 + 代码层目录自建

- 清理逻辑**不动**，仅更新注释中的角色定位：systemd 部署以 ExecStopPost 为准；reaper 兜底非 systemd 环境（进程组/会话级死亡仍有效）
- **premain 增加一步**：配置加载后、挂载开始前，对 `java.io.tmpdir` 做 `Files.createDirectories`（幂等）——即使 ExecStartPre 被漏配、目录不存在，agent 也能自建，模块复制不会因此失败。此步同时覆盖 agent 与 module 两个 JVM
- 不删除 reaper（用户明确要求保留）

### 3.3 脚本联动：add-agent-to-service.sh

这是把 agent 接入 systemd 的唯一入口，需同步注入上述配置：

1. `ExecStartPre` 幂等添加 `/bin/mkdir -p tmp`（相对 WorkingDirectory，见 3.1）
2. `ExecStart` 幂等插入 `-Djava.io.tmpdir=tmp`
   - 应用已自带 `-Djava.io.tmpdir` 时**不覆盖**（避免改变应用其他临时文件行为），ExecStopPost 对准已有值并输出提示
3. `ExecStopPost` 幂等添加 `/bin/sh -c 'rm -f tmp/sandbox_module_jar_*.jar'`
4. 头部用法说明与示例更新
5. 幂等性要求：对同一 service 文件执行两遍，结果一致
   - 注：三行均使用相对路径，脚本**无需解析 WorkingDirectory 的绝对值**，注入逻辑比原方案更简单

## 4. 改动清单

| 文件 | 改动 |
|---|---|
| `add-agent-to-service.sh` | tmpdir 解析 + ExecStartPre/ExecStart 插入 + ExecStopPost 注入 + 幂等 + 说明更新 |
| `common/.../FaultLogger.java` 之外的 agent/module 启动路径 | premain/inject 前对 `java.io.tmpdir` 做 `Files.createDirectories`（代码层目录自建兜底，见 3.2） |
| `fault-agent/.../TmpReaper.java` | 仅注释：角色降级为兜底 |
| `DESIGN.md` 3.2.4 | 重写：连坐机制从“可忽略边界”升级为“已知必现的部署形态问题”（附事故数据），方案原理 |
| `README.md` | 6.4 临时副本清理重写；前置条件/快速开始补 tmpdir 说明 |
| `TEST_CASES.md` | 新增 M 系列验证记录 |

## 5. 验证项

### M1 KillMode=mixed 连坐实证（Linux 测试机 192.168.193.129；本机 Windows 无 systemd）

- 对照组：test-app 带 agent 挂载成功（产生副本 + reaper spawn）→ `systemctl restart` →
  `systemctl status` 的 CGroup 内 reaper 进程消失 + `/tmp` 副本残留 → **坐实连坐**
- 修复组：注入 tmpdir + ExecStopPost 后同样 restart → 副本**零残留**、tmpdir 干净

### M2 多模块目录场景

- `sandbox-module/` 放 `fault-module` + 2~3 个占位大 jar（模拟生产 6 模块）
- 确认：`copyToTempFile` 复制全部、tmpdir 隔离生效、ExecStopPost 全清、**多模块共存下我们的挂载/注入正常**

### M3 本机可做

- 构建通过
- `add-agent-to-service.sh` 对样例 service 执行两遍，diff 无变化（幂等）
- parse-only 冒烟

> 若远程测试机不可达：交付完整验证脚本，由用户在环境上执行后回贴判读。

## 6. 生产即时止血（无需等发版）

1. `focus@.service` 加上述三行（`ExecStartPre` 建目录、`-Djava.io.tmpdir=tmp`、`ExecStopPost` 清扫），`systemctl daemon-reload`
2. 清理 `/tmp` 存量：`rm -f /tmp/sandbox_module_jar_*.jar`（当前无模块在运行，安全）
3. 重启服务恢复演练
