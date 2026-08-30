# fault-sandbox：基于 JVM-Sandbox 的进程级故障注入工具

目标 Spring Boot 应用（bootJar）以 `-javaagent` 启动时自动完成：**解析用户类与方法 → 落 MySQL → 自动挂载 sandbox 模块 → 行级 watch 注入 kill 故障**。
硬保护原则：目标进程要么处于 watch 保护之下，要么不存在。

## 产物

| 产物 | 角色 |
|---|---|
| `fault-agent/build/libs/fault-agent-1.0.0.jar` | premain agent |
| `fault-module/build/libs/fault-module-1.0.0.jar` | sandbox 模块（放 `sandbox-modules/`） |
| `test-app/build/libs/test-app.jar` | 验证用 Spring Boot 应用 |
| `common/src/main/resources/schema.sql` | 建库建表脚本（**手动执行一次**） |

## 工作流程

1. 目标应用启动：`java -javaagent:fault-agent.jar -jar app.jar`
2. premain 定位 bootJar（`-jar` 参数 → agentArgs `bootJar=` → 配置 `bootJar.path`）
3. 逐解析单元算 SHA-256（`BOOT-INF/classes/` 聚合 + lib 白名单 jar 字节流）
4. 查表1 状态机去重（completed 跳过 / failed 或本机中断重解析 / 异机孤儿超时抢占）
5. ASM 解析全部用户类方法（方法名+描述符），`INSERT IGNORE` 幂等落表2，表1 置 completed
6. premain 内同步执行 `sandbox.sh -p <pid> -d "fault-module/inject?id=<表1主键列表>"` 并阻塞等待
7. 模块从表2 拉方法，对每个类注册 `onWatching().withLine()` 行级 watch
8. 任意被 hook 的行首次执行到：INSERT 表3 抢占（唯一索引防多节点重复）→ 成功则记录（机器/ip/类/方法/行/线程）并 `kill -9` 当前进程；DuplicateKey 则放行
9. premain 全程受 `premain.timeout.ms` 总预算约束：超时/异常 → 表1 置 failed + 写表4 → kill 当前进程

## 配置

两个模块各自内置 `config.properties`（fat jar 根）。agent 侧支持 agentArgs 覆盖：`-javaagent:fault-agent.jar=k=v,k2=v2`。

### fault-agent

| 键 | 默认值 | 说明 |
|---|---|---|
| `agent.enabled` | `true` | 唯一放行开关：false 跳过全流程 |
| `jdbc.url` | `jdbc:mysql://127.0.0.1:3306/fault_sandbox?...` | 故障库连接 |
| `jdbc.username` | `root` | |
| `jdbc.password` | `root123` | |
| `lib.whitelist` | 空 | BOOT-INF/lib 需解析的 jar 名（精确/前缀，逗号分隔） |
| `sandbox.sh.path` | `/home/lys2/sandbox/bin/sandbox.sh` | 挂载命令路径 |
| `premain.timeout.ms` | `600000` | premain 全程总预算 |
| `bootJar.path` | 空 | 非 `-jar` 启动时的兜底路径 |
| `orphan.threshold.minutes` | `10` | 异机 pending 孤儿判定阈值 |

### fault-module

`jdbc.url` / `jdbc.username` / `jdbc.password`（模块侧连库读写 4 张表）。

## 数据表（MySQL 库 fault_sandbox，手动建表）

**表结构由操作者手动执行 `common/src/main/resources/schema.sql` 创建**（`mysql --host=<host> --user=root -p < schema.sql`）。agent 启动时只校验 4 张表是否存在，缺表会明确报错并走硬保护 kill，**不会自动建库建表**。

| 表 | 用途 | 关键约束 |
|---|---|---|
| `t_jar_record` | 解析单元记录（状态机：pending/completed/failed） | `uk(sha256)` 并发防重 |
| `t_class_method` | 类-方法解析结果 | `uk(unit_id,class,method,desc)` INSERT IGNORE 幂等 |
| `t_fault_record` | 故障命中记录（含轮次 tag） | `uk(unit_id,class,method,line,tag)` 轮内集群级抢占 |
| `t_error_record` | agent 自身错误（写入后进程被 kill） | — |

## 构建与部署

