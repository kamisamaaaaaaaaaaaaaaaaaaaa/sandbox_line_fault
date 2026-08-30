# fault-sandbox：基于 JVM-Sandbox 的进程级故障注入工具

目标 Spring Boot 应用（bootJar）以 `-javaagent` 启动时自动完成：**解析用户类与方法 → 落 MySQL → 自动挂载 sandbox 模块 → 行级 watch 注入 kill 故障**。
硬保护原则：目标进程要么处于 watch 保护之下，要么不存在。

## 产物

| 产物 | 角色 |
|---|---|
| `fault-agent/build/libs/fault-agent-1.0.0.jar` | premain agent |
| `fault-module/build/libs/fault-module-1.0.0.jar` | sandbox 模块（放 `sandbox-modules/`） |
| `test-app/build/libs/test-app.jar` | 验证用 Spring Boot 应用 |

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

## 数据表（MySQL 库 fault_sandbox）

| 表 | 用途 | 关键约束 |
|---|---|---|
| `t_jar_record` | 解析单元记录（状态机：pending/completed/failed） | `uk(sha256)` 并发防重 |
| `t_class_method` | 类-方法解析结果 | `uk(unit_id,class,method,desc)` INSERT IGNORE 幂等 |
| `t_fault_record` | 故障命中记录 | `uk(unit_id,class,method,line)` 集群级抢占 |
| `t_error_record` | agent 自身错误（写入后进程被 kill） | — |

字段明细见 `SchemaInitializer.java` DDL。

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
