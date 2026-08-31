# fault-sandbox 测试用例总入口

> 运行环境：Linux 192.168.193.129（lys）。产物：`fault-agent-1.0.0.jar`、`fault-module-1.0.0.jar`、`test-app.jar`。
> 预期分三类：**命令预期 / 后端日志预期 / 数据库状态预期**。每条用例执行后在此记录结果与时间。

## 前置条件

- [ ] fault_sandbox 库与 4 张表**已手工执行 schema.sql 建好**（agent 只做存在性校验，缺表即硬保护，绝不自动建表）
- [ ] `sandbox/sandbox-module/` 已有 fault-module-1.0.0.jar
- [ ] agent `config.yml` 的 `jdbc.url` 指向可达 MySQL，`sandbox.home` 指向 sandbox 安装目录
- [ ] test-app.jar 白名单已配置：`config.yml` 中 `lib.whitelist: test-lib.*`（或用 `-javaagent:fault-agent.jar=lib.whitelist=test-lib.*` 覆盖）
- [ ] **所有用例启动命令必须带 `-Dfault.tag=<用例标识>`**（缺 tag 会被模块按策略 kill，无法执行用例）

## TC1 首次启动解析落库

- 操作：`java -Dfault.tag=tc1 -javaagent:fault-agent.jar -jar test-app.jar`
- 命令预期：应用启动成功（premain 解析落库 → 自动挂载 → 放行）
- 日志预期：agent 日志出现 `bootJar located` / `unit stored ... classes=N methods=M` / `mount OK, release application startup`；无 HARD PROTECT
- 数据库预期：
  - t_jar_record：2 行（CLASSES + fault-test-lib 的 LIB_JAR），status=completed，parsed_at 有值，boot_jar 为完整路径
  - t_jar_record.class_count/method_count 与源码实际一致（CLASSES 单元：TestApplication/OrderService/PayService/OrderController 共 4 类；方法数含构造器，排除 `<clinit>`/synthetic）
  - t_class_method：包含 testlib 包两类的方法（IdGenerator.nextOrderId/nextPayId、AmountChecker.isValid/isLarge）
- 结果：☐ 通过 ☐ 失败（现象：______）

## TC2 二次启动跳过解析

- 操作：杀掉进程后原样重启
- 命令预期：启动成功且明显更快跳过解析
- 日志预期：`unit already completed, skip`；无 `unit stored`
- 数据库预期：t_jar_record 仍 2 行；t_class_method 行数不变
- 结果：☐ 通过 ☐ 失败

## TC3 故障注入命中 kill

- 操作：应用启动完成（已挂载）后，`curl -X POST 'http://127.0.0.1:8080/order/create?amount=99'`
- 命令预期：请求hang住或连接断开，进程被 kill -9（`echo $?` 验证 exit 137 或 shell 报 Killed）
- 日志预期：模块日志出现 `FAULT HIT & PREEMPTED: unitId=N class=... method=create line=... thread=http-nio-*`；agent 无异常
- 数据库预期：t_fault_record 新增一条：unit_id 对应 CLASSES 单元、hostname/ip 为本机、class_name=...OrderService、method_name=create、line_no>0、thread_name 为 http 线程
- 结果：☐ 通过 ☐ 失败

## TC4 重启后续抢

- 操作：TC3 后重启应用，再次请求同一接口
- 命令预期：应用存活（同一行 DuplicateKey 放行），业务正常返回
- 数据库预期：t_fault_record 不新增同 (unit_id, class, method, line) 行；若命中其他未触发行则新增一条且进程死
- 结果：☐ 通过 ☐ 失败

## TC5 多节点并发

- 操作：两个 test-app 实例（不同端口）同时启动并同时请求 create
- 命令预期：仅一个实例死（另一个存活或因其他行死亡）
- 数据库预期：t_jar_record 无重复 completed 行（状态机去重）；同一行 t_fault_record 仅一条
- 结果：☐ 通过 ☐ 失败

## TC6 本机中断续传

- 操作：手工把 CLASSES 单元行改为 `status='pending', parsed_at=NULL`（ip 保持本机）→ 重启应用
- 命令预期：启动成功
- 日志预期：agent 立即重解析（不等孤儿阈值），出现 `unit stored`
- 数据库预期：该单元 status 回到 completed；t_class_method 行数不变（INSERT IGNORE 幂等）
- 结果：☐ 通过 ☐ 失败

