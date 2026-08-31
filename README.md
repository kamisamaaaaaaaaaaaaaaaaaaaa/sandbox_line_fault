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

配置随 fat jar 内置（`config.yml`），修改后需重新打包。agent 侧还支持启动参数覆盖：
`-javaagent:fault-agent.jar=键=值,键2=值2`（仅 agent，多个用逗号分隔）。

### fault-agent 的 config.yml

**必填项是 `jdbc.url`、`jdbc.username`、`jdbc.password`**（代码内强校验，缺失则进程被按策略 kill）；其余均有代码默认值，按需修改。

| 键 | 必填 | 说明 |
|---|---|---|
| `jdbc.url` | **是** | 故障库连接串（已含 5s/10s 连接超时，勿随意去掉） |
| `jdbc.username` | **是** | 数据库用户 |
| `jdbc.password` | **是** | 数据库密码 |
| `lib.whitelist` | 否 | **lib 白名单**：bootJar 的 `BOOT-INF/lib/` 中需要解析的 jar 文件名，**正则表达式（对文件名全串匹配）**。**YAML 列表写法，一行一个 `- ` 开头**。**不配置该参数 = 只解析 `BOOT-INF/classes/`**。非法正则会被跳过并告警 |
| `mount.enabled` | 否（默认 `true`） | **是否注入故障**：true = 解析后自动挂载模块并注入；**false = 纯解析模式**（只把类/方法清单落库，应用正常启动，不挂载不注入） |
| `sandbox.home` | **是**（`mount.enabled=true` 时） | sandbox 工具安装目录（挂载脚本自动取其 `bin/sandbox.sh`）。缺失在解析开始前即硬保护，不会白跑解析 |
| `parse.timeout.ms` | 否（默认 `900000`，15 分钟） | **解析阶段总预算**：从 premain 进入时起算，覆盖本节点全部单元的登记/解析/落库（无等待、无接管，各节点各算各的）；超时即硬保护 |
| `mount.timeout.ms` | 否（默认 `1200000`，20 分钟） | **挂载阶段超时**：从解析完成、开始挂载时起算，与解析阶段**各自独立计时** |
| `parse.batch.size` | 否（默认 `2000`） | **解析结果分批写库的批大小**：流式解析（方法不驻留内存），每累积 N 条写库一次。大项目内存敏感可下调（如 500） |
| `log.dir` | 否（默认 `logs`） | agent 日志目录（相对路径基于目标进程工作目录，建议设绝对路径如 `/var/log/fault`） |

### fault-module 的 config.yml

| 键 | 必填 | 说明 |
|---|---|---|
| `jdbc.url` | **是** | 故障库连接串，须与 agent 指向同一库 |
| `jdbc.username` | **是** | 数据库用户 |
| `jdbc.password` | **是** | 数据库密码 |
| `inject.batch.size` | 否（默认 `5000`） | 分批读取方法清单的批大小（避免大项目一次性读入打爆内存） |
| `exclude.methods` | 否 | **注入排除**：方法正则，**全串匹配 `完全限定类名.方法名`**。**YAML 列表写法，一行一个 `- ` 开头**。排除整类写 `- cn\.demo\.OrderService\..*`，排除单方法写 `- cn\.demo\.OrderService\.pay`；命中的方法不注册 watch（注册前过滤）。**不配置 `exclude` 段 = 不排除任何方法**。非法正则会被跳过并告警 |
| `log.dir` | 否（默认 `logs`） | 模块日志目录（module 独立配置，与 agent 的 log.dir 互不影响） |

> **正则转义（agent 的 `lib.whitelist` 与 module 的 `exclude.methods` 规则相同）**：两者都是 Java 正则，`.` 是通配符；要表示字面量的点请写 `\.`（不转义也能匹配到，但会放宽，例如 `OrderService` 会连 `OrderXService` 一起匹配）。**推荐列表项不加引号直接写 `\. `**；若加**双引号**则必须双写反斜杠 `- "cn\\.demo\\..*"`，否则 YAML 报扫描错误（实测：双引号里写单个 `\.` 会抛 `while scanning a double-quoted scalar`）。

## 四、轮次（tag）机制

- 每轮演练通过 JVM 参数标识：**`-Dfault.tag=<轮次标识>`**（操作者手动添加）。
- **不带 `-Dfault.tag` 启动**：模块挂载成功后立即 kill 进程，应用不会运行（缺轮次标识无法判重，按硬保护处理，`t_error_record` 会留痕）。
- 判重规则：同一行在**同一轮内**只会故障一次；换新 tag 后所有行重新可注入；不同轮次互不影响。
- 集群语义：多节点并行时，同一行同一轮**最多导致集群内一个节点死亡**（由数据库唯一索引保证）。

