# fault-sandbox 设计文档

> 本文描述系统的设计思路、关键难题与解决方案。使用说明见 [README.md](README.md)，验证记录见 [tests/TEST_CASES.md](tests/TEST_CASES.md)。

## 1. 背景与目标

对一个以 bootJar 发布的 Spring Boot 应用做混沌演练：**进程随机死在某一行用户代码上，且要能回答"死在哪台机器、哪个 jar、哪个类、哪个方法、哪一行、哪个线程"**。

设计目标拆解为四个能力：

1. **自动发现**：不侵入应用代码，启动时自动解析 bootJar 得到全部用户类与方法清单；
2. **自动挂载**：无需人工干预，应用启动过程中自动完成 sandbox 模块挂载；
3. **行级注入**：任意用户代码行首次执行到时可触发 kill，且多节点集群中"一行最多死一个节点"；
4. **硬保护**：故障注入体系自身失败时，宁可杀掉进程也绝不让应用在无保护状态下运行。

## 2. 总体架构

```
┌────────────────────────────────────────────────────────────────┐
│ 目标应用 JVM（java -javaagent:fault-agent.jar -jar app.jar）      │
│                                                                │
│  ┌─ premain（主线程，应用启动前）────────────────────────────┐    │
│  │ fault-agent：定位bootJar → 解析单元hash → 表1状态机去重      │    │
│  │            → ASM解析 → INSERT IGNORE落表2 → 置completed    │    │
│  │            → 同步执行 sandbox.sh 挂载模块（阻塞等待）         │    │
│  │            → 失败/超时：表4 + kill（硬保护）                │    │
│  └────────────────────────────────────────────────────────┘    │
│                                                                │
│  ┌─ sandbox 模块（独立 module classloader）─────────────────┐    │
│  │ fault-module：inject?id=2,3 → 查表2 → 每类注册行级watch    │    │
│  │             beforeLine: INSERT表3抢占 → 成功kill/冲突放行  │    │
│  └────────────────────────────────────────────────────────┘    │
└────────────────────────────────────────────────────────────────┘
                    │ JDBC                        │ JDBC
                    ▼                             ▼
        ┌─────────────── MySQL fault_sandbox（4张表）───────────────┐
        │ t_jar_record(状态机)  t_class_method(方法明细)             │
        │ t_fault_record(轮内抢占)  t_error_record(agent自身错误)     │
        └──────────────────────────────────────────────────────────┘
```

两个交付物 + 一份手工建表脚本（`common/src/main/resources/schema.sql`），全部配置随各自 fat jar 内置。

## 3. 关键难题与解决方案

### D1 bootJar 定位：JDK9+ 的 `-jar` 从 inputArguments 消失了

最初方案用 `RuntimeMXBean.getInputArguments()` 找 `-jar` 参数，在 Linux OpenJDK 21 上实测**拿不到**：JDK9 起 `-jar` 及其路径被归入 main 侧参数，不出现在 inputArguments（JDK 8 才包含）。

**解决**：四级定位链，跨 JDK 8~21：

1. `sun.java.command` 系统属性首个 token（实测 premain 阶段值恰为 `-jar` 的路径）；
2. `/proc/self/cmdline`（Linux，null 分隔的完整命令行，取 `-jar` 的后继参数）；
3. `inputArguments`（兜底 JDK 8 行为）；
4. 均失败 → 硬保护：明确报"应用必须以 -jar 方式启动"。

约束：**应用必须以 `-jar` 方式启动**，这是文档明确的边界（`-cp` 起主类无法定位 bootJar）。

### D2 解析粒度：为什么按"解析单元"切分且行号不入库