## TC7 挂载失败硬保护

- 操作：`-javaagent:fault-agent.jar=sandbox.home=/not/exist`
- 命令预期：进程自动退出（被 agent kill），应用不会启动完成
- 日志预期：agent 日志出现 `HARD PROTECT: phase=MOUNT`；`KILL current process`
- 数据库预期：t_error_record 新增一条 phase=MOUNT、error_type=TIMEOUT 或 EXCEPTION、boot_jar 为完整路径；对应 t_jar_record 行 status=completed（解析成功不受挂载失败影响）
- 结果：☐ 通过 ☐ 失败

## TC8 mount.enabled=false 纯解析模式放行

- 操作：`-javaagent:fault-agent.jar=mount.enabled=false -jar test-app.jar`
- 命令预期：应用正常启动（解析落库后放行，不挂载不注入）
- 日志预期：agent 日志出现 `unit stored ...`，随后 `mount.enabled=false -> parse-only mode (results in DB), release application startup`
- 数据库预期：t_jar_record/t_class_method 正常落库（status=completed）；t_fault_record 无新增（进程存活不命中）
- 结果：☐ 通过 ☐ 失败

## 执行记录

| 日期 | 用例 | 结果 | 备注 |
| --- | --- | --- | --- |
| 2026-08-30 | TC1 首次启动解析落库 | ✅ | failed 重解析→completed；白名单 test-lib 命中（classes=4 methods=16 / lib classes=2 methods=6）；mount exit=0 |
| 2026-08-30 | TC2 二次启动跳过解析 | ✅ | `unit already completed, skip` ×2，挂载照常 |
| 2026-08-30 | TC3 故障注入命中 kill | ✅ | 命中 TestApplication.`<init>` line=21（启动期），表3 完整记录后 kill -9 |
| 2026-08-30 | TC4 重启后续抢 | ✅ | line=21 DuplicateKey 放行 → line=22 抢占 kill，死点前进 |
| 2026-08-30 | TC5 多节点并发 | ✅ | 两实例并发：表1 无重复行；同轮竞争各死于不同新行（line30 / testlib line6），行无重复记录 |
| 2026-08-30 | TC6 本机中断续传 | ✅ | 手工置 pending(ip=本机) → 启动立即重解析 → completed，t_class_method 幂等不变 |
| 2026-08-30 | TC7 挂载失败硬保护 | ✅ | 表4 记录 phase=MOUNT type=EXCEPTION（含 boot_jar 完整路径/机器/ip）→ kill → 应用不放行；表1 不受影响 |
| 2026-08-30 | TC8 agent.enabled=false | ✅ | `skip all, release application startup`，应用正常存活 |

## 轮次 tag 与 lambda 命中（2026-08-30 追加验证）

| # | 用例 | 结果 | 备注 |
| --- | --- | --- | --- |
| V1 | 无 fault.tag 启动 | ✅ | 模块日志 `kill process per policy` + 表4 记录（id=3）+ 进程被杀，应用不放行 |
| V2 | tagA 首轮命中 | ✅ | `-Dfault.tag=tagA`：line21 在 tag='' 有旧记录的情况下仍抢占成功（id=16, tag=tagA）——**跨 tag 判重隔离** |
| V3 | lambda 行级命中 | ✅ | 解析纳入 `lambda$` 前缀方法；tagB 轮渐进重启命中 `lambda$startupRunner$0` line44、`lambda$auditAll$0` line77/78/79 并依次 kill |
| V4 | tag 轮内判重 | ✅ | tagB 轮 24 条记录覆盖 24 个不同 (class,method,line)，同轮同行不重复注入；tagB 与 tagA、空 tag 互不影响 |

> 环境备注：Linux OpenJDK 21 下 `getInputArguments()` 不含 `-jar`（JDK9+ 行为），bootJar 定位链为 sun.java.command → /proc/self/cmdline → inputArguments 三级（均失败则硬保护 kill，无配置兜底）；sandbox.sh 需以其 bin 目录为工作目录执行（SANDBOX_HOME_DIR=${PWD}/..）。
>
> 轮次说明：操作者在目标应用 JVM 参数加 `-Dfault.tag=<轮次标识>`；模块 inject 时读取，缺失则直接 kill 进程（表4 留痕）。判重唯一索引 `uk(unit_id,class,method,line,tag)`——新 tag 开始所有行重新可注入。

