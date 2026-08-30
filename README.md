# fault-sandbox 使用手册

> 基于 JVM-Sandbox 的进程级故障注入工具：目标应用启动时自动解析用户类与方法并挂载故障模块，**任意用户代码行首次执行到时，记录故障（机器/类/方法/行/线程）并 kill 掉当前进程**。
> 设计原理与难题解决见 [DESIGN.md](DESIGN.md)，验证记录见 [tests/TEST_CASES.md](tests/TEST_CASES.md)。

## 一、快速开始（三步）

```bash
# 第 1 步：建库建表（仅首次，MySQL 服务器上执行）
mysql --host=<mysql地址> --user=root -p < common/src/main/resources/schema.sql

# 第 2 步：上传产物
#   fault-module-1.0.0.jar → <sandbox安装目录>/sandbox-module/
#   fault-agent-1.0.0.jar  → 任意目录（如 /home/lys2/）

# 第 3 步：带 agent 启动目标应用（-Dfault.tag 指定本轮演练标识）
java -Dfault.tag=round-001 \
     -javaagent:/home/lys2/fault-agent-1.0.0.jar \
     -jar /home/lys2/your-app.jar
```

启动后自动完成：解析 bootJar → 结果入库 → 挂载故障模块 → 应用继续启动 → **任一用户代码行首次执行到时进程被 kill -9，故障详情写入 `t_fault_record`**。

## 二、前置条件

| 条件 | 说明 |
|---|---|
| 目标应用**必须以 `-jar` 方式启动** | bootJar 路径从启动参数自动定位，`-cp` 起主类的方式不支持 |
| MySQL 可达 | 目标机器能连上数据库（默认端口 3306） |
| JDK | Linux 上 OpenJDK 8/11/17/21 均已验证兼容 Java 8 字节码产物 |
| JVM-Sandbox 已安装 | `<sandbox安装目录>/bin/sandbox.sh` 存在，模块 jar 已放入 `sandbox-module/` |

## 三、配置说明

配置随 fat jar 内置（`config.properties`），修改后需重新打包。agent 侧还支持启动参数覆盖：
`-javaagent:fault-agent.jar=键=值,键2=值2`（仅 agent，多个用逗号分隔）。

### fault-agent 的 config.properties

| 键 | 必填 | 说明 |
|---|---|---|
| `jdbc.host` | 是 | MySQL 地址（跨机部署填 MySQL 所在机器 IP） |
| `jdbc.username` | 是 | 数据库用户 |
| `jdbc.password` | 是 | 数据库密码 |
| `lib.whitelist` | 否 | **lib 白名单**：bootJar 的 `BOOT-INF/lib/` 中需要解析的 jar 文件名，**逗号分隔多个**，支持前缀匹配。例：`lib.whitelist=biz-dao,biz-service,order-common-2.1.jar`。留空 = 只解析 `BOOT-INF/classes/` |
| `sandbox.sh.path` | 是 | sandbox.sh 绝对路径 |
| `premain.timeout.ms` | 否（默认 600000） | premain 全程总预算（解析+落库+挂载），大项目按需调大 |
| `orphan.threshold.minutes` | 否（默认 10） | 异机孤儿解析判定阈值（分钟） |

> `jdbc.url` 键可整体覆盖连接串（默认按 jdbc.host 拼接，已带 5s/10s 连接超时，勿随意去掉）。

### fault-module 的 config.properties

| 键 | 必填 | 说明 |
|---|---|---|
| `jdbc.host` | 是 | MySQL 地址（与 agent 指向同一库） |
| `jdbc.username` | 是 | 数据库用户 |
| `jdbc.password` | 是 | 数据库密码 |

## 四、轮次（tag）机制

- 每轮演练通过 JVM 参数标识：**`-Dfault.tag=<轮次标识>`**（操作者手动添加）。
- **不带 `-Dfault.tag` 启动**：模块挂载成功后立即 kill 进程，应用不会运行（缺轮次标识无法判重，按硬保护处理，`t_error_record` 会留痕）。
- 判重规则：同一行在**同一轮内**只会故障一次；换新 tag 后所有行重新可注入；不同轮次互不影响。
- 集群语义：多节点并行时，同一行同一轮**最多导致集群内一个节点死亡**（由数据库唯一索引保证）。

## 五、关键日志速查（`logs/fault-agent.log` 与 `logs/fault-module.log`）

### 正常链路（agent 日志）

| 日志 | 含义 |
|---|---|
| `premain start: pid=... budget=...` | agent 开始工作 |
| `bootJar located: /path/app.jar` | 成功定位 bootJar |
| `schema check OK: all 4 tables exist` | 建表校验通过 |
| `unit stored: type=CLASSES source=... unitId=N classes=A methods=B` | 该解析单元首次/重新解析完成入库 |
| `unit already completed, skip: ... unitId=N` | 该单元此前已解析（本单元方法数不会重复入库） |
| `re-parse claimed (previous FAILED / pending left by THIS machine / orphan pending...)` | 触发了重解析及原因 |
| `mount cmd: [bash, ..., -d, fault-module/inject?id=...]` | 正在执行挂载命令 |
| `sandbox.sh exit=0` | 挂载成功 |
| `premain completed: mount OK, release application startup` | 应用开始启动（此刻起已处于保护中） |