## 五、关键日志速查（`<log.dir>/fault-agent.log` 与 `<log.dir>/fault-module.log`，默认 `logs/`，可用 `log.dir` 配置指定）

### 正常链路（agent 日志）

| 日志 | 含义 |
|---|---|
| `premain start: pid=... parseTimeout=... mountTimeout=...` | agent 开始工作（两阶段超时各自独立计时） |
| `bootJar located: /path/app.jar` | 成功定位 bootJar |
| `schema check OK: all 4 tables exist` | 建表校验通过 |
| `unit stored: type=CLASSES source=... unitId=N classes=A methods=B` | 该解析单元首次/重新解析完成入库 |
| `unit already completed, skip: ... unitId=N` | 该单元此前已解析（本单元方法数不会重复入库） |
| `unit stored: ...` 出现在未配置白名单的类库上 | 白名单正则命中情况 | 检查 `lib.whitelist` 列表 |
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
| `HARD PROTECT: phase=DB, msg=schema check failed ... (run schema.sql manually)` | 库表未建/不可达 | 先执行 `schema.sql`；检查 `jdbc.url`/网络/账号 |
| `HARD PROTECT: phase=DB, ... Communications link failure` | MySQL 连不上 | 检查 MySQL 存活、`jdbc.url`、防火墙 3306 |
| `HARD PROTECT: phase=MOUNT, type=TIMEOUT, msg=sandbox.sh wait timeout` | 挂载超时 | 检查 sandbox 安装与 `sandbox.home`；适当调大 `mount.timeout.ms` |
| `HARD PROTECT: phase=MOUNT, ... sandbox.sh exit code=1` | 挂载命令失败 | 看 `mount cmd` 下方的输出内容定位（权限/模块 jar 缺失等） |
| `jvm property 'fault.tag' missing -> kill process per policy` | **启动时没加 `-Dfault.tag`**，进程被按策略 kill | 启动命令补上 `-Dfault.tag=<轮次>` |
| `write t_error_record failed, fallback to local log only` | MySQL 不可达，错误只落在本地日志 | 恢复 MySQL 后重启 |
| （无等待/接管类日志） | 解析阶段不等待他节点：未完成即各自解析，表2 幂等收敛 | 无需处理 |
| `HARD PROTECT: ... invalid config.yml` / `required config missing` | 配置文件非法 / 必填项缺失（痕迹在 stdout/app.log，因配置不可用写不了 `logs/`） | 修正 `config.yml` 后重启 |

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

## 八、异常处理路径全景

**总原则**：除 `mount.enabled=false`（纯解析模式）外，**任何阶段异常都不放行**——尽力写 `t_error_record`（表4）后 kill 当前进程。
配置缺失/非法时连库都不可用，此时表4 无法写入，仅本地日志（stdout / `app.log`）留痕后 kill。

**名词约定**：
- **配置缺失** = classpath 里根本没有 `config.yml`（不是"必填项没填"，那是另一行）；
- **配置非法** = `config.yml` 存在但 YAML 语法错误（典型：双引号里写单个 `\.`）；
- 两者都直接硬保护，不做"用内置默认值静默降级"。

### A 组：agent 侧 `premain`（阶段顺序 = 启动顺序）

| # | 阶段 | 异常场景 | 处理 | 表1 影响 | 进程 |
|---|---|---|---|---|---|
| A0 | 日志初始化 | 目录不可写 | 降级仅 stdout，**不中断** | — | 继续 |
| A1 | 配置加载 | `config.yml` **缺失** | 硬保护 `PARSE`（无配置信息，表4 写不了 → 本地日志） | 无 | kill |
| A1 | 配置加载 | `config.yml` **非法**（YAML 语法错） | 硬保护 `PARSE`（同上） | 无 | kill |
| A1 | 配置加载 | 必填项缺失（`jdbc.username/password`） | 首次用库时 `require` 抛错 → 硬保护 `DB` | 无 | kill |
| A2 | bootJar 定位 | 三级兜底（sun.java.command → /proc/self/cmdline → inputArguments）全部失败 | 硬保护 `PARSE` | 无（尚未有单元） | kill |
| A3 | 建表校验 | 库连不上 / 缺表 | 硬保护 `DB` | 无 | kill |
| A4 | 解析 bootJar | zip 打不开/损坏 | 硬保护 `PARSE` | 无 | kill |
| A4 | 解析 class | 单个 class ASM 失败 | 跳过该类，记入 `failedClasses`，**不中断** | 无 | 继续 |
| A4 | 解析 class | 上述失败类落表4 | 单条写失败仅 warn | 无 | 继续 |
| A5 | 表1 登记 | `INSERT IGNORE` 冲突（说明 sha256 已存在）但紧接着 `findBySha256` 查不到行 = 数据不一致 | 硬保护 `DB` | 无 | kill |
| A5 | 状态=completed | 该单元已解析过 | 跳过复用（不重复解析） | 不变 | 继续 |
| A5 | 状态=未完成（pending / 历史 failed） | 首次解析，或上次解析中断、或他节点正在解析 | **本节点直接解析**（无抢占、无等待，表2 幂等收敛） | →completed | 继续 |
| A5 | 任一单元前 | 超过 `parse.timeout.ms` 总预算 | 硬保护 `PARSE/TIMEOUT` | 未完成单元**保持未完成**（不回退），下次启动重新解析 | kill |
| A6 | 表2 落库 + 置 completed | 批量插入或更新失败 | 硬保护 `DB`（携带该单元 id） | 该单元保持未完成 | kill |
| A7 | 挂载 | 剩余时间 ≤ 0 / `sandbox.sh` 等待超时 | `destroyForcibly` + 硬保护 `MOUNT/TIMEOUT`（**unitIds=null**） | **不动**（解析结果有效可复用） | kill |
| A7 | 挂载 | 非 0 退出码 / 脚本不存在 / 被中断 | 硬保护 `MOUNT` | **不动** | kill |
| A8 | 硬保护收尾 | 表4 写失败（DB 正不可用） | 仅本地日志 | — | 仍 kill |
| A8 | 硬保护收尾 | `kill -9` 未生效 | `Runtime.halt(137)` 兜底 | — | 终止 |