- **粒度**：`BOOT-INF/classes/` 整体一个单元，hash = 目录下全部 .class 条目按路径排序后内容聚合 SHA-256；白名单命中的每个 lib jar 各一个单元，hash = 字节流 SHA-256。任何类改动重打包后对应单元 hash 变化，自动触发重新解析。
- **行号不入库**：sandbox 的 `beforeLine(advice, lineNum)` 回调自带行号，静态解析只需方法名+描述符；表2 的 `INSERT IGNORE`（业务唯一索引）天然幂等。
- **synthetic 边界**：synthetic 方法仅纳入 `lambda$` 前缀（lambda 体内是用户逻辑，实测命中 `lambda$auditAll$0` 内部行）；bridge/access$ 等转发型 synthetic 排除（方法体 1-2 行转发且行号指向原声明处，hook 会与目标方法重复命中）；`<clinit>` 排除（见 D10）。

### D3 并发解析：唯一键占位 + 状态机

多节点同时启动会同时尝试解析同一单元。解法是**数据库占位**：

- 先 `INSERT` 一行 `status=pending`（sha256 唯一索引）——插入成功者获得解析权，天然防并发重复；
- 冲突方读状态分流：`completed` 跳过 / `failed` 重解析 / `pending` 超时（孤儿）抢占重解析；
- 重解析不需要清理旧数据：表2 唯一索引 `uk(unit_id,class,method,desc)` + `INSERT IGNORE`，增量补齐收敛到与全量解析一致的结果。
- 状态机分支（含"本机中断识别"）见下：

| 读到的状态 | 判定 | 动作 |
|---|---|---|
| 无记录 | 首次解析 | 插入 pending 后解析 |
| completed | 已解析 | 跳过 |
| failed | 上次解析异常 | 条件 UPDATE 置回 pending + 刷新本节点 hostname/ip → 解析 |
| pending 且 ip=自己 且 boot_jar 相同 | 本机上次解析中断（进程被 kill） | 立即重解析，不等超时 |
| pending 且 ip≠自己、未超时 | 其他节点解析中 | 等待跳过 |
| pending 且 ip≠自己、超时 | 异机孤儿 | 抢占重解析 |

同机多实例同时抢到同一 pending 也无害：表2 幂等收敛。

### D4 挂载时机：同步阻塞在 premain 里

**为什么同步**：premain 阻塞主线程期间，应用启动流程尚未开始，**不存在任何用户代码执行**——挂载必然先于一切业务代码（含 `@PostConstruct`/`CommandLineRunner`），kill 死点不会错过。若异步挂载，启动期执行的代码（如 bean 构造、Runner）可能跑在 watch 就绪之前。

**premain 阶段能被 attach 吗**：可以。sandbox.sh 从外部进程 attach 本 JVM，Attach Listener 是独立线程；主线程阻塞在 native 等待（waitFor）不挡 safepoint，retransform 的 VM_Operation 可正常完成——已在 Linux OpenJDK 21 实测打通。

**失败语义（硬保护）**：`premain.timeout.ms`（默认 10 分钟）是解析+落库+挂载全程总预算，任一阶段超时或异常 → 表1 置 failed（仅解析中单元；挂载阶段失败不动已完成的解析结果）→ 写表4（DB 不可用则降级本地日志）→ `kill` 当前进程。DB 挂起时 connectTimeout/socketTimeout（5s/10s）保证 kill 必达。

### D5 kill 前的判重：轮次 tag + 数据库唯一索引

- **问题**：多节点并行时同一行会被多个节点同时执行；且同一行跨重启、跨轮次是否应重复注入需要语义。
- **方案**：表3 唯一索引 `uk(unit_id, class_name, method_name, line_no, tag)`。`beforeLine` 直接 `INSERT IGNORE`：
  - 成功 = 本节点赢得"该行该轮"的故障执行权 → 记录后 `kill -9`；
  - DuplicateKey = 该行本轮已被其他节点触发 → 放行继续执行。
