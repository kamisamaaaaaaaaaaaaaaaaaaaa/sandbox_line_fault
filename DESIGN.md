# fault-sandbox 设计文档

> 本文描述系统的架构、核心机制与设计取舍。使用说明见 [README.md](README.md)，验证记录见 [TEST_CASES.md](TEST_CASES.md)。

## 目录

- [1. 背景与目标](#1-背景与目标)
- [2. 总体架构](#2-总体架构)
- [3. 核心机制](#3-核心机制)
  - [3.1 解析](#31-解析定位粒度并发落库)
  - [3.2 挂载](#32-挂载时机串行化副本清理)
  - [3.3 注入与判重](#33-注入与判重)
  - [3.4 类加载与依赖隔离](#34-类加载与依赖隔离)
  - [3.5 硬保护](#35-硬保护)
  - [3.6 可观测性](#36-可观测性)
- [4. 数据模型](#4-数据模型)
- [5. 覆盖率统计口径](#5-覆盖率统计口径)

---

## 1. 背景与目标

对一个以 bootJar 发布的 Spring Boot 应用做混沌演练：**进程随机死在某一行用户代码上，且要能回答"死在哪台机器、哪个 jar、哪个类、哪个方法、哪一行、哪个线程"**。

四项能力及其设计约束：

| 能力 | 约束 | 难点 |
|---|---|---|
| 自动发现 | 不侵入应用代码 | 启动时解析 bootJar，且不能拖慢启动、不能打爆内存 |
| 自动挂载 | 无需人工干预 | 必须**先于一切业务代码**完成，否则启动期执行的行会漏掉 |
| 行级注入 | 一行最多死一个节点 | 多节点并发靠数据库唯一索引仲裁，不依赖分布式协调 |
| 硬保护 | 绝不放行无保护的应用 | 工具自身任何故障都必须是"终止"，而非"降级放行" |

---

## 2. 总体架构

```
┌────────────────────────────────────────────────────────────────┐
│ 目标应用 JVM（java -javaagent:fault-agent.jar -jar app.jar）      │
│                                                                │
│  ┌─ premain（主线程，应用启动前）────────────────────────────┐    │
│  │ fault-agent：定位 bootJar → 解析单元 hash → 表1 登记/跳过     │    │
│  │            → ASM 解析 → 冲突跳过落表2 → 置 completed        │    │
│  │            → 同步执行 sandbox.sh 挂载模块（阻塞等待）         │    │
│  │            → 失败/超时：写表4 + kill（硬保护）               │    │
│  └────────────────────────────────────────────────────────┘    │
│                                                                │
│  ┌─ sandbox 模块（独立 module classloader）─────────────────┐    │
│  │ fault-module：inject?id=2,3 → 查表2 → 每类注册行级 watch     │    │
│  │             beforeLine: INSERT 表3 抢占 → 成功 kill/冲突放行 │    │
│  └────────────────────────────────────────────────────────┘    │
└────────────────────────────────────────────────────────────────┘
                    │ JDBC                        │ JDBC
                    ▼                             ▼
        ┌─────────────── 故障库 fault_sandbox（4 张表）───────────────┐
        │ t_jar_record(状态机)  t_class_method(方法明细)             │
        │ t_fault_record(轮内抢占)  t_error_record(工具自身错误)       │
        └──────────────────────────────────────────────────────────┘
```

两个交付物 + 一份手工建表脚本（`common/src/main/resources/schema.sql`），配置随各自 fat jar 内置。

---

## 3. 核心机制

### 3.1 解析：定位、粒度、并发、落库

#### 3.1.1 bootJar 定位

JDK9 起 `-jar` 及其路径被归入 main 侧参数，不出现在 `RuntimeMXBean.getInputArguments()`（仅 JDK 8 包含），因此定位不能依赖 inputArguments。采用四级定位链，跨 JDK 8~21：

1. `sun.java.command` 系统属性首个 token（premain 阶段该值即 `-jar` 的路径）；
2. `/proc/self/cmdline`（Linux，null 分隔的完整命令行，取 `-jar` 的后继参数）；
3. `inputArguments`（兜底 JDK 8 行为）；
4. 均失败 → 硬保护：明确报"应用必须以 -jar 方式启动"。

**约束**：应用必须以 `-jar` 方式启动（`-cp` 起主类无法定位 bootJar）。

#### 3.1.2 解析粒度与注册口径

- **粒度 = 解析单元**：`BOOT-INF/classes/` 整体一个单元，hash = 目录下全部 `.class` 条目按路径排序后内容聚合 SHA-256；白名单命中的每个 lib jar 各一个单元，hash = 字节流 SHA-256。任何类改动重打包后单元 hash 变化，自动触发重新解析。
- **行号不入库**：sandbox 的 `beforeLine(advice, lineNum)` 回调自带行号，静态解析只需方法名 + 描述符；表2 的唯一索引 `uk(unit_id,class,method,desc)` 配合裸 INSERT 冲突跳过实现幂等。
- **流式解析 + 分批写库**：`BootJarParser` 以 `UnitSink` 回调逐条 push 方法，`UnitWriter` 累积到 `parse.batch.size` 即写库一次，单元结束 flush 剩余。内存占用为 O(一批方法 + 类名集合)，大项目不会打爆内存。
- **已完成单元跳过内容解析**：`beginUnit` 返回 null 时跳过该单元的 ASM 解析（仍计算 hash 用于判定）。
- **方法注册口径**（过滤在 `BootJarParser.parseClass` 内，按 `ACC_SYNTHETIC` 与方法名判定）：
  - **`<clinit>` 排除**：sandbox 在**类结构收集阶段**即硬编码排除（`ClassStructureImplByAsm$5$1.visitMethod` 中 `StringUtils.equals("<clinit>", name)` 成立时直接 `super.visitMethod`，不收集为 BehaviorStructure）→ 进不了 `signCodes`，织入器永不改写，**无配置开关**。解析器同步排除：收录只会让表2 多出永不命中的行，制造覆盖率盲区。
    > 注意与 `main` 的排除点不同：`main` 在 `UnsupportedMatcher.isJavaMainBehavior`（matching 阶段），`<clinit>` 在 `ClassStructureImplByAsm$5$1`（结构收集阶段）——两者都是硬编码，但从不同入口拦截。
  - **synthetic 方法仅纳入 `lambda$` 前缀**：lambda 体内是用户逻辑；bridge / `access$xxx` 等转发型 synthetic 方法体 1-2 行且行号指向原声明处，hook 会与目标方法重复命中。
  > 排查提醒：确认这类过滤逻辑时**直接读 `BootJarParser` 源码**——关键词检索（`isSynthetic` / `clinit`）在 `Opcodes.ACC_SYNTHETIC` 与字符串常量上可能漏命中。
- **注入阶段的名单过滤**：解析落表2 是全量的，名单只作用于注入阶段。对每个方法按 `"完全限定类名.方法名"` 先经 `inject.include.methods` 选入（名单为空则全部入选），再经 `exclude.methods` 排除，两者都过才注册 watch；过滤后若一个类都不剩，判定为零覆盖，写表4 后 kill。配置写法见 README。

#### 3.1.3 并发解析：两态 + 幂等收敛

多节点同时启动会同时解析同一单元。各节点**各自独立解析**（状态机只有两态，节点之间无协调）：

| 读到的状态 | 语义 | 动作 |
|---|---|---|
| 无记录 | 首次解析 | 登记（裸 INSERT 冲突复用取 id）→ 解析 → 置 completed |
| completed | 已完成 | 跳过复用 |
| 任何其他状态（pending / 历史 failed） | 未完成 | **本节点直接解析**，解析完成置 completed |

正确性完全由表2 保证：唯一索引 `uk(unit_id,class,method,desc)` + 裸 INSERT 冲突跳过（JDBC 标准 SQLState 23 类判定）幂等。多节点解析的是**同一个 sha256 的 jar**（内容字节级相同），解析结果一致，并集即任一节点的结果——重复解析只多花一次 CPU，不产生数据冲突。

**逐行独立提交（autocommit）**：

> 单条 INSERT 的锁只持有到语句结束（毫秒级），单语句事务在结构上不可能形成锁等待环。
> 表2 是靠唯一索引收敛的幂等表，**任何部分写入都是合法中间态**（未完成状态下次启动会重新解析自动补齐），逐行提交即满足正确性要求。
> 其余三张表全部是单语句 autocommit 写入，工程内**不存在多语句事务**。

**SQLState 23 类判定的适用性边界**：`23` 类 = 约束违反（ANSI SQL/JDBC 标准定义），MySQL（23000）/ PostgreSQL（23505）/ SQL Server / DB2 / H2 均适用；Oracle 不守规范（ORA-00001 常映射为 99999）、SQLite 老驱动 SQLState 为 null——这些库上唯一键冲突会被误判为非冲突走硬保护（失败方向安全，但功能不可用）。另：23 类含外键/NOT NULL 违反，但本工具表无外键、插入参数由代码保证非 null，23 类实际只可能来自唯一键冲突。

**单 class 解析失败 = 硬保护**（结果不完整即不放行）：保证"completed 行的 method_count 与表2 实际行数一致"这一观测不变量成立。

表1 只做**登记与观测**：`markCompleted` 以最后完成的节点覆盖（ip/机器名/计数/parsed_at 为观测信息，不参与任何决策）。

---

### 3.2 挂载：时机、串行化、副本清理

#### 3.2.1 同步阻塞在 premain 里

**为什么同步**：premain 阻塞主线程期间，应用启动流程尚未开始，**不存在任何用户代码执行**——挂载必然先于一切业务代码（含 `@PostConstruct` / `CommandLineRunner`），kill 死点不会错过。若异步挂载，启动期执行的代码可能跑在 watch 就绪之前。

**premain 阶段能被 attach 吗**：可以。sandbox.sh 从外部进程 attach 本 JVM，Attach Listener 是独立线程；主线程阻塞在 native 等待（waitFor）不挡 safepoint，retransform 的 VM_Operation 可正常完成。

**失败语义**：超时拆成两阶段独立计时——`parse.timeout.ms`（定位后的登记+解析+落库）与 `mount.timeout.ms`（attach + 模块 inject + watch 注册）。任一阶段超时或异常 → 写表4（DB 不可用则降级本地日志）→ kill 当前进程。表1 **不做回退**：未完成的行保持未完成，下次启动重新解析。DB 挂起时 connectTimeout/socketTimeout（5s/10s）保证 kill 必达。

#### 3.2.2 sandbox.sh 的隐式依赖

`sandbox.sh` 内部用 `SANDBOX_HOME_DIR=${PWD}/..` 定位安装目录——**必须在 sandbox/bin 目录下执行**。解法：`ProcessBuilder.directory(script 父目录)` 显式设置工作目录，不依赖当前 shell 的 CWD。

#### 3.2.3 挂载串行化：同机多进程 attach 的 token 路由错乱

**现象**：同机多进程并发（或秒级相继）挂载同一份 sandbox 安装目录时，会出现 watch **静默丢失**——命令返回成功、日志显示注册成功，但目标进程永远不被注入。

**根因**（见 `sandbox.sh` 与 sandbox-core 字节码）：

```
sandbox.sh：token="$(date | head | cksum | sed 's/ //g')"     ← 精度到秒
attach 后 agent 把 "namespace;token;ip;port" 追加进 ${HOME}/.sandbox.token
client 再 grep "${token}" ... | tail -1 取 ip:port 发命令
```

同一秒内并发 attach 的两个进程会生成**相同 token**，若对方的 append 插在自己「append → grep」之间，`tail -1` 取到的就是别人 JVM 的端口——命令被发到错误 JVM：watch 挂在别人进程里，自己的进程干净且**无任何报错**。

**方案**：用 `flock` 把整段 attach+inject 串行化：

```
flock -w <等待秒> <user.home>/.fault-sandbox-attach.lock bash <sandbox.sh> -p <pid> -d <模块参数>
```

串行后，自己 append 的行必然是 grep 时刻的最后一行，`tail -1` 必定命中自己——**即使两次 attach 落在同一秒、token 完全相同也无妨**，因此不需要额外做跨秒间隔。锁文件置于 `user.home`：token 文件同样在 `user.home`，冲突只可能发生在同用户进程之间，同时规避多用户共享 `/tmp` 的权限问题。依赖 util-linux 的 `flock`，缺失时非 0 退出 → 硬保护 `MOUNT`。

**另一条独立的静默失败路径**：`sandbox.sh` 内部用 `curl -N -s` 发命令，**不校验 HTTP 状态码**——模块命令返回 5xx 时脚本仍 `exit 0`。典型场景：模块 jar 的临时副本被误删 → 模块报 `config.yml not found` → inject 实际失败 → 进程"挂载成功"地空跑、watch 根本没注册。对策：`SandboxMountInvoker` 检测输出中的 Jetty 错误页标识 `Problem accessing` → 硬保护 `MOUNT`。

#### 3.2.4 临时副本清理（TmpReaper）

sandbox 每次挂载通过 `File.createTempFile` 把模块 jar 复制一份到临时目录（`ModuleJarClassLoader.copyToTempFile`，**运行期延迟读取**），副本仅在**模块卸载**或 **JVM 正常退出**时清理。故障注入的每次命中都是 `kill -9`，两条清理路径都走不到 → **副本必然泄漏**（每个约 4.8M；覆盖演练进程反复重启，数百次即可写满小磁盘）。

**方案（agent 内置，部署方零感知）**：挂载完成后先**在 JVM 内同步**快照副本清单，再 spawn 一个脱离会话的清理守护：

1. **快照必须由 JVM 自己完成**：从 `/proc/self/fd` 用 `Files.readSymbolicLink` 逐个解析出本进程持有的副本路径（按 `sandbox_module_jar_` 过滤去重）。
   > 不能把快照交给 shell：shell 启动（fork+exec+bash 初始化约 10~50ms）与"挂载完很快就被注入 kill"存在竞态——JVM 先死则 shell 读 `/proc/<pid>/fd` 得到空列表，清理被静默跳过。而 JVM 内快照发生在 premain 内、premain 返回前，注入 kill 不可能早于它，因此无竞态。
2. 清单作为 **argv 传给 reaper**：该删什么在 spawn 那一刻已固定，与 `/proc` 是否还存在彻底解耦；reaper 只做"等 pid 消亡 + 删参数里的文件 + 自杀"。
3. **pid 复用免疫**：比对 `/proc/<pid>/stat` 的 starttime（进程启动时刻），pid 被内核复用给新进程时 starttime 必然变化 → 立即判定原进程已死。
4. `setsid` 脱离会话；spawn 失败仅记日志——清理属卫生措施，与故障注入的覆盖完整性无关，**不适用硬保护**。

边界：systemd 停服务按 cgroup 连带杀死 reaper 时清理不执行（可由低频 cron 兜底；k8s 每 Pod 的 emptyDir 随 Pod 销毁则天然无此问题）。可选加固：给每个进程独立 `-Djava.io.tmpdir`。

---

### 3.3 注入与判重

#### 3.3.1 未加载类的覆盖：不需要 withLoad

sandbox 1.4.0 的 `EventWatchBuilder` 没有 `withLoad()`。但 watcher 是**常驻 matcher**：激活时 retransform 已加载类，之后每次类加载事件都会对新类做匹配增强。因此"premain 同步挂载 + watcher 常驻"即覆盖全部用户类——包括启动期和运行期才加载的类。

#### 3.3.2 kill 前的判重：轮次 tag + 数据库唯一索引

- **判重键 = 轮次 + 应用（bootJar 部署路径）+ 行 + 线程 + 调用栈 + 第几次故障**：表3 唯一索引
  `uk(tag, boot_jar_hash, class_name, method_name, line_no, thread_name, fault_seq, stack_hash)`。
  同一行在同一线程、经同一调用栈执行时，本轮最多发生 `inject.fault.times` 次故障（未配置时默认 1）；
  该行被 T 个线程、S 种调用栈执行时本轮最多 T × S × N 次。
  - **按线程区分**：同一行被不同线程执行时故障效果可能不同，各线程独立计数。
  - **按调用栈区分**：同一行经不同调用路径执行时故障场景不同，每种调用栈各占一次故障机会。
  - **判重用线程名而非线程 ID**：线程 ID 仅单个 JVM 内唯一且重启后变化，跨进程不可比，无法支撑集群级判重。
  - **线程名需具备稳定业务语义**：默认或动态线程名（如 `Thread-0`、`pool-1-thread-1`）会在重启后变化，使判重键随之变化，故障次数将不再受上限约束。此为使用约定，由使用者保证（见 README）。
  - `boot_jar_hash` = `MD5(bootJar 完整路径)` 前 16 位 hex。**部署路径即应用标识**——同路径的多节点属同一应用集群（集群级只 kill 一个节点）；不同路径视为不同应用，各自独立抢占。
  - `ip` 仅作普通列记录"死在哪个节点"，**不参与判重**。
  - **索引键用 16 位 hash 而非完整路径**：① 表3 唯一键的字节预算紧张（核算见[第 4 章](#4-数据模型)），完整路径最长 512 字符会把预算占满，超限时插入即硬保护；② 默认 collation 不区分大小写，会误判 Linux 上大小写敏感的路径。16 位 hash（64bit）碰撞概率可忽略。
  - **判重键必须包含应用标识**：表1 以 **sha256（内容）** 为单元键，同一 jar 部署在两个不同路径时共用同一 `unit_id`；判重键加入 `boot_jar_hash` 后，两个部署副本各自独立抢占、独立 kill，互不干扰。
- **故障次数计数（模块侧）**：按「行+线程+调用栈」维护已确认的故障次数。
  - 每次执行到即尝试插入 `fault_seq = 已确认次数 + 1`；插入成功与冲突都让计数 +1——冲突意味着该次故障已被他节点/进程抢占，计数据此与数据库状态同步，无需预先查库。
  - 次数用尽后从计数器移出，转入只判存在性的"已用尽"集合，之后命中即返回，不再撞库、不再计数。
  - 进程被 kill 后计数随之消失，新进程从 0 开始，靠插入冲突逐次推进——因此跑满 N 次需跨多个进程生命周期累计执行该行约 `1+2+…+N` 次。
- `beforeLine` 直接裸 INSERT，按 JDBC 标准 SQLState 判定：
  - 成功 = 本节点赢得"该行该线程该次"的故障执行权 → 记录后 `kill -9`；
  - 唯一键冲突（SQLState 23 类）= 该次故障已被他节点/进程触发 → 计数推进后放行继续执行；
  - **其他任何 SQL 错误（截断/非法值等非 23 类）→ 上抛走硬保护 kill**：采用裸 INSERT 配合 SQLState 显式判定，每类错误都有明确归属，任何非冲突错误都会放大为硬保护，不会被静默吞掉。
- **轮次 tag**：操作者给目标应用加 `-Dfault.tag=tagA`。模块 inject 时读取：缺失 → 写表4 + 直接 kill；存在 → 整轮使用该 tag。换新 tag = 所有行重新可注入。
- **原子性**：判重完全依赖数据库唯一索引（裸 INSERT 的冲突判定为单语句原子操作），并发同抢同一「行+线程+调用栈+第几次」仅 1 条成功——不依赖应用内锁，多机多实例天然安全。
- **本地计数的作用**：`counters` / `exhausted` 既减少热路径上的无效撞库，也承担故障次数推进——进程重启后计数归零，靠插入冲突重新与数据库状态同步，因此不依赖本地状态的持久性。

#### 3.3.3 调用栈快照

故障命中时记录触发该次故障的调用栈，用于定位故障发生在哪条调用路径上。

- **以摘要入索引、原文另存文本列**：调用栈原文长度不定，且表3 唯一键的字节预算已接近上限（核算见[第 4 章](#4-数据模型)），
  无法容纳原文。故 `stack_hash`（`MD5` 32 位 hex，128B）入唯一索引，原文 `stack_text` 存 `MEDIUMTEXT` 不入索引。
  两者由同一次取栈产生，可用 `stack_text` 复算核对 `stack_hash`。
- **取栈位于判重之前**：判重键含 `stack_hash`，摘要只能由取栈算出，因此 `beforeLine` 中"未注入类"的判空之后、
  判重之前必须完成一次完整栈遍历。行级回调是热路径，一次栈遍历的开销远高于一次集合查找，
  这是判重粒度细化到调用栈的既定代价。
- **栈不截断**：只裁掉栈顶**连续**的取栈入口（`java.lang.Thread`）、sandbox 织入探针与事件分发帧、本模块监听帧，其后全部保留。
  这些帧属于工具的固定前缀，保留只会增加噪音；业务代码不在这些包名下，裁剪不会误伤。
  帧格式为 `类名.方法名(文件名:行号)`，可区分同一方法内的不同调用点。
- **递归深度会影响故障机会数**：递归方法中的同一行在不同递归深度产生不同的 `stack_hash`，
  该行的故障机会数与递归深度正相关，`counters` / `exhausted` 的规模也随之增长。
  需要收敛时用 `inject.include.methods` / `exclude.methods` 把递归方法排除在注入范围外。
- **只在 kill 前打印调用栈**：本节点赢得抢占时才打印；唯一键冲突放行分支会被反复执行，打印会造成日志膨胀。

#### 3.3.4 织入边界（影响覆盖率口径）

sandbox 的织入有若干**不可注入**的行，覆盖率必须按"可达行"统计而非 javap 行号全集：

- **sandbox 硬编码排除**（`UnsupportedMatcher`）：JVM 入口 `main` 方法（`isJavaMainBehavior`，无开关）、CGLIB/Spring 代理类（4 种后缀，无开关）、`$$Lambda$` 运行时壳类（`isEnableLambda` 可放开）、`$$SANDBOX$` 合成方法、sandbox 自身类、stealth classloader 的类、JDK 核心类（`unsafe.enable` 可放开）、native 方法（`isNativeSupported` 可放开）。
- **JVM 校验器层约束**：构造器 `super()` 之前的行——super() 前 `this` 为 `uninitializedThis`，行级探针需携带 `this`（`BeforeEvent.object`），压栈即 `VerifyError`；ASM AdviceAdapter 因此把探针统一推迟到 super() 之后，super() 前的 LNT 行被静默丢弃。
- **方法体 64KB 限制**：行级插桩使字节码膨胀 2~3 倍，超限 → 整类 `VerifyError` → sandbox 回退原始字节码 → **该类所有行静默不增强**（类正常运行但永不命中）。

---

### 3.4 类加载与依赖隔离

#### 3.4.1 为什么只有 agent 需要 relocate

| | 加载位置 | 与宿主应用的关系 | 是否需要 relocate |
|---|---|---|---|
| agent | **应用的父 loader**（`AppClassLoader`，`-javaagent` 的 jar 被 JVM 追加进 system classpath） | 应用按父优先委托，会**先命中 agent 带来的同名类** → 污染宿主依赖 | **必须** |
| module | sandbox 独立创建的 `ModuleJarClassLoader`（未命中 routing 的类先在本地 jar 中 `findClass`，子优先） | 不在应用的委托链上，应用看不见它 | 不需要 |

relocate 后，agent 的依赖在父 loader 中只以 `cn.chinaclear.fault.shaded.*` 的名字存在；应用请求的仍是 `com.google.common.*` 等原始包名，父 loader 中查不到，于是应用始终加载自己那份依赖，两者互不干扰。

relocate 规则的命名约束见 README「agent fat jar 的 relocate 与命名规范」。

#### 3.4.2 JDBC 驱动的加载方式

驱动类名由 `jdbc.driver` 指定（必填，无内置兜底，加载失败即硬保护）。连接一律由 `JdbcHelper` 自己持有的 `Driver` 实例建立（`driver.connect`），**不经过 `DriverManager`**：

- `DriverManager` 是 JVM 全局单例，按"注册顺序 + 对调用方 classloader 是否可见"挑选驱动。agent premain 注册的 shaded 驱动既排在前面、又经委托链（app classloader 持有 agent fat jar）对模块可见，模块会被它截胡，转而依赖 agent fat jar 的物理文件；该文件按需读类失败会直接打断模块的 JDBC 调用。
- agent 与模块的 common 类相互独立（sandbox 模块类加载器子优先），两者各自持有自己的驱动实例与静态状态，互不干扰。
- `Class.forName` 未显式指定加载器，使用调用类 `JdbcHelper` 的定义类加载器，因此两侧各自在自己的 jar 内解析。

**两侧配置值不同**：agent fat jar 内的依赖已被 relocate，jar 中不存在原包名的驱动类，故 agent 侧 `config.yml` 填 relocate 后的类名、module 侧填驱动的标准类名。

两侧各填自己 jar 内真实存在的类名，由配置显式声明。shadow 的 relocate 只改写类文件（含其中的字符串常量），`config.yml` 属资源文件不受影响，因此驱动名只能由配置直接给出。

#### 3.4.3 不引入日志门面

整个工具不引入 slf4j/logback，使用自写文件日志（`FaultLogger`），避免日志实现污染宿主应用。

---

### 3.5 硬保护

原则：**故障注入工具自身故障不得拖垮目标应用以外的东西，也不能放行未保护的应用**。

- premain 解析/落库/挂载任何异常 → 写表4 + kill；
- 模块侧同样硬保护：注入失败、单类 watch 注册失败（覆盖不完整）、名单过滤后零覆盖、运行期 `beforeLine` 任何异常（含 DB 不可用）→ 写表4 + kill；
- kill 两级兜底：`kill -9` → 未生效则 `Runtime.halt(137)`；
- JDBC 带 connectTimeout=5000 / socketTimeout=10000，保证 DB 挂起时 kill 不被无限拖延；
- 唯一放行例外是 `mount.enabled=false`（纯解析模式，操作者显式选择不注入）。

**表4 写入的两个例外**（物理上无法写入，退化为本地日志，但**仍然 kill**）：

1. `config.yml` 缺失或非法 → `config == null`，连库地址都没有；
2. 表4 写入本身失败（故障库不可达）。

---

### 3.6 可观测性

自写 `FaultLogger`：同步写文件（`logs/fault-agent.log` / `logs/fault-module.log`）+ stdout，时间戳 + 线程名 + 级别，异常带堆栈；日志目录不可写时降级为仅 stdout。

关键分支全覆盖：premain 各阶段、驱动解析结果、单元登记与跳过判定（首次/已完成跳过/未完成重解析）、注入过滤概况、mount 命令与输出、命中与抢占、冲突放行、kill 执行结果与兜底分支。

> 日志路径提示：agent 的 `log.dir` 可由 `-javaagent` 参数覆盖到独立目录；模块的 `log.dir` 来自模块自己的 `config.yml`，相对路径基于目标进程工作目录。

---

## 4. 数据模型

见 `common/src/main/resources/schema.sql`（操作者手动执行）。

| 表 | 语义 | 关键约束 |
|---|---|---|
| `t_jar_record` | 解析单元（两态：completed / 未完成） | `uk(sha256)` 登记（不用于抢占）。单元以**内容** hash 为键：同一 jar 部署在多个路径时共用同一 unit，应用区分由表3 的 `boot_jar_hash` 负责 |
| `t_class_method` | 类-方法明细 | `uk(unit_id,class,method,desc)` 幂等（裸 INSERT 冲突跳过） |
| `t_fault_record` | 故障命中（含轮次 tag、第几次故障、调用栈） | **`uk(tag, boot_jar_hash, class, method, line, thread_name, fault_seq, stack_hash)`** 轮内抢占，粒度为「行+线程+调用栈+第几次」；`ip` 为普通列仅记录死亡节点，不参与判重 |
| `t_error_record` | 工具自身错误 | — |

> 索引长度核算（表3 唯一键，utf8mb4）：tag 64×4 + boot_jar_hash 16×4 + class 256×4 + method 128×4
> + line 4 + thread_name 128×4 + fault_seq 4 + stack_hash 32×4 = **2504B < 3072B**，安全。
> 余量 568B，这也是调用栈必须以摘要入索引、原文只能另存文本列的原因。

---

## 5. 覆盖率统计口径

覆盖率按**可达行**统计：javap 基线行号全集大于运行时可达行，差集归因于三类——未实例化类的构造器行、运行时不可达分支（基线噪声）、sandbox 织入盲区（`main` 方法与构造器 `super()` 前的行，见 [3.3.4](#334-织入边界影响覆盖率口径)）。

表3 的记录数不等于覆盖行数（同一行每「线程+调用栈」组合最多 N 条），覆盖率按 `COUNT(DISTINCT class_name, method_name, line_no)` 统计；按「行+线程+调用栈+第几次」核对时，每个组合应为 N 条且 `fault_seq` 从 1 连续无缺号。同一行出现多条不同 `stack_hash` 的记录，表示该行存在多条调用路径，每条路径各自独立消耗故障机会。