### B 组：module 侧 `inject` 命令

| # | 场景 | 处理 | 进程 |
|---|---|---|---|
| B1 | 缺 `-Dfault.tag` | 表4（`phase=MOUNT`，尽力）→ kill | kill |
| B2 | `id` 参数空/非法 | 表4（`phase=INJECT`，尽力）→ kill | kill |
| B3 | 重复 inject（同 unitIds 已注入） | 跳过，防重复注册 watch | 继续 |
| B4 | 读表2 / 建连接失败 | 表4（尽力）→ kill | kill |
| B5 | **单类 watch 注册失败** | 表4 留痕 → 抛出，外层统一 kill（覆盖不完整即不放行） | kill |
| B6 | 全部批次读完 `totalMethods == 0`（表2 里这些 unitId 查不到任何方法：解析单元内无 class / 数据被误删 / id 传错） | 表4 + kill（挂了却没方法 = 绝不放行） | kill |

### C 组：运行期 `beforeLine`（每行回调）

| # | 场景 | 处理 | 业务方法 |
|---|---|---|---|
| C1 | 类名不在本批映射 | 直接返回 | 正常执行 |
| C2 | 该行本轮已确认被抢占（本地缓存） | 直接返回（不撞库） | 正常执行 |
| C3 | `INSERT IGNORE` 成功 = 赢得本行本轮执行权 | 日志 → `kill -9` 自己 | **进程终止** |
| C4 | `kill` 命令未生效 | 表4 留痕 + **回滚删除表3 记录**（防脏数据永久阻塞该行） + 移出缓存 | 放行继续 |
| C5 | `INSERT IGNORE` 冲突 = 集群内他节点已触发该行 | 加入本地缓存 | 放行继续 |
| C6 | 任何异常（含 DB 不可用） | 表4（尽力）→ **kill**（宁可不放行，也不放过"已注入却不生效"的进程）；kill 未生效则 `halt(137)` | **进程终止** |

### D 组：状态影响矩阵

| 结果 | 表1 | 表3 | 表4 | 进程 |
|---|---|---|---|---|
| 解析成功 | completed | — | 仅失败 class 明细 | 继续 |
| 解析中途失败/超时 | 该单元**保持未完成**（pending，不回退） | — | 有 | kill |
| 定位/建表/配置失败 | 无记录 | — | 有 | kill |
| 挂载失败/超时 | **保持 completed** | — | 有 | kill |
| 命中故障 | — | 1 行（唯一索引保证集群唯一） | — | kill |
| 同轮他节点已命中 | — | 无（冲突忽略） | — | 放行 |

### 已按"不放行"策略固化的两处（原为放行，现改为 kill）

1. **运行期 `beforeLine` 任何异常（含 DB 不可用）→ kill**（C6）：宁可杀进程，也不放过"已注入却可能不生效"的进程；kill 未生效则 `halt(137)`。
2. **单类 watch 注册失败 → kill**（B5）：故障覆盖不完整即不放行（表4 留痕后由 `inject` 外层统一 kill）。

## 九、卸载

演练结束：从启动命令移除 `-javaagent` 参数与 `-Dfault.tag`，重启应用即恢复原状（模块 jar 可留在 sandbox-module 目录，不影响未挂载的进程）。