- **轮次 tag**：操作者给目标应用加 `-Dfault.tag=tagA`。模块 inject 时读取：缺失 → 写表4 + 直接 kill（无轮次标识的记录无法判重，按硬保护不放行）；存在 → 整轮使用该 tag。换新 tag = 所有行重新可注入。
- **原子性**：判重完全依赖 MySQL 唯一索引（`INSERT IGNORE` 为单语句原子操作），实测 5 路并发同抢一行仅 1 条成功——不依赖应用内锁，多机多实例天然安全。进程内的 AtomicBoolean/preemptedLines 只是减少重复撞库的性能优化。

### D6 类加载冲突：agent 与应用共享 system classpath

premain agent jar 被追加到目标应用的 **system classpath**，agent 携带的 mysql-connector/ASM/protobuf 会与应用自身依赖同池加载，版本冲突会破坏应用。解法：shadowJar 对上述依赖 **relocate** 到 `cn.chinaclear.fault.shaded.*`。sandbox 模块运行在 sandbox 独立的 module classloader 中，无此问题，无需 relocate。同理，整个工具不引入 slf4j/logback，使用自写文件日志（`FaultLogger`），避免日志实现污染宿主。

### D7 sandbox.sh 的隐式依赖

`sandbox.sh` 内部用 `SANDBOX_HOME_DIR=${PWD}/..` 定位安装目录——**必须在 sandbox/bin 目录下执行**。解法：`ProcessBuilder.directory(script 父目录)` 显式设置工作目录，不依赖当前 shell 的 CWD。

### D8 未加载类的覆盖：不需要 withLoad

sandbox 1.4.0 的 `EventWatchBuilder` 没有 `withLoad()`（更高版本 API）。但 watcher 是**常驻 matcher**：激活时 retransform 已加载类，之后每次类加载事件都会对新类做匹配增强。因此"premain 同步挂载 + watcher 常驻"即覆盖全部用户类——包括启动期和运行期才加载的类。

### D9 硬保护与爆炸半径

原则：**故障注入工具自身故障不得拖垮目标应用以外的东西，也不能放行未保护的应用**。具体：

- premain 解析/落库/挂载任何异常 → 表4 + kill（应用不放行）；
- DB 不可达时写表4 也会失败 → 降级为本地日志 → 仍然 kill；
- JDBC 带 connectTimeout=5000/socketTimeout=10000，保证 DB 挂起时 kill 不被无限拖延；
- kill 三级兜底：`kill -9` → `taskkill /F`（Windows 自测）→ `Runtime.halt(137)`，每级都有日志；
- 唯一放行例外不存在于运行时——不放 agent jar 应用本来就不受影响（演练结束后摘掉参数重启即可）。

### D10 日志体系

不依赖 slf4j（避免实现类冲突），自写 `FaultLogger`：同步写文件（`logs/fault-agent.log` / `logs/fault-module.log`）+ stderr，时间戳+线程名+级别，异常带堆栈。关键分支全覆盖：premain 各阶段、状态机每个分支的原因（failed 重解析/本机中断/孤儿抢占/被他节点抢先）、mount 命令与输出、命中与抢占、DuplicateKey 放行（每行仅首次）、kill 命令执行结果与兜底分支。

## 4. 数据模型

见 `common/src/main/resources/schema.sql`（操作者手动执行）。要点：

| 表 | 语义 | 关键约束 |
|---|---|---|
| `t_jar_record` | 解析单元（状态机 pending/completed/failed） | `uk(sha256)` 占位防并发 |
| `t_class_method` | 类-方法明细 | `uk(unit_id,class,method,desc)` 幂等 |
| `t_fault_record` | 故障命中（含轮次 tag） | `uk(unit_id,class,method,line,tag)` 轮内抢占 |
| `t_error_record` | 工具自身错误 | — |

## 5. 已验证场景

TC1-TC8（解析/跳过/命中/续抢/多节点/续传/硬保护/配置）与 V1-V4（tag 机制/lambda 命中）全部通过，明细见 `tests/TEST_CASES.md`。并发抢占的数据库层原子性单独用 5 路并行 `INSERT IGNORE` 实测（仅 1 条成功）。
