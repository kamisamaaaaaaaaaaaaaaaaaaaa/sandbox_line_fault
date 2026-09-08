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
- **classes 单元可关闭**（`parse.classes.enabled`，默认 true）：置为 false 时该单元整体跳过——包括它的哈希计算（要遍历整个 classes 目录，不跳过就白算）——应用代码既不落表2 也不注入故障，对应"只对第三方组件做故障演练"的场景。此时单元集合可能为空（白名单也未命中任何 jar 时），直接硬保护 `PARSE`：一个"挂载了但零覆盖"的进程比启动失败更危险，因为它看起来一切正常。
- **行号不入库**：sandbox 的 `beforeLine(advice, lineNum)` 回调自带行号，静态解析只需方法名 + 描述符；表2 的唯一索引 `uk(unit_id,class,method,desc_hash)` 配合裸 INSERT 冲突跳过实现幂等。
- **流式解析 + 分批写库**：`BootJarParser` 以 `UnitSink` 回调逐条 push 方法，`UnitWriter` 累积到 `parse.batch.size` 即写库一次，单元结束 flush 剩余。内存占用为 O(一批方法 + 类名集合)，大项目不会打爆内存。
- **已完成单元跳过内容解析**：`beginUnit` 返回 null 时跳过该单元的 ASM 解析（仍计算 hash 用于判定）。
- **方法注册口径**（过滤在 `BootJarParser.parseClass` 内，按访问标志与方法名判定）：
  - **`<clinit>` 排除**：sandbox 在**类结构收集阶段**即硬编码排除（`ClassStructureImplByAsm$5$1.visitMethod` 中 `StringUtils.equals("<clinit>", name)` 成立时直接 `super.visitMethod`，不收集为 BehaviorStructure）→ 进不了 `signCodes`，织入器永不改写，**无配置开关**。解析器同步排除：收录只会让表2 多出永不命中的行，制造覆盖率盲区。
    > 注意与 `main` 的排除点不同：`main` 在 `UnsupportedMatcher.isJavaMainBehavior`（matching 阶段），`<clinit>` 在 `ClassStructureImplByAsm$5$1`（结构收集阶段）——两者都是硬编码，但从不同入口拦截。
  - **abstract 方法排除**：接口声明的抽象方法、抽象类的 abstract 方法在 class 文件里**没有 Code 属性**。sandbox 的 `EventWeaver` 能匹配到它并构造改写适配器，但无方法体时 `onMethodEnter` / `visitLineNumber` 永远不会被调用，桩落不上去——注册后既不回调也不报错。收录只会虚增覆盖率分母，故解析阶段同步排除。
  - **native 方法排除**：`rewriteNativeMethod` 会去掉 `ACC_NATIVE`、装上方法体并生成 `ACC_PRIVATE|ACC_NATIVE|ACC_FINAL` 代理方法，因此它**确实被织入了**——但只插 `spyMethodOnBefore` / `spyMethodOnReturn` / `spyMethodOnThrows`，**不插 `spyMethodOnLine`**（native 无 Code 属性，也就没有 LineNumberTable，没有行号可报；对比 `rewriteNormalMethod` 重写了 `visitLineNumber`）。本模块只监听 `beforeLine`，故 native 方法注册后同样永不回调，一并排除。
    > 两者失败点不同：abstract 卡在「桩没插上」，native 卡在「桩插了但没有 LINE 事件」。若将来接入 BEFORE/RETURN 事件，需要重新评估 native 的取舍。
  - **synthetic 方法仅纳入 `lambda$` 前缀**：lambda 体内是用户逻辑；bridge / `access$xxx` 等转发型 synthetic 方法体 1-2 行且行号指向原声明处，hook 会与目标方法重复命中。