### 命中与注入（module 日志）

| 日志 | 含义 |
|---|---|
| `FAULT HIT & PREEMPTED: tag=... unitId=... class=... method=... line=... thread=... machine=...` | **故障命中**：该行首次执行到，记录后进程即将被 kill。这是最重要的日志 |
| `line already preempted in this round, release execution: class#method#line` | 该行本轮已被其他节点触发，本节点放行（每行仅记一次） |
| `inject skipped: units already injected` | 重复执行挂载命令被忽略 |

### 异常与告警（出现即代表按策略 kill）

| 日志 | 含义 | 处理 |
|---|---|---|
| `HARD PROTECT: phase=PARSE, type=EXCEPTION, msg=bootJar not located` | 未以 `-jar` 方式启动 | 改为 `java -javaagent:... -jar app.jar` 启动 |
| `HARD PROTECT: phase=DB, msg=schema check failed ... (run schema.sql manually)` | 库表未建/不可达 | 先执行 `schema.sql`；检查 `jdbc.host`/网络/账号 |
| `HARD PROTECT: phase=DB, ... Communications link failure` | MySQL 连不上 | 检查 MySQL 存活、`jdbc.host`、防火墙 3306 |
| `HARD PROTECT: phase=MOUNT, type=TIMEOUT, msg=sandbox.sh wait timeout` | 挂载超时 | 检查 sandbox 安装与 `sandbox.sh.path`；适当调大 `premain.timeout.ms` |
| `HARD PROTECT: phase=MOUNT, ... sandbox.sh exit code=1` | 挂载命令失败 | 看 `mount cmd` 下方的输出内容定位（权限/模块 jar 缺失等） |
| `jvm property 'fault.tag' missing -> kill process per policy` | **启动时没加 `-Dfault.tag`**，进程被按策略 kill | 启动命令补上 `-Dfault.tag=<轮次>` |
| `write t_error_record failed, fallback to local log only` | MySQL 不可达，错误只落在本地日志 | 恢复 MySQL 后重启 |
| `unit pending on other node (in progress), skip` | 其他节点正在解析，本节点跳过 | 无需处理 |
| `re-parse claimed (pending left by THIS machine)` | 上次解析被中断（如进程被杀），本次续传 | 无需处理 |

## 六、常见问题（FAQ）

**Q1：应用启动后没被 kill，是故障没挂上吗？**
不一定。kill 只在"某行首次执行到"时触发。看 module 日志是否有 `inject done: registered=N/M`；用 `curl` 打一个会走业务方法的接口验证。若已挂载且方法执行过，检查该行是否本轮已被触发过（`t_fault_record` 按 tag 查）。

**Q2：换了一版应用重启，为什么又解析了一次？**
应用内容变化 → 解析单元 hash 变化 → 自动重新解析并生成新 unitId。旧单元结果保留，属于预期行为。

**Q3：重启后没有 kill，直接起来了？**
该轮 tag 下启动必经的行都已被记录过（判重放行）。换一个新 tag 即可开始新一轮。

**Q4：多节点集群会不会全死？**
不会。同一行同一轮最多死一个节点（数据库唯一索引保证），各节点死于不同行时会各死一次。

**Q5：想临时不让 agent 干活？**
从启动命令去掉 `-javaagent` 参数重启即可（不要用其它方式绕过，无 tag 时进程会被 kill）。

## 七、限制与边界（设计取舍）

| 边界 | 原因 |
|---|---|
| `<clinit>`（静态初始化）不注入 | 运行在类初始化锁内，回调中的 JDBC 会触发额外类加载，有递归/死锁的理论风险；且内容多为静态字段赋值，注入价值低。判重表可兜底重复，排除是稳妥选择 |
| bridge / `access$xxx` synthetic 方法不注入 | 编译器生成的 1-2 行转发代码，行号指向原方法声明处，hook 会与目标方法重复命中 |
| lambda（`lambda$xxx`）**会注入** | lambda 体内是用户逻辑（已实测命中） |
| 接口的抽象方法声明不注入 | 无方法体；实现类方法正常 hook |
| 必须以 `-jar` 启动 | bootJar 路径从启动参数定位 |
| 无 `-Dfault.tag` 则进程被 kill | 无轮次标识无法判重，按硬保护不放行 |
| 全量行 hook 有性能开销 | 仅本轮未命中行存在；已命中/已抢占行有内存短路 |
| 基于 JVM-Sandbox 1.4.0 | 无 `withLoad()`；watcher 常驻 matcher 对后加载类天然生效 |

## 八、卸载

演练结束：从启动命令移除 `-javaagent` 参数与 `-Dfault.tag`，重启应用即恢复原状（模块 jar 可留在 sandbox-module 目录，不影响未挂载的进程）。
