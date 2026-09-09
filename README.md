# fault-sandbox 使用手册

> 基于 JVM-Sandbox 的进程级故障注入工具：目标应用启动时自动解析用户类与方法并挂载故障模块，**任意用户代码行执行到时，若该行在该线程本轮的故障次数尚未用尽，则记录故障（机器/类/方法/行/线程/第几次）并 kill 掉当前进程**。
>
> | 文档 | 定位 |
> |---|---|
> | 本文 | **怎么用**：构建、安装、配置、运行、日志、排障 |
> | [DESIGN.md](DESIGN.md) | **为什么**：架构、核心机制的设计取舍 |
> | [TEST_CASES.md](TEST_CASES.md) | 验证记录 |

## 目录

- [1. 概述](#1-概述)
- [2. 编译与构建](#2-编译与构建)
- [3. 快速开始](#3-快速开始)
- [4. 前置条件](#4-前置条件)
- [5. 配置](#5-配置)
- [6. 运行机制](#6-运行机制)
- [7. 日志速查](#7-日志速查)
- [8. 异常处理全景](#8-异常处理全景)
- [9. 限制与边界](#9-限制与边界)
- [10. 卸载](#10-卸载)

---

## 1. 概述

### 1.1 做什么

对一个以 bootJar 发布的 Spring Boot 应用做混沌演练，回答"进程死在哪台机器、哪个 jar、哪个类、哪个方法、哪一行、哪个线程"。四项能力：

| 能力 | 说明 |
|---|---|
| 自动发现 | 不侵入应用代码，启动时自动解析 bootJar 得到全部用户类与方法 |
| 自动挂载 | 无需人工干预，应用启动过程中自动完成 sandbox 模块挂载 |
| 行级注入 | 任意用户代码行执行到时触发 kill；每行每线程每调用栈本轮最多 `inject.fault.times` 次（可配置），同一「行+线程+调用栈+第几次」在集群内最多死一个节点 |
| 硬保护 | 工具自身故障时，宁可杀掉进程也绝不让应用在无保护状态下运行 |

### 1.2 交付物

| 产物 | 部署位置 | 说明 |
|---|---|---|
| `fault-agent-1.0.0.jar` | 任意目录 | premain：定位 → 解析 → 落库 → 挂载 |
| `fault-module-1.0.0.jar` | `<sandbox安装目录>/sandbox-module/` | 模块：读清单 → 注册行级 watch → 命中 kill |
| `common/src/main/resources/schema.sql` | 在数据库手工执行 | 4 张表的建表脚本 |

配置随各自 fat jar 内置（`config.yml`），修改后需重新打包。

---

## 2. 编译与构建

**环境**：JDK 8+ 即可（Gradle Wrapper 已随仓库提供，无需本机安装 Gradle）；编译产物为 Java 8 字节码。

| 模块 | 内容 |
|---|---|
| `common` | 公共代码：解析器、JDBC/DAO、配置、日志、kill 工具、`schema.sql` |
| `fault-agent` | **agent**（premain 定位/解析/落库/挂载），单文件 fat jar：壳类 + 嵌套 core jar（`lib/agent-all.jar`），依赖由壳类的隔离 ClassLoader 独占加载（见 [5.7](#57-agent-隔离类加载嵌套-jar)） |
| `fault-module` | **sandbox 模块**（inject 命令 + 行级监听），运行在 sandbox 独立 classloader，与宿主依赖天然隔离 |

```bash
# Windows（项目根目录）
gradlew.bat :fault-agent:agentShellJar :fault-module:shadowJar

# Linux
./gradlew :fault-agent:agentShellJar :fault-module:shadowJar
```

> agent 的部署产物是 `fault-agent/build/libs/fault-agent-1.0.0.jar`（外壳，由 `agentShellJar` 组装）。
> 被嵌入外壳的 core jar（无壳类、无 Premain-Class，不能直接 `-javaagent`）输出到
> `build/intermediates/agent-core/`，属构建中间产物，不出现在 `build/libs`。

### 依赖仓库配置（内网部署）

默认使用 `mavenCentral()`。插件一律通过 `buildscript` 的 `classpath` 引入（不使用 `plugins {}` DSL）；仓库声明与常见 Gradle 工程一致——插件仓库写在 `buildscript` 块内，普通依赖仓库写在 `repositories` 块内。

部署到内网时，仓库声明共 **3 处**需要改动（均已备好内网私服注释模板，取消注释、填入私服地址即可）：

| 位置 | 作用 |
|---|---|
| `build.gradle`（根）`buildscript.repositories` | shadow 插件的解析仓库 |
| `build.gradle`（根）`allprojects.repositories` | 各模块普通依赖的解析仓库 |
| `stress-app/build.gradle` `buildscript.repositories` | spring-boot 插件的解析仓库（stress-app 为本机验证载荷） |

私服为 http（非 https）时必须加 `allowInsecureProtocol = true`；需要认证时加 `credentials { ... }`。3 处模板内容相同，部署时同步启用。

> `test-app` / `test-lib`（验证用 Spring Boot 应用与白名单 lib）与 `scripts/`（部署辅助脚本）为**本机验证资源，未纳入 git**；`git clone` 后如需跑验证用例，需自行准备或从既有环境获取。`settings.gradle` 仍引用这两个模块，缺失时用上面的命令只构建部署产物即可。

---

## 3. 快速开始

```bash
# 第 1 步：建库建表（仅首次，在数据库服务器上执行）
mysql --host=<数据库地址> --user=root -p < common/src/main/resources/schema.sql

# 第 2 步：上传产物
#   fault-module-1.0.0.jar → <sandbox安装目录>/sandbox-module/
#   fault-agent-1.0.0.jar  → 任意目录（如 /home/lys/）

# 第 3 步：带 agent 启动目标应用（-Dfault.tag 指定本轮演练标识）
java -Dfault.tag=round-001 \
     -javaagent:/home/lys/fault-agent-1.0.0.jar \
     -jar /home/lys/your-app.jar
```

启动后自动完成：解析 bootJar → 结果入库 → 挂载故障模块 → 应用继续启动 → **任一用户代码行执行到时（该行该线程该调用栈本轮故障次数未用尽）进程被 kill -9，故障详情（含调用栈）写入 `t_fault_record`**。

### 3.1 已有库升级

**开发/验证阶段**：直接 drop 四张表后重新执行 `schema.sql` 即可，不必执行任何升级语句。

**已有数据的库**：`schema.sql` 以 `CREATE TABLE IF NOT EXISTS` 编写，对**已存在的表不会做任何修改**。
表结构变更需手工执行变更语句，语句集中附在 `schema.sql` 文件末尾的「已有库升级」段落：

```bash
# 第 1 步：备份待变更的表
mysqldump --host=<数据库地址> --user=root -p fault_sandbox t_fault_record > t_fault_record_bak.sql

# 第 2 步：执行 schema.sql 末尾「已有库升级」段落中对应的 ALTER 语句
mysql --host=<数据库地址> --user=root -p fault_sandbox
```

agent 启动时只校验表与列是否存在，**不会自动建表或加列**；缺列会在启动阶段即硬保护 kill，因此升级必须先于部署新版本 jar 完成。

---

## 4. 前置条件

| 条件 | 说明 |
|---|---|
| 目标应用**必须以 `-jar` 方式启动** | bootJar 路径从启动参数自动定位，`-cp` 起主类的方式不支持 |
| 数据库可达 | 目标机器能连上故障库 |
| JDK | 产物为 Java 8 字节码，运行在 JDK 8 及以上的 JVM |
| JVM-Sandbox 已安装 | `<sandbox安装目录>/bin/sandbox.sh` 存在，模块 jar 已放入 `sandbox-module/` |
| `flock` 命令可用 | 挂载用 `flock` 对同机 attach 做串行化（util-linux 自带，缺失则挂载失败→硬保护） |

---

## 5. 配置

### 5.1 加载规则

- 配置随 fat jar 内置，读 classpath 下的 `config.yml`（YAML，支持嵌套分组，展平为点分键读取），这是配置的**唯一来源**。
- **agentArgs 覆盖写法已不支持**：`-javaagent:fault-agent.jar=键=值,键2=值2` 这种写法传了也会被**忽略**，不会生效、也不报错。配置只能写进 `config.yml`。
- **缺失或非法即硬保护**：`config.yml` 不存在，YAML 语法错误，必填项缺失，或配置项写了却没值，进程都会被 kill，不做"用内置默认值静默降级"。

### 5.2 agent 配置项

| 键 | 必填 | 默认值 | 说明 |
|---|---|---|---|
| `jdbc.url` | 是 | — | 故障库连接串（已含 5s/10s 连接超时，勿随意去掉） |
| `jdbc.username` | 是 | — | 数据库用户 |
| `jdbc.password` | 是 | — | 数据库密码 |
| `jdbc.driver` | 是 | — | **JDBC 驱动的标准类名**（如 `com.mysql.cj.jdbc.Driver`），与 module 侧写法一致。无内置兜底，加载失败即硬保护。见 [5.7](#57-agent-隔离类加载嵌套-jar) |
| `sandbox.home` | `mount.enabled=true` 时必填 | — | sandbox 安装目录（取其 `bin/sandbox.sh`）。缺失在解析开始前即硬保护，不会白跑解析 |
| `parse.classes.enabled` | 否 | **`true`** | 是否解析 `BOOT-INF/classes/`（应用自身代码）。`false` = 只解析 `lib.whitelist` 命中的第三方 jar，应用代码既不解析也不注入故障。见 [5.5](#55-解析范围与注入范围) |
| `lib.whitelist` | 否 | **空列表**（不解析任何 lib） | `BOOT-INF/lib/` 中需要解析的 jar 文件名，正则表达式（对文件名全串匹配），YAML 列表一行一个 |
| `mount.enabled` | 否 | **`true`** | `false` = 纯解析模式（只落库，应用正常启动，不挂载不注入） |
| `parse.timeout.ms` | 否 | **`900000`**（15 分钟） | 解析阶段总预算：登记+解析+落库，超时即硬保护 |
| `mount.timeout.ms` | 否 | **`1200000`**（20 分钟） | 挂载阶段超时，与解析阶段各自独立计时 |
| `parse.batch.size` | 否 | **`2000`** | 解析结果分批写库的批大小（流式解析，方法不驻留内存） |
| `log.dir` | 否 | **`logs`** | 日志目录（相对路径基于目标进程工作目录，也可设绝对路径） |
| `log.level` | 否 | **`INFO`** | 日志级别阈值：`DEBUG` / `INFO` / `WARN` / `ERROR`（忽略大小写），低于阈值的日志 stdout 与文件都不输出；非法值配置非法（硬保护）。全量日志清单见 [7](#7-日志速查) |

### 5.3 module 配置项

| 键 | 必填 | 默认值 | 说明 |
|---|---|---|---|
| `jdbc.url` | 是 | — | 故障库连接串，须与 agent 指向同一库 |
| `jdbc.username` | 是 | — | 数据库用户 |
| `jdbc.password` | 是 | — | 数据库密码 |
| `jdbc.driver` | 是 | — | **JDBC 驱动的标准类名**（如 `com.mysql.cj.jdbc.Driver`），与 agent 侧写法一致。无内置兜底，加载失败即硬保护 |
| `inject.fault.times` | 否 | **`1`** | **每行每线程每调用栈故障次数**（≥1）。同一行在同一线程、经同一调用栈执行时本轮最多发生多少次故障；该行被 T 个线程、S 种调用栈执行时本轮最多 T × S × N 次。填入 <1 的值即硬保护 |
| `inject.batch.size` | 否 | **`5000`** | 分批读取方法清单的批大小（避免大项目一次性读入打爆内存） |
| `inject.filters` | 否 | **空列表**（所有已解析方法都是注入候选） | 注入过滤块：按作用范围（global / class / lib）分块配置选入与排除，按配置顺序串行作用。见 [5.5](#55-解析范围与注入范围) |
| `log.dir` | 否 | **`logs`** | 模块日志目录，与 agent 的 `log.dir` 互不影响 |
| `log.level` | 否 | **`INFO`** | 日志级别阈值，语义与 agent 侧一致。故障命中 `FAULT HIT & PREEMPTED` 为 INFO（永远输出）；多节点抢空的 `already preempted` 为 DEBUG（默认静默） |

> 配置模板中**只保留必填项**，有默认值的配置全部注释并附用法说明，按需取消注释修改。

### 5.4 正则写法

`lib.whitelist` 与 `inject.filters` 下的 `libs` / `include` / `exclude` 规则相同：

- 均为 **Java 正则，全串匹配**；`.` 是通配符，表示字面量的点请写 `\.`（不转义也能匹配，但会放宽，如 `OrderService` 会连 `OrderXService` 一起匹配）。
- 名单一律写成一行一个 `- ` 开头的 YAML 列表。减号与值之间的空格数量、值首尾空白都不影响解析（读取时会 trim）。缩进只要求同层一致，**不可用 Tab**。
- **正则一律用单引号包裹**，如 `- 'cn\.demo\..*'`。原因见下表。
- **非法正则即硬保护**（写表4 后 kill），不做"跳过该条继续跑"的降级：正则写错会让名单部分失效，`exclude` 写错更会造成超范围注入，静默降级的代价是覆盖不完整而不自知。

**正则的三种引号写法**

| 写法 | YAML 对 `\` 的处理 | 结论 |
|---|---|---|
| `- 'cn\.demo\..*'`（单引号） | 原样保留 | **推荐** |
| `- cn\.demo\..*`（不加引号） | 原样保留 | 多数情况能用，但有例外（见下） |
| `- "cn\.demo\..*"`（双引号） | 当作转义符 | **不可用**：`\.` 是非法转义，要么扫描报错，要么吞掉反斜杠、悄悄改掉正则语义 |

不加引号时，正则里出现以下情况会出问题：以 `*`、`&`、`!`、`%`、`@`、`` ` `` 开头（被当别名/锚点/标签/指令）；
含 `: `（冒号+空格，被解析成键值对）；含 ` #`（被当注释截断）；以引号开头。
统一加单引号就不必记这些例外。

### 5.5 解析范围与注入范围

**解析范围**（agent 侧）。解析单元分两类：`BOOT-INF/classes/` 整体一个单元（应用自身代码），
`BOOT-INF/lib/` 中命中 `lib.whitelist` 的每个 jar 各一个单元（第三方依赖）。

- `parse.classes.enabled: false` 时 classes 单元不再解析（连它的哈希计算一并跳过），应用代码既不落表2 也不注入故障——对应"只对第三方组件做故障演练"的场景。
- **该开关与白名单不可同时为空**：若关闭了 classes 解析、`lib.whitelist` 又没匹配到任何 jar，本轮一个解析单元都没有，直接硬保护 `PARSE`。不放行"挂载了却零覆盖"的进程。
- classes 单元关闭后，表1 里历史遗留的 CLASSES 单元行仍然存在，只是不在本轮单元清单里，模块不会注入它。本轮生效的单元清单会打印为 `unit: id=... type=... source=...`。

**注入范围**（module 侧）。解析落表2 是全量的，过滤只作用于注入阶段（注册 watch 前）。
`inject.filters` 下可配置若干个块，**按配置顺序串行作用**：

```yaml
inject:
  filters:
    - scope: class
      include:
        - 'cn\.demo\.service\..*'
      exclude:
        - 'cn\.demo\.service\.Safe\..*'
    - scope: lib
      libs:
        - 'newclear-framework-.*\.jar'
      include:
        - 'com\.newclear\..*'
```

每个块对方法的判定顺序：作用范围不覆盖 → 该块不表态；`include` 非空且未命中 → 拦下；
`exclude` 命中 → 拦下。**任一块把方法拦下即不注入**——不是取并集，也不是首个命中块生效。

**块内字段不配置时**

| 字段 | 不配置时 |
|---|---|
| `libs` | 覆盖全部 lib 单元，不限具体 jar |
| `include` | 全选，该块覆盖范围内的方法全部通过 |
| `exclude` | 不排除任何方法 |
| 三者都不配置 | 该块**放行**其覆盖范围内的全部方法，不是"该块不注入"。若觉得不配置的意图不够显眼，可显式写 `include: ['.*']`——与不配置完全等价，只是更易读 |

| scope | 作用范围 | 范围限定 |
|---|---|---|
| `global` | 全部已解析方法 | — |
| `class` | `BOOT-INF/classes/` 的应用自身代码 | — |
| `lib` | 第三方 jar | `libs`：jar 文件名正则全串匹配（与 `lib.whitelist` 同构） |

由此：范围互斥的块（`class` 与 `lib`）互不表态、各自管各自；范围重叠的块（`global` 与 `class`）需逐块过关。

> **注意**：没有块覆盖的方法等于"没人拦"，会被全部注入。因此只配置一个 `scope: lib` 块时，
> 约束不到应用代码——应用代码仍会全量注入。要同时管住应用代码，需再配一个 `global` 或 `class` 块。

- 方法正则对 `"完全限定类名.方法名"` 全串匹配：注入整类写 `- 'cn\.demo\.OrderService\..*'`，单个方法写 `- 'cn\.demo\.OrderService\.pay'`。
- 注入时按块打印过滤概况：`filters: N block(s), applied in order -> [scope=class libs=0 include=1 exclude=1, ...]`；因过滤整类跳过的数量打印为 `skipped classes (no method kept): N`。
- **过滤后若一个方法都没注册，判定为零覆盖，写表4 后 kill**（与"挂载了却无方法"同等对待）。
- 配置非法即硬保护：`scope` 缺失或不在三类之内、任一正则非法，都写表4 后 kill。`scope` 不是 `lib` 时配的 `libs` 不参与范围判定（仅告警），但其正则仍会做合法性校验——写错同样硬保护，不会因为是冗余配置就放过。

**完整示例**：只解析白名单命中的第三方 jar（不解析应用代码），注入范围仅限 test-lib 中 IdGenerator 类的方法：

```yaml
jdbc:
  url: jdbc:mysql://127.0.0.1:3306/fault_sandbox?useSSL=false&serverTimezone=Asia/Shanghai
  username: root
  password: root123
  driver: com.mysql.cj.jdbc.Driver

sandbox:
  home: /opt/sandbox

parse:
  classes:
    enabled: false

lib:
  whitelist:
    - 'test-lib-.*\.jar'

inject:
  filters:
    - scope: lib
      libs:
        - 'test-lib-.*\.jar'
      include:
        - 'cn\.demo\.IdGenerator\..*'
```

示例中未出现的配置项（`mount.enabled`、`parse.timeout.ms`、`inject.fault.times`、`inject.batch.size`、`log.dir`）均未配置，各自取默认值（见 5.2 / 5.3 表格）。

### 5.6 更换数据库

驱动类名由 `jdbc.driver` 指定，工具不绑定具体数据库。换库需改动四处：

| # | 改动点 | 位置 | 说明 |
|---|---|---|---|
| 1 | `jdbc.driver` | agent 与 module 的 `config.yml` | 目标库驱动类名。**两侧统一填驱动的标准类名**（如 `org.postgresql.Driver`）——agent 依赖在隔离 ClassLoader 内加载、类名保持原名 |
| 2 | `jdbc.url` | agent 与 module 的 `config.yml` | 换成目标库连接串，如 `jdbc:postgresql://host:5432/fault_sandbox` |
| 3 | 建表 DDL | `common/src/main/resources/schema.sql` | 4 张表按目标库方言改写（类型、自增主键、唯一索引） |
| 4 | 驱动依赖 | `common/build.gradle` | 加入目标库驱动依赖；依赖会随 core 打进 agent 嵌套 jar（`lib/agent-all.jar`），由隔离 ClassLoader 加载，无需其他动作 |

MySQL → PostgreSQL 的类型映射（`schema.sql` 为 MySQL 方言）：

| MySQL | PostgreSQL | 涉及对象 |
|---|---|---|
| `BIGINT AUTO_INCREMENT` | `BIGSERIAL` | 4 张表主键，需 `getGeneratedKeys()` 可用 |
| `DATETIME` / `DATETIME DEFAULT CURRENT_TIMESTAMP` | `TIMESTAMP` / `TIMESTAMP DEFAULT CURRENT_TIMESTAMP` | 表1 `parsed_at` / `updated_at`、表3 `occurred_at`、表4 `created_at` |
| `CHAR(n)` | `VARCHAR(n)` | 表1 `sha256`、表3 `boot_jar_hash` / `stack_hash`（PG 的 `CHAR(n)` 会空格填充） |
| `MEDIUMTEXT` | `TEXT` | 表3 `stack_text`（PG 无 MEDIUMTEXT，TEXT 无长度上限） |
| `INT` | `INTEGER` | 表1 `class_count` / `method_count`、表3 `line_no` / `fault_seq` |
| `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4` | 去掉 | PG 的字符集在 `CREATE DATABASE` 时指定 |
| `CREATE DATABASE IF NOT EXISTS` | 需提前建库 | PG 不支持该语法 |
| `UNIQUE KEY uk_xxx (...)` | `CONSTRAINT uk_xxx UNIQUE (...)` | 表2 幂等与表3 抢占均依赖唯一键冲突 |

索引字节预算在 PG 上比 MySQL 宽松：MySQL utf8mb4 按**声明长度 × 4** 预分配索引长度，PG 按**实际存储字节**计算，表3 唯一键在 PG 上仅占百余字节。

需确认目标库满足三条 SQL 兼容性约束：

- **唯一键冲突判定**：工具以 JDBC 标准 `SQLState` 以 `23` 开头（约束违反类）判定唯一键冲突，以此实现表2 幂等收敛与表3 集群级抢占（表3 的粒度为「行+线程+调用栈+第几次故障」）。MySQL 与 PostgreSQL 均返回 23 类；若目标库不属 23 类，需同步调整判定逻辑。
- **自增主键**：4 张表均依赖自增主键与 `getGeneratedKeys()`。
- **单语句提交**：工具不使用任何数据库特定的 upsert 语法（`INSERT IGNORE` / `ON DUPLICATE KEY` 均未使用）。

### 5.7 agent 隔离类加载（嵌套 jar）

agent 的 fat jar 采用「壳 + 嵌套 core」结构，与应用依赖物理隔离，**依赖类名保持原名、不做改写**：

```
fault-agent.jar（外壳）
├── cn/chinaclear/fault/agent/FaultAgent.class          # 壳：premain 入口（AppClassLoader 加载）
├── cn/chinaclear/fault/agent/AgentClassLoader.class    # 壳：隔离 ClassLoader
├── lib/agent-all.jar                                   # 嵌套 core：业务类 + common + 全部依赖
└── META-INF/MANIFEST.MF
```

加载链路：JVM 调用 `FaultAgent.premain`（壳）→ 壳构造 `AgentClassLoader` 把嵌套 jar 全量读入内存 → 反射调用 core 入口 `AgentBootstrap.run`，此后全部逻辑运行在隔离 loader 内。隔离由两个机制共同保证：

1. **依赖收进嵌套 jar**：`lib/agent-all.jar` 在外壳里是一个 entry 而非解包目录，标准类加载器不递归进嵌套 jar，外壳根上又只有壳类——AppClassLoader 物理上看不到任何依赖类。
2. **parent 取 system loader 的父加载器**（JDK9+ 的 platform loader / JDK8 的 ext loader）：委派链为嵌套 jar 自己 → platform/ext → bootstrap，三层都只有 JDK 模块类，应用的 classpath（挂在 App loader 上）被整体跳过。

带来的配置变化：`jdbc.driver` 在 agent 与 module 两侧**统一填驱动的标准类名**，不再需要区分"relocate 后的名字"。

壳阶段失败（loader 初始化/反射调用失败）时 core 尚未跑起、无法写表4，降级为 `System.err` 留痕后立即 `halt(137)`，绝不放行"隔离未建立却继续启动"的进程。设计细节见 DESIGN「类加载与依赖隔离」，验证记录见 TEST_CASES「隔离类加载（A 系列）」。

---

## 6. 运行机制

### 6.1 启动到注入的完整链路

```
应用 JVM 启动
  └─ premain（主线程，应用启动前，阻塞）
       1. 加载配置 → 2. 定位 bootJar → 3. 建表校验
       4. 解析 bootJar（按"解析单元"hash 判重）→ 方法明细落表2
       5. 串行化 attach sandbox → 模块 inject（注册行级 watch）
       6. 任一阶段失败/超时 → 写表4 → kill（硬保护）
  └─ 应用继续启动（此时已处于保护中）
  └─ 用户代码行首次执行 → beforeLine → 抢占表3 → 成功则 kill
```

premain 同步阻塞是刻意的：阻塞期间应用启动流程尚未开始，挂载必然先于一切业务代码（含 `@PostConstruct` / `CommandLineRunner`），kill 死点不会错过。

### 6.2 轮次（tag）与判重

- 每轮演练由 JVM 参数标识：`-Dfault.tag=<轮次标识>`（操作者手动添加）。
- **不带 `-Dfault.tag` 启动**：模块挂载成功后立即 kill，应用不会运行（无轮次标识无法判重，按硬保护处理，表4 留痕）。
- 判重键 = **轮次 + 应用（bootJar 部署路径）+ 行 + 线程 + 调用栈 + 第几次故障**：同一行在同一线程、经同一调用栈执行时本轮最多发生 `inject.fault.times` 次故障（未配置时默认 1；该行被 T 个线程、S 种调用栈执行时最多 T × S × N 次）；换新 tag 后所有行重新可注入。
- **按线程区分**：同一行被不同线程执行时故障效果可能不同，故各线程独立计数。判重用线程名而非线程 ID——线程 ID 仅单 JVM 内唯一且重启后变化，跨进程不可比。
- **按调用栈区分**：同一行经不同调用路径执行时故障场景不同，故每种调用栈各占一次故障机会。调用栈原文长度不定，判重用其 MD5 摘要 `stack_hash`，原文 `stack_text` 随记录一同落库。
- 计数推进：模块按「行+线程+调用栈」维护已确认的故障次数，每次执行到就尝试插入 `fault_seq = 次数+1`；插入成功即注入故障，冲突表示该次已被他节点/进程抢占——两种情况计数都 +1，据此与数据库状态同步。次数用尽后移出计数器，之后不再撞库。
- 集群语义：同一「行 + 线程 + 调用栈 + 第几次」组合**最多导致集群内一个节点死亡**（由数据库唯一索引保证）。

### 6.3 命中与 kill

行执行到时，模块向表3 裸 INSERT 抢占（携带该行该线程该调用栈的第几次故障 `fault_seq`，以及调用栈原文与摘要）：成功 = 赢得该次故障执行权，**打印完整调用栈后** `kill -9`；唯一键冲突 = 该次故障已被他节点/进程触发，计数推进后放行继续执行（该分支不打印调用栈，避免热路径日志膨胀）。

调用栈用于定位故障发生在哪条调用路径上：栈顶会裁掉取栈入口（`java.lang.Thread`）、sandbox 织入探针与事件分发帧、模块自身帧，只保留业务帧，帧格式为 `类名.方法名(文件名:行号)`，不做深度截断。判重键依赖调用栈摘要，摘要只能由取栈算出，因此 `beforeLine` 每次回调都会做一次完整栈遍历——行级回调是热路径，这会让目标应用明显变慢，是判重粒度细化到调用栈的既定代价；追求执行速度时用 `inject.filters` 缩小注入范围。

### 6.4 临时副本清理

sandbox 每次挂载会把 `sandbox-module/` 目录下**全部**模块 jar 复制一份到临时目录并**运行期延迟读取**，副本仅在模块卸载或 JVM 正常退出时清理；而故障注入每次命中都是 `kill -9`，两条清理路径都走不到 → 副本必然泄漏（含其他团队的模块副本，总量随目录内 jar 总大小增长）。

清理方式按部署形态区分：

- **systemd 部署（`add-agent-to-service.sh` 接入）**：脚本会自动向 service 注入三行配置——`ExecStartPre` 创建独立 tmpdir、`ExecStart` 加 `-Djava.io.tmpdir=<WorkingDirectory>/tmp`（副本落进应用独占目录，不写共享 /tmp）、`ExecStopPost` 在停止阶段清扫副本。systemd 重启的停止阶段会连坐杀死 agent 的后台清理进程（生产已复现），**清扫必须由 ExecStopPost 在停止阶段完成**。
- **非 systemd 部署（脚本直接启动）**：agent 内置的清理守护兜底——挂载完成后在 JVM 内同步快照副本清单，spawn 脱离会话的清理进程，等本进程消亡后删除清单中的文件并自杀。

排障提示：若挂载阶段出现 `No space left on device` 或挂载 404，先检查临时目录占用：`du -sh /tmp/sandbox_module_jar_* 2>/dev/null | sort -h | tail`。

---

## 7. 日志速查

日志位于 `<log.dir>/fault-agent.log` 与 `<log.dir>/fault-module.log`。

> 注意：agent 与模块的 `log.dir` / `log.level` 各自来自自己的 `config.yml`，相对路径基于目标进程工作目录。同一台机器上多个进程若共用工作目录，模块日志会写到同一个文件。

级别阈值由 `log.level` 控制（agent / module 各自配置，默认 `INFO`）：低于阈值的日志 stdout 与文件都不输出。
INFO 覆盖各阶段里程碑与注入过程概况；WARN / ERROR 出现即需要关注；
故障命中 `FAULT HIT & PREEMPTED` 为 INFO（kill 前最后一条，永远输出）。
当前唯一的 DEBUG 日志是 module 侧的多节点冲突放行（`already preempted`，数量与集群冲突次数成正比，默认静默，排查多节点问题时临时调 DEBUG）。

### 7.1 agent 日志

**INFO（里程碑）**

| 日志 | 含义 |
|---|---|
| `log file: <路径> (level=...)` | 日志初始化完成（含生效级别） |
| `premain start: pid=... parseTimeout=... mountTimeout=...` | agent 开始工作（两阶段超时各自独立计时） |
| `bootJar located: /path/app.jar` | 成功定位 bootJar |
| `jdbc driver resolved: <类名> -> loader=... codeSource=...` | 驱动加载成功（含类来源，用于排查驱动来自哪个 jar） |
| `schema check OK: all 4 tables exist` | 建表校验通过 |
| `parse units: count=N ids=[...]（classes=...）` | 本轮解析单元清单 |
| `unit stored: type=CLASSES source=... unitId=N classes=A methods=B` | 该解析单元解析完成入库 |
| `premain completed: mount OK, release application startup` | 应用开始启动（此刻起已处于保护中） |
| `mount.enabled=false -> parse-only mode ...` | 纯解析模式放行（不挂载不注入） |
| `unit already completed, skip: ... unitId=N` | 该单元此前已解析，跳过 |
| `mount cmd: [flock, -w, N, <锁文件>, bash, ..., -d, fault-module/inject?id=...]` | 正在执行挂载命令（完整命令行） |
| `sandbox.sh exit=N output: <全文>` | sandbox.sh 退出码与完整输出（挂载排障用） |
| `tmp reaper spawned: watching pid=... files=[...]` | 临时副本清理守护已启动（等本进程消亡后删除 sandbox 模块临时副本） |
| `tmp reaper skipped: no sandbox module temp copy held by pid=...` | 本进程未持有模块临时副本，无需清理守护 |

**WARN**

| 日志 | 含义 |
|---|---|
| `read sun.java.command failed` / `read /proc/self/cmdline failed` / `read jvm input arguments failed` | bootJar 定位的降级探测路径某一步失败（会继续尝试下一步） |
| `bootJar path not exists: ...` | 探测到的路径不存在（继续尝试其他方式） |
| `bootJar not located: application must be started with -jar` | 所有方式都未定位到（随后硬保护） |
| `log dir not writable (...), fallback to stdout only` | 日志目录不可写，仅剩 stdout |

**ERROR（出现即按策略 kill 或记录表4）**

| 日志 | 含义 | 处理 |
|---|---|---|
| `HARD PROTECT: phase=PARSE, msg=bootJar not located` | 未以 `-jar` 方式启动 | 改为 `java -javaagent:... -jar app.jar` 启动 |
| `HARD PROTECT: phase=PARSE, ... 该开关与白名单不可同时为空` | `parse.classes.enabled=false` 且白名单没匹配到 jar，零解析单元 | 打开 classes 解析或修白名单 |
| `HARD PROTECT: phase=PARSE, ... invalid config.yml / required config missing` | 配置非法 / 必填项缺失（含 `log.level` 非法、正则非法、留空） | 修正 `config.yml` 后重启（痕迹在 stdout/app.log，因配置不可用写不了 `logs/`） |
| `HARD PROTECT: phase=DB, msg=schema check failed ...` | 库表未建/不可达 | 先执行 `schema.sql`；检查 `jdbc.*` 与网络 |
| `HARD PROTECT: phase=DB, ... Communications link failure` | 数据库连不上 | 检查数据库存活、`jdbc.url`、防火墙 |
| `HARD PROTECT: phase=MOUNT, type=TIMEOUT, msg=mount wait timeout（含 flock 串行化锁等待）` | 挂载超时（含等不到 attach 串行锁） | 检查 sandbox 安装与 `sandbox.home`；同机进程多时锁等待会拉长，适当调大 `mount.timeout.ms` |
| `HARD PROTECT: phase=MOUNT, ... mount failed（flock -w Ns 等待串行化锁超时/脚本非 0 退出）exit code=1` | 挂载命令失败 | 开 `log.level=DEBUG` 看 `mount cmd` 下方的输出内容定位（权限/模块 jar 缺失/flock 缺失等） |
| `config unavailable (missing/invalid), skip DB records (local log only)` | 配置不可用，表4 写不了，仅本地留痕 | 修正 `config.yml` |
| `write t_error_record failed, fallback to local log only` | 表4 写失败（库不可达），退化为本地日志 | 恢复数据库后重启 |
| `tmp reaper spawn failed (best effort, cleanup hygiene only)` | 临时副本托管进程启动失败（仅影响清理卫生，不影响注入） | 忽略或检查系统环境 |

### 7.2 module 日志

**INFO（里程碑）**

| 日志 | 含义 |
|---|---|
| `bootJar resolved: <路径> (hash=...)` | 挂载命令参数定位 bootJar 成功 |
| `inject requested, unitIds=... tag=... batchSize=...` | 收到挂载命令，开始注入流程 |
| `filters: N block(s), applied in order -> [scope=... libs=N include=N exclude=N, ...]` | 过滤块概况（`N block(s)` = 0 表示无过滤块，所有方法都是候选） |
| `unit: id=... type=... source=...` | 本轮生效的解析单元明细 |
| `batch registered: scanned=M rows, classes=C, injected=N methods, cursor=类名#方法名` | **每批**注入情况：`M` 本批扫过的 `t_class_method` 行数、`C` 本批注册的类数（Class 级）、`N` 本批实际注入的方法数（方法级，一个方法名覆盖其全部重载）、`cursor` 本批断点（各批游标严格递增即说明无重复注册） |
| `no method kept after include/exclude filtering, skip class: ...` | 该类的方法全部被名单过滤掉，整类跳过（**每个类一条**，大项目较多） |
| `skipped classes (no method kept): N` | 因名单过滤而整类跳过的类数汇总 |
| `inject done: injected=N methods, scanned=S rows, tag=...` | 注入完成：`N` **注入故障总数**（方法名数）、`S` 累计扫过的行数。`S > N` 说明存在同名重载行被去重（同一方法名只注入一次） |
| `jdbc driver resolved: <类名> -> loader=... codeSource=...` | 驱动加载成功（module 侧标准类名） |
| `FAULT HIT & PREEMPTED: tag=... unitId=... class=... method=... line=... seq=k/N thread=... stackHash=... machine=...` 换行接 `call stack (N frames):` 与逐帧调用栈 | **故障命中（kill 前最后一条）**：该行该线程该调用栈本轮第 k 次故障（上限 N），调用栈已打印并随记录入库，进程即将被 kill。多节点并发时仅赢得抢占的节点打印 |

**DEBUG（过程明细）**

| 日志 | 含义 |
|---|---|
| `fault seq already preempted in this round (tag=...), release execution: class#method#line#thread#stackHash seq=k` | 该行该线程该调用栈本轮的第 k 次故障已被他节点/进程触发，本节点放行（**多节点并发时与冲突次数成正比**，这也是 module 侧唯一的 DEBUG 日志） |

**WARN / ERROR（出现即需要关注）**

| 级别 | 日志 | 含义 | 处理 |
|---|---|---|---|
| ERROR | `jvm property 'fault.tag' missing -> kill process per policy` | 启动时没加 `-Dfault.tag` | 启动命令补上 `-Dfault.tag=<轮次>` |
| ERROR | `no methods found for unitIds=... -> kill per policy` | 解析结果里一个方法都没有 | 检查解析是否完成（表2 是否有数据） |
| ERROR | `no class registered after include/exclude filtering ...` | 名单与解析结果无交集，零覆盖 | 检查 `inject.filters` 各块的 scope 与正则 |
| ERROR | `register watch failed for class=...` | 该类 watch 注册失败（写表4 后按策略 kill） | 看异常栈定位 |
| ERROR | `inject failed, kill process per policy` | 注入流程异常 | 看异常栈定位 |
| ERROR | `beforeLine handling failed, kill process per policy` | 行级回调异常（含 DB 不可用），按硬保护 kill | 看异常栈；检查数据库 |
| ERROR | `kill not effective, rollback fault record and release: ...` | kill 手段未生效，回滚故障记录并放行 | 检查进程环境（罕见） |
| WARN / ERROR | `write t_error_record failed (local log only)` | 表4 写失败（库不可达），仅本地留痕 | 恢复数据库 |
| WARN | `decode bootJar param failed: ...` | 挂载命令参数解码失败 | 检查挂载命令与 agent 版本是否配套 |

另有一条壳阶段失败的 ERROR 只出现在 stdout（此时日志文件尚未建立）：

| 日志 | 含义 | 处理 |
|---|---|---|
| `[fault-agent] HARD PROTECT: agent bootstrap failed: ...` | 隔离 ClassLoader 初始化/反射调用失败，core 未跑起 | 检查 agent jar 完整性（是否被截断/改坏） |

---

## 8. 异常处理全景

**总原则**：除 `mount.enabled=false`（纯解析模式）外，**任何阶段异常都不放行**——尽力写表4 后 kill 当前进程。

**表4 写入的两个例外**（物理上无法写入，退化为本地日志，但**仍然 kill**）：

1. `config.yml` 缺失或非法 → 连库地址都没有，无法写表4；
2. 表4 写入本身失败（故障库不可达）。

### 8.1 A 组：agent 侧 premain（按启动顺序）

| # | 阶段 | 异常场景 | 处理 | 表1 影响 | 进程 |
|---|---|---|---|---|---|
| A0 | 日志初始化 | 目录不可写 | 降级仅 stdout，**不中断** | — | 继续 |
| A1 | 配置加载 | `config.yml` 缺失 / 非法 / 必填项缺失 | 硬保护（无库可写 → 本地日志） | 无 | kill |
| A2 | bootJar 定位 | 多级兜底全部失败 | 硬保护 `PARSE` | 无 | kill |
| A3 | 建表校验 | 库连不上 / 缺表 | 硬保护 `DB` | 无 | kill |
| A4 | 解析 bootJar | zip 打不开/损坏；**单个 class ASM 失败** | 硬保护 `PARSE`（消息含失败 class 名） | 无 | kill |
| A5 | 表1 登记 | 唯一键冲突但查不到行 / 非冲突 SQL 错误 | 硬保护 `DB` | 无 | kill |
| A5 | 状态=completed | 该单元已解析过 | 跳过复用 | 不变 | 继续 |
| A5 | 状态=未完成 | 首次解析，或上次中断，或他节点正在解析 | **本节点直接解析**（无抢占、无等待，表2 幂等收敛） | →completed | 继续 |
| A5 | 任一单元前 | 超过 `parse.timeout.ms` | 硬保护 `PARSE/TIMEOUT`；未完成单元**保持未完成**，下次启动重新解析 | 保持 | kill |
| A6 | 表2 落库 | 唯一键冲突（多节点并发同单元） | **逐行独立提交，冲突行跳过** | 收敛为并集 | 继续 |
| A6 | 表2 落库 | 非冲突 SQL 错误（截断/非法值） | 硬保护 `DB`（携带单元 id）；已提交行保留为合法中间态 | 保持未完成 | kill |
| A7 | 挂载 | 剩余时间 ≤ 0 / 等待挂载（含 flock 串行锁）超时 | `destroyForcibly` + 硬保护 `MOUNT/TIMEOUT` | **不动** | kill |
| A7 | 挂载 | 非 0 退出码 / 脚本不存在 / `flock` 缺失 / 输出含 Jetty 错误页 | 硬保护 `MOUNT` | **不动** | kill |
| A8 | 硬保护收尾 | 表4 写失败 | 仅本地日志 | — | 仍 kill |
| A8 | 硬保护收尾 | `kill -9` 未生效 | `Runtime.halt(137)` 兜底 | — | 终止 |

> **A7 挂载为什么要串行化（同机多进程必读）**
> `sandbox.sh` 用 `token="$(date | head | cksum)"` 生成路由 token（**精度到秒**），attach 后把
> `namespace;token;ip;port` 追加到 `${HOME}/.sandbox.token`，client 再 `grep token | tail -1` 取端口发命令。
> 同一秒内并发 attach 的两个进程会生成**相同 token**，grep 命中多行、`tail -1` 取到**别人 JVM 的端口**——
> 命令发到错误 JVM，表现为 watch 静默丢失（命令返回成功，但目标进程永远不被注入）。
> 因此 agent 用 `flock <user.home>/.fault-sandbox-attach.lock` 把整段 attach+inject 串行化：
> 串行后自己 append 的那行必然是 grep 时刻的最后一行，`tail -1` 必定命中自己，
> **即使两次 attach 落在同一秒、token 完全相同也无妨**。
> 代价：同机 N 个进程同时启动时挂载要排队，总耗时约等于各次耗时之和。

### 8.2 B 组：module 侧 inject 命令

| # | 场景 | 处理 | 进程 |
|---|---|---|---|
| B1 | 缺 `-Dfault.tag` | 表4（`phase=MOUNT`，尽力）→ kill | kill |
| B2 | `id` 参数空/非法 | 表4（`phase=INJECT`，尽力）→ kill | kill |
| B3 | 重复 inject（同 unitIds 已注入） | 跳过，防重复注册 watch | 继续 |
| B4 | 读表2 / 建连接失败 | 表4（尽力）→ kill | kill |
| B5 | 单类 watch 注册失败 | 表4 留痕 → 抛出，外层统一 kill（覆盖不完整即不放行） | kill |
| B6 | 全部批次读完 `totalMethods == 0` | 表4 + kill（挂了却没方法 = 绝不放行） | kill |
| B7 | 经 include/exclude 过滤后一个类都没注册 | 表4 + kill（零覆盖即不放行） | kill |

### 8.3 C 组：运行期 beforeLine（每行回调）

| # | 场景 | 处理 | 业务方法 |
|---|---|---|---|
| C1 | 类名不在本批映射 | 直接返回 | 正常执行 |
| C2 | 该行该线程本轮故障次数已用尽（已用尽集合命中） | 直接返回（不撞库、不计数） | 正常执行 |
| C3 | 裸 INSERT 成功 = 赢得该行该线程该调用栈本轮第 `fault_seq` 次的故障执行权 | 打印调用栈 → `kill -9` 自己 | **进程终止** |
| C4 | `kill` 命令未生效 | 表4 留痕 + **回滚删除表3 该条记录**（按完整判重键，防脏数据永久阻塞该次故障）+ 计数回退 | 放行继续 |
| C5 | 唯一键冲突（SQLState 23 类）= 该次故障已被他节点/进程触发；**其他 SQL 错误 → 硬保护** | 计数 +1 | 放行继续（仅冲突） |
| C6 | 任何异常（含 DB 不可用） | 表4（尽力）→ kill；kill 未生效则 `halt(137)` | **进程终止** |

### 8.4 D 组：状态影响矩阵

| 结果 | 表1 | 表3 | 表4 | 进程 |
|---|---|---|---|---|
| 解析成功 | completed | — | — | 继续 |
| 解析中途失败/超时 | **保持未完成**（不回退） | — | 有 | kill |
| 定位/建表/配置失败 | 无记录 | — | 有 | kill |
| 挂载失败/超时 | **保持 completed** | — | 有 | kill |
| 命中故障 | — | 1 行（每行每线程每调用栈最多 N 行；唯一索引保证每个「行+线程+调用栈+第几次」集群唯一） | — | kill |
| 同轮的该次故障已被他节点命中 | — | 无（冲突忽略） | — | 放行（计数推进） |

---

## 9. 限制与边界

| 边界 | 原因 |
|---|---|
| `<clinit>`（静态初始化）不注入 | sandbox 在类结构收集阶段即硬编码排除（无配置开关）；解析器同步排除，收录只会让表2 多出永不命中的行 |
| abstract 方法不注入 | 接口声明的抽象方法与抽象类的 abstract 方法没有 Code 属性，桩无处可插，注册后永不回调；解析阶段即排除 |
| native 方法不注入 | sandbox 虽会去掉 native 并生成代理方法完成织入，但只插 BEFORE/RETURN/THROWS、不插 LINE（native 无 Code 属性也就没有行号表），本模块只监听 `beforeLine`，故同样永不回调；解析阶段即排除 |
| bridge / `access$xxx` synthetic 方法不注入 | 转发型 synthetic 的行号表指向类声明行，收录注册后会产生落在类声明行上的命中记录（非业务行，污染覆盖率先行口径）；仅保留 `lambda$` 前缀 |
| lambda（`lambda$xxx`）会注入 | lambda 体内是用户逻辑；lambda 体被 javac 抽为原类的私有合成方法，有字节码有 LNT |
| `$$Lambda$` 运行时壳类不注入 | JVM 现场生成的转发壳，无 LNT、名字带随机序号、无业务逻辑 |
| JVM 入口 `main` 方法不注入 | sandbox 硬编码跳过（无配置开关）。这对 premain 模式是保护：main 在挂载完成后才执行，若可注入则首个进程会在 main 首行被 kill，应用永远无法启动 |
| 构造器 `super()` 之前的行不触发 | JVM 校验器约束：super() 前 `this` 为 `uninitializedThis`，行级探针需携带 `this`，压栈即 `VerifyError`；ASM 把探针推迟到 super() 之后，super() 前的 LNT 行被静默丢弃。静态方法首行不受此约束 |
| 未执行的代码不出现在覆盖记录中 | 覆盖（即故障）只在代码真正执行时产生：未实例化类的构造器、逻辑不可达的分支等运行时不可达的行不会触发，覆盖率应按"可达行"统计 |
| CGLIB/Spring 代理类不注入 | sandbox 排除 `$$EnhancerBySpringCGLIB$$` 等运行时生成类名；业务方法体在原始类，原始类正常织入 |
| JDK 核心类不注入 | sandbox 对 `loader==null` 的类默认排除（`unsafe.enable` 可放开） |
| 超过 64KB 的方法插桩后整体失效 | 行级插桩使方法字节码膨胀 2~3 倍，超限 → 整类 `VerifyError` → sandbox 回退原始字节码 → 该类所有行静默不增强。排查：开 sandbox 的 dumpClass 或查 transform 失败记录 |
| 线程名需具备稳定业务语义 | 判重键含线程名。若应用使用默认或动态线程名（如 `Thread-0`、`pool-1-thread-1`），重启后线程名变化会使判重键随之变化，故障次数将不再受 `inject.fault.times` 限制 |
| 必须以 `-jar` 启动 | bootJar 路径从启动参数定位 |

---

## 10. 卸载

演练结束：从启动命令移除 `-javaagent` 参数与 `-Dfault.tag`，重启应用即恢复原状（模块 jar 可留在 sandbox-module 目录，不影响未挂载的进程）。