- **注入阶段的过滤块**（`inject.filters`）：解析落表2 是全量的，过滤只作用于注入阶段。判定方法属于哪个作用范围需要回到表1 取单元的 `unit_type` 与 `source_jar`——表2 只带 `unit_id`，单看方法无法区分它来自应用代码还是哪个第三方 jar，这是模块侧要在 inject 开始时额外查一次单元元信息的原因。
  - 各块**按配置顺序串行作用**：作用范围不覆盖该方法所属单元的块不表态；范围覆盖的块内先 `include` 选入（空 = 全选）再 `exclude` 过滤，**任一块把方法拦下即不注入**。
  - 于是范围互斥的块（`class` 与 `lib`）互不表态、各自管各自；范围重叠的块（`global` 与 `class`）需逐块过关——这就是"被任一块过滤掉就不能注入"的语义，与"取并集""首个命中块生效"都不相同。
  - 单元元信息在 inject 开始时一次查完（单元数为个位数），正则在过滤前一次预编译；过滤是启动期一次性开销，不在 `beforeLine` 热路径上，不引入运行期成本。
  - 过滤后若一个方法都不剩，判定为零覆盖，写表4 后 kill。配置写法见 README。

#### 3.1.3 并发解析：两态 + 幂等收敛

多节点同时启动会同时解析同一单元。各节点**各自独立解析**（状态机只有两态，节点之间无协调）：

| 读到的状态 | 语义 | 动作 |
|---|---|---|
| 无记录 | 首次解析 | 登记（裸 INSERT 冲突复用取 id）→ 解析 → 置 completed |
| completed | 已完成 | 跳过复用 |
| 任何其他状态（pending / 历史 failed） | 未完成 | **本节点直接解析**，解析完成置 completed |

正确性完全由表2 保证：唯一索引 `uk(unit_id,class,method,desc_hash)` + 裸 INSERT 冲突跳过（JDBC 标准 SQLState 23 类判定）幂等。多节点解析的是**同一个 sha256 的 jar**（内容字节级相同），解析结果一致，并集即任一节点的结果——重复解析只多花一次 CPU，不产生数据冲突。

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

#### 3.2.4 临时副本清理

sandbox 每次挂载通过 `File.createTempFile` 把模块 jar 复制一份到临时目录（`ModuleJarClassLoader.copyToTempFile`，**运行期延迟读取**），副本仅在**模块卸载**或 **JVM 正常退出**时清理。故障注入的每次命中都是 `kill -9`，两条清理路径都走不到 → **副本必然泄漏**（每个约 4.8M；覆盖演练进程反复重启，数百次即可写满临时目录）。

两个放大因素（2026-09-07 生产事故确认）：

- **复制的是 `sandbox-module/` 下全部模块 jar**，不止本工具的：生产目录 6 个模块，4 个是其他团队的 `*-jar-with-dependencies.jar`（体积大）——每轮重启产生 6 份副本，写入量由"所有模块 × 重启频率"决定
- **systemd 部署下清理进程本身会被连坐**：`KillMode=mixed` + `Restart=on-failure` 时，服务重启的停止阶段对 cgroup 内除 Main PID 外的所有进程 SIGKILL——agent spawn 的清理守护在轮询间隔（秒级）内即被杀，`rm` 从未执行。生产复现：471 轮泄漏约 2800 份写满 /tmp → `copyToTempFile` 报 `No space left on device` → 模块注册失败 → 挂载 404

**方案（两层）**：

1. **主力：ExecStopPost 清扫（systemd 部署，`add-agent-to-service.sh` 自动注入）**

   ```ini
   ExecStartPre=/bin/mkdir -p tmp
   ExecStart=... -Djava.io.tmpdir=tmp ...
   ExecStopPost=/bin/sh -c 'rm -f tmp/sandbox_module_jar_*.jar'
   ```

   - **独立 tmpdir**（相对 `WorkingDirectory`，路径零硬编码）：副本落进应用独占目录，与共享 /tmp、其他团队的模块写入隔离；目录由 `ExecStartPre` 创建（JVM 不会自建），premain 内另有 `Files.createDirectories` 兜底
   - **ExecStopPost 在 cgroup 清理之后执行**，不受连坐影响——本轮泄漏本轮清扫，覆盖全部退出路径（命中 kill / 运维 restart / 绕过 systemd 直接 kill / 正常停服务），包括"最后一轮"
   - `File.createTempFile` 以进程 cwd 解析相对 tmpdir（cwd 即 `WorkingDirectory`），`/proc/<pid>/fd` 看到的是解析后的绝对路径，TmpReaper 快照不受影响
   - system manager 与 user manager 的 KillMode/cgroup 行为一致（user unit 实测等价）