```powershell
# Windows 构建
.\gradlew.bat :fault-agent:shadowJar :fault-module:shadowJar :test-app:bootJar :test-lib:jar
# 部署（scripts/deploy-to-linux.ps1）
#   fault-module jar → /home/lys2/sandbox/sandbox-modules/
#   fault-agent jar + test-app.jar → 目标机
```

```bash
# Linux 启动目标应用（自动解析+自动挂载）
java -javaagent:fault-agent.jar=lib.whitelist=fault-test-lib -jar test-app.jar
# 手动挂载（调试）
/home/lys2/sandbox/bin/sandbox.sh -p <pid> -d "fault-module/inject?id=1,2,3"
```

## 注意事项

- agent fat jar 内 mysql/asm/protobuf 已 relocate（`cn.chinaclear.fault.shaded.*`），不与应用 classpath 冲突
- 日志：工作目录 `logs/fault-agent.log`、`logs/fault-module.log`
- 表2 只存方法名+描述符不存行号：`beforeLine` 回调自带行号，天然 hook 方法所有行
- 测试用例：`tests/TEST_CASES.md`

## 限制与边界（设计取舍，均为有意行为）

| 边界 | 说明 | 原因 |
|---|---|---|
| `<clinit>` 不注入 | 静态初始化块/字段初始化不做行级 hook | clinit 运行在 JVM 类初始化锁内，且回调里的 JDBC 网络请求会触发额外类加载，存在类初始化递归/死锁的理论风险；clinit 内容多为静态字段赋值，注入价值低。判重表本身能兜住重复（同轮重启后放行可正常完成初始化），排除是工程稳妥选择 |
| bridge / `access$xxx` synthetic 不注入 | 编译器生成的转发型合成方法被排除 | 方法体仅 1-2 行转发且行号表指向原方法声明处，hook 它们会与目标方法对同一行重复命中 |
| lambda（`lambda$xxx`）**会注入** | synthetic 方法中仅 lambda 前缀被纳入 | lambda 体内是用户业务逻辑（已实测 `lambda$auditAll$0` 内部行命中） |
| 接口中的无方法体声明不注入 | 抽象方法无字节码 | 无行可 hook；实现类的方法正常 hook |
| 构造器会注入 | `<init>` 与普通方法同机制 | 已实测（TC3 命中 `<init>` line=21） |
| 无轮次 tag 直接 kill | JVM 未加 `-Dfault.tag` 时，模块 inject 阶段直接 kill 进程 | 无轮次标识的故障记录无法按轮判重，按硬保护原则不放行 |
| 行级 hook 的性能 | `beforeLine` 命中前有行号查找开销 | 仅判重命中的行有内存 set 短路；表3 抢占失败（同轮已发生）的行不再撞库 |
| sandbox 版本 | 基于 JVM-Sandbox 1.4.0 API | 该版本 `EventWatchBuilder` 无 `withLoad()`；watcher 为常驻 matcher，对启动后才加载的类天然生效 |

## 多节点与异常场景（已验证）

**"每一行只会故障一次"的保证来自数据库唯一索引 `uk_hit_tag(unit_id, class_name, method_name, line_no, tag)`**：`INSERT IGNORE` 是数据库层原子操作，多节点同毫秒抢占同一行同一轮时，MySQL 保证只有一条插入成功——这是结构性保证，不依赖应用内锁或时序。

- **多节点并发**（TC5）：两实例同时启动，表1 状态机去重无重复行；两节点各死于不同新行，表3 同 (行,tag) 仅一条
- **跨 tag 隔离**（V2/V4）：tagA 与 tagB 各自独立判重，换 tag 后所有行重新可注入；tagB 轮 24 条记录覆盖 24 个不同行
- **异常场景**（均已实测）：
  - 挂载失败（sandbox.sh 不存在）→ 表4 MOUNT/EXCEPTION + kill，应用不放行（TC7）
  - 缺 `fault.tag` → 表4 记录 + 直接 kill（V1）
  - 本机解析中断（pending 遗留）→ 重启立即续传（TC6）
  - 解析单元异常 → 表1 置 failed + 表4 + kill，重启自动重解析
  - DB 不可达 → premain 报 schema not ready → 本地日志留痕（表4 写不进时降级）→ kill
  - bootJar 无法定位 → 硬保护 kill
