# fault-sandbox 测试用例总入口

> 运行环境：Linux 192.168.193.131（lys2）。产物：`fault-agent-1.0.0.jar`、`fault-module-1.0.0.jar`、`test-app.jar`。
> 预期分三类：**命令预期 / 后端日志预期 / 数据库状态预期**。每条用例执行后在此记录结果与时间。

## 前置条件

- [ ] fault_sandbox 库与 4 张表可被 agent 自动初始化（或已手工建好）
- [ ] `sandbox-modules/` 已有 fault-module-1.0.0.jar
- [ ] agent config.properties 的 jdbc.url 指向 Linux 侧可达 MySQL，sandbox.sh.path 正确
- [ ] test-app.jar 白名单已启用：`-javaagent:fault-agent.jar=lib.whitelist=fault-test-lib`

## TC1 首次启动解析落库

- 操作：`java -javaagent:fault-agent.jar=lib.whitelist=fault-test-lib -jar test-app.jar`
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

- 操作：`-javaagent:fault-agent.jar=sandbox.sh.path=/not/exist/sandbox.sh`
- 命令预期：进程自动退出（被 agent kill），应用不会启动完成
- 日志预期：agent 日志出现 `HARD PROTECT: phase=MOUNT`；`KILL current process`
- 数据库预期：t_error_record 新增一条 phase=MOUNT、error_type=TIMEOUT 或 EXCEPTION、boot_jar 为完整路径；对应 t_jar_record 行 status=completed（解析成功不受挂载失败影响）
- 结果：☐ 通过 ☐ 失败

## TC8 agent.enabled=false 放行

- 操作：`-javaagent:fault-agent.jar=agent.enabled=false -jar test-app.jar`
- 命令预期：应用正常启动，无解析无挂载
- 日志预期：agent 日志仅 `agent.enabled=false -> skip all`
- 数据库预期：无新增
- 结果：☐ 通过 ☐ 失败

## 执行记录

| 日期 | 用例 | 结果 | 备注 |
| --- | --- | --- | --- |
| | | | |