2. **兜底：TmpReaper（非 systemd 部署）**——挂载完成后**在 JVM 内同步**快照副本清单，spawn 一个脱离会话的清理守护：

   1. **快照必须由 JVM 自己完成**：从 `/proc/self/fd` 用 `Files.readSymbolicLink` 逐个解析出本进程持有的副本路径（按 `sandbox_module_jar_` 过滤去重）。
      > 不能把快照交给 shell：shell 启动（fork+exec+bash 初始化约 10~50ms）与"挂载完很快就被注入 kill"存在竞态——JVM 先死则 shell 读 `/proc/<pid>/fd` 得到空列表，清理被静默跳过。而 JVM 内快照发生在 premain 内、premain 返回前，注入 kill 不可能早于它，因此无竞态。
   2. 清单作为 **argv 传给 reaper**：该删什么在 spawn 那一刻已固定，与 `/proc` 是否还存在彻底解耦；reaper 只做"等 pid 消亡 + 删参数里的文件 + 自杀"。
   3. **pid 复用免疫**：比对 `/proc/<pid>/stat` 的 starttime（进程启动时刻），pid 被内核复用给新进程时 starttime 必然变化 → 立即判定原进程已死。
   4. `setsid` 脱离会话；spawn 失败仅记日志——清理属卫生措施，与故障注入的覆盖完整性无关，**不适用硬保护**。

   局限：systemd 部署下该守护会被连坐杀死（见上），故仅作非 systemd 环境的兜底；k8s 每 Pod 的 tmp 目录随 Pod 销毁天然无此问题。

事故中值得记录的保护行为：/tmp 写满后，mount 静默失败被错误页检测（见 3.2.3）识别，硬保护 kill 没有放行一个"看起来挂载成功、实际零覆盖"的进程——泄漏是缓慢恶化的运维问题，而保护语义始终成立。

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

#### 3.3.3 重载方法与注入粒度

sandbox 的 `onBehavior(String)` **只按方法名匹配**（API 另有 `withParameterTypes(...)` 可精确匹配单个重载，
本工具未使用），因此注册一次即织入该名字下的**全部重载方法**。由此产生两条设计：

- **注入按方法名去重**：`t_class_method` 中同一 `(class_name, method_name)` 有多个重载行（描述符不同），
  对注入而言是冗余的。模块读取时以 `(class_name, method_name)` 为游标做**严格大于**的分页：

  ```sql
  WHERE unit_id IN (...) AND (class_name, method_name) > (?, ?)
  ORDER BY class_name, method_name LIMIT n
  ```

  每个 `(class_name, method_name)` 只出现在第一批，后续同名重载行被自然跳过——
  既避免跨批重复注册 watch，也省去读取冗余行。这些行仍完整保留在表中，供统计与排查使用。
  批内仍按方法名去重（同一批可能含同一方法名的多个重载行）。
- **故障记录携带方法签名**：`t_fault_record.method_desc` 记录命中方法的 ASM 描述符，供排查时区分是哪个重载触发。
  `Behavior` 接口未暴露描述符，由 `getParameterTypes()` + `getReturnType()` 按 JVM 规范还原（与解析侧存储格式一致）。
  该字段**不参与判重**：`(class_name, method_name, line_no)` 在字节码层面已唯一对应一个重载（同一类里两方法的行号表不重叠）。

#### 3.3.4 调用栈快照

故障命中时记录触发该次故障的调用栈，用于定位故障发生在哪条调用路径上。

- **以摘要入索引、原文另存文本列**：调用栈原文长度不定，且 `t_fault_record` 唯一键的字节预算已接近上限（核算见[第 4 章](#4-数据模型)），
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
  需要收敛时用 `inject.filters` 块内的 `exclude` 把递归方法排除在注入范围外。
- **只在 kill 前打印调用栈**：本节点赢得抢占时才打印；唯一键冲突放行分支会被反复执行，打印会造成日志膨胀。

#### 3.3.5 织入边界（影响覆盖率口径）

sandbox 的织入有若干**不可注入**的行，覆盖率必须按"可达行"统计而非 javap 行号全集：

- **sandbox 硬编码排除**（`UnsupportedMatcher`）：JVM 入口 `main` 方法（`isJavaMainBehavior`，无开关）、CGLIB/Spring 代理类（4 种后缀，无开关）、`$$Lambda$` 运行时壳类（`isEnableLambda` 可放开）、`$$SANDBOX$` 合成方法、sandbox 自身类、stealth classloader 的类、JDK 核心类（`unsafe.enable` 可放开）、native 方法（`isNativeSupported` 可放开）。
- **JVM 校验器层约束**：构造器 `super()` 之前的行——super() 前 `this` 为 `uninitializedThis`，行级探针需携带 `this`（`BeforeEvent.object`），压栈即 `VerifyError`；ASM AdviceAdapter 因此把探针统一推迟到 super() 之后，super() 前的 LNT 行被静默丢弃。
- **方法体 64KB 限制**：行级插桩使字节码膨胀 2~3 倍，超限 → 整类 `VerifyError` → sandbox 回退原始字节码 → **该类所有行静默不增强**（类正常运行但永不命中）。

---

### 3.4 类加载与依赖隔离

#### 3.4.1 agent 用隔离 ClassLoader 替代依赖改写

| | 加载位置 | 与宿主应用的关系 | 隔离方式 |
|---|---|---|---|
| agent | **应用的父 loader**（`AppClassLoader`，`-javaagent` 的 jar 被 JVM 追加进 system classpath） | 应用按父优先委托，会**先命中 agent 带来的同名类** → 污染宿主依赖 | 依赖收进嵌套 jar，由壳类自建的 `AgentClassLoader` 独占加载 |
| module | sandbox 独立创建的 `ModuleJarClassLoader`（未命中 routing 的类先在本地 jar 中 `findClass`，子优先） | 不在应用的委托链上，应用看不见它 | 无需处理 |

冲突的物理基础：`-javaagent` 把 agent jar 追加进 AppClassLoader 的搜索路径，而 `-jar` 启动时应用自己的 bootJar 也在其上——应用加载同名类时（无需 agent 先用过该类）委派到 AppClassLoader 就会命中 agent 那份，应用自己 lib 里的类永远加载不到。

**agent 的解法是让 AppClassLoader 的可见面上不存在依赖类**，两个机制配合：

1. **依赖收进嵌套 jar**。外壳 jar 根上只放 premain 壳类（`FaultAgent`、`AgentClassLoader`，JVM 规范要求 premain 类对 system loader 可见），全部业务类与依赖打进外壳内的 `lib/agent-all.jar` entry——jar 套 jar 不是标准 classpath 形态，标准类加载器不递归进嵌套 entry，依赖类对外壳（AppClassLoader）完全不可见。仅自建 loader 而依赖仍平铺在外壳根上是不够的：AppClassLoader 不经过自建 loader 也能直接加载它们。
2. **`AgentClassLoader` 的 parent 取 system loader 的父加载器**（JDK9+ platform / JDK8 ext）。委派链为嵌套 jar 自己 → platform/ext → bootstrap，三层都只有 JDK 模块类，应用 classpath 被整体跳过。不能取 AppClassLoader（隔离的成立与否就押在"应用恰好没有同名类"上）；也不能取 null——JDK9+ 的 `java.sql` / `java.management` 等非 base 模块类由 platform loader 加载，parent=null 只委派到 bootstrap，拿不到这些类（实测在 `SQLException` 上翻车）。

结构示意（`FaultAgent.premain` 仅构造隔离 loader 并反射调用 `AgentBootstrap.run`，跨 loader 只传 JDK 类型；壳阶段失败 `System.err` 留痕后 `halt(137)`，绝不放行"隔离未建立却继续启动"的进程）：

```
fault-agent.jar（外壳）
├── cn/chinaclear/fault/agent/FaultAgent.class          # 壳：premain 入口（AppClassLoader 加载）
├── cn/chinaclear/fault/agent/AgentClassLoader.class    # 壳：隔离 ClassLoader
├── lib/agent-all.jar                                   # 嵌套 core：业务类 + common + 全部依赖（原名）
└── META-INF/MANIFEST.MF
```

隔离 loader 把嵌套 jar 全量读入内存后独立 `defineClass`（内存驻留换取零临时文件、无清理钩子——agent 命中即 `kill -9`，任何临时文件方案都要面对清理路径走不到的泄漏问题）。资源（`config.yml`）由 `findResource` 从内存 map 提供，用带显式 handler 的自定义协议 URL 返回可打开流的结果，不注册全局 `URLStreamHandlerFactory`（避免与应用争抢）。`defineClass` 附带指向嵌套 jar 的 `CodeSource`，日志可打印类来源。

选择隔离 loader 而非依赖改写（shadow relocate）的原因：依赖类名保持原名，agent 与 module 的配置心智统一（`jdbc.driver` 两侧填同一个标准类名）；异常栈里的类名不再被改写得无法阅读；新增依赖不再需要同步维护改写规则。

agent 与 sandbox module 仅通过 DB 表与进程调用交互，无跨 loader 共享类；agent 内新建线程（挂载输出读取等）继承隔离 loader 的 TCCL（`AgentBootstrap.run` 首行显式设置、返回时恢复）。

#### 3.4.2 JDBC 驱动的加载方式

驱动类名由 `jdbc.driver` 指定（必填，无内置兜底，加载失败即硬保护）。连接一律由 `JdbcHelper` 自己持有的 `Driver` 实例建立（`driver.connect`），**不经过 `DriverManager`**：

- `DriverManager` 是 JVM 全局单例，按"注册顺序 + 对调用方 classloader 是否可见"挑选驱动。agent premain 注册的驱动既排在前面、又经委托链对模块可见，模块会被它截胡，转而依赖 agent 的物理文件；该文件按需读类失败会直接打断模块的 JDBC 调用。
- agent 与模块的 common 类相互独立（agent 侧在隔离 loader、模块在 sandbox 子优先 loader），两者各自持有自己的驱动实例与静态状态，互不干扰。
- `Class.forName` 未显式指定加载器，使用调用类 `JdbcHelper` 的定义类加载器，因此两侧各自在自己 loader 内解析。

**两侧配置值一致**：agent 依赖在隔离 loader 内加载、类名保持原名，agent 与 module 侧 `jdbc.driver` 统一填驱动的标准类名。

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

级别阈值过滤（`log.level`，agent / module 各自配置，默认 INFO）：DEBUG < INFO < WARN < ERROR 四级，低于阈值的日志 stdout 与文件都不输出，非法值按配置非法硬保护。阈值在字符串格式化前短路。级别语义：INFO 只保留里程碑（各阶段完成、驱动解析、单元入库、注入完成、故障命中、硬保护），WARN/ERROR 出现即需关注；`debug()` 当前仅用于多节点抢空的冲突放行分支（与冲突次数成正比，INFO 下静默）。全量清单见 README 第 7 章。

关键分支全覆盖：premain 各阶段、驱动解析结果、单元登记与跳过判定（首次/已完成跳过/未完成重解析）、注入过滤概况、mount 命令与输出、命中与抢占、冲突放行、kill 执行结果与兜底分支。

---

## 4. 数据模型

见 `common/src/main/resources/schema.sql`（操作者手动执行）。

| 表 | 语义 | 关键约束 |
|---|---|---|
| `t_jar_record` | 解析单元（两态：completed / 未完成） | `uk(sha256)` 登记（不用于抢占）。单元以**内容** hash 为键：同一 jar 部署在多个路径时共用同一 unit，应用区分由 `t_fault_record` 的 `boot_jar_hash` 负责 |
| `t_class_method` | 类-方法明细 | `uk(unit_id,class,method,desc_hash)` 幂等（裸 INSERT 冲突跳过）。描述符原文 `method_desc TEXT` 完整保留、不入索引；索引键为 `desc_hash`（MD5 前 16 hex）——描述符长度不定，超多参数方法可达 KB 级，入索引既超列宽也超字节预算 |
| `t_fault_record` | 故障命中（含轮次 tag、第几次故障、调用栈、方法签名） | **`uk(tag, boot_jar_hash, class, method, line, thread_name, fault_seq, stack_hash)`** 轮内抢占，粒度为「行+线程+调用栈+第几次」；`method_desc TEXT` 记录命中方法的 ASM 描述符（供区分重载，**不参与判重**——`(class, method, line)` 已能唯一定位一个重载）；`ip` 为普通列仅记录死亡节点 |
| `t_error_record` | 工具自身错误 | `message` / `detail` 为 MEDIUMTEXT：错误消息可能携带完整的问题数据（如完整方法描述符），异常栈可达数百 KB。写入统一在 `ErrorRecordDao` 按列宽兜底，覆盖全部写入路径 |

> 索引长度核算（utf8mb4，上限 3072B）：
> `t_jar_record` 唯一键 = sha256 64×4 = **256B**。
> `t_class_method` 唯一键 = unit_id 8 + class 256×4 + method 128×4 + desc_hash 16×4 = **1608B**。
> `t_fault_record` 唯一键 = tag 64×4 + boot_jar_hash 16×4 + class 256×4 + method 128×4
> + line 4 + thread_name 128×4 + fault_seq 4 + stack_hash 32×4 = **2504B**（余量 568B——调用栈必须以摘要入索引、
> 原文另存文本列的原因；后续任何列进入该索引前须重新核算）。
>
> `t_class_method` 的 `desc_hash` 是必需的：该表唯一键原本就含 `method_desc`，原文改 TEXT 后必须把它移出索引，
> 否则既超出字节预算（描述符 8KB 远超 3072B），超 256 字符的描述符也会触发 Data too long 走硬保护。
> `t_fault_record` 则不需要签名摘要：其唯一键原本不含描述符，且 `(class, method, line)` 在字节码层面
> 天然唯一对应一个重载（同一类里两个方法的行号表不重叠），签名仅为可观测字段。

### 4.1 字段超长治理

列按其语义分三类处理：

| 类型 | 处理方式 | 例子 |
|---|---|---|
| 观测型长文本 | `TEXT` / `MEDIUMTEXT` 完整保留，**不入索引** | `method_desc`、`stack_text`、`t_error_record.message` / `detail` |
| 需参与判重的长文本 | 另加定长 hash 列入索引，原文仍以长文本保留 | `t_class_method.desc_hash`、`boot_jar_hash`、`stack_hash` |
| 标识型字段 | 保留 `VARCHAR` 保证可读可查，长度取工程上限 | `class_name`(256)、`method_name`(128)、`boot_jar`(512)、`tag`(64) |

写入侧**不做列宽预检**：列宽约束交由数据库判定，违反时由 `JdbcHelper` 的行级上下文输出该行完整数据
（类名、方法名、描述符原文与长度、摘要），随异常进入硬保护日志与 `t_error_record`，
避免在业务代码里硬编码列宽而与 `schema.sql` 耦合。

---

## 5. 覆盖率统计口径

覆盖率按**可达行**统计：javap 基线行号全集大于运行时可达行，差集归因于三类——未实例化类的构造器行、运行时不可达分支（基线噪声）、sandbox 织入盲区（`main` 方法与构造器 `super()` 前的行，见 [3.3.5](#335-织入边界影响覆盖率口径)）。

表3 的记录数不等于覆盖行数（同一行每「线程+调用栈」组合最多 N 条），覆盖率按 `COUNT(DISTINCT class_name, method_name, line_no)` 统计；按「行+线程+调用栈+第几次」核对时，每个组合应为 N 条且 `fault_seq` 从 1 连续无缺号。同一行出现多条不同 `stack_hash` 的记录，表示该行存在多条调用路径，每条路径各自独立消耗故障机会。
