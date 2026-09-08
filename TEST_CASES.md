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
  - t_jar_record.class_count/method_count 与源码实际一致（CLASSES 单元：TestApplication/OrderService/PayService/OrderController 共 4 类；方法数含构造器，排除 `<clinit>`/abstract/synthetic）
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
- 数据库预期：t_fault_record 新增一条：unit_id 对应 CLASSES 单元、hostname/ip 为本机、class_name=...OrderService、method_name=create、line_no>0、thread_name 为 http 线程、fault_seq=1
- 结果：☐ 通过 ☐ 失败

## TC4 重启后续抢

- 操作：TC3 后重启应用，再次请求同一接口
- 命令预期：应用存活（同一行 DuplicateKey 放行），业务正常返回
- 数据库预期：同一「行+线程+调用栈」的故障次数未达 `inject.fault.times` 前会继续新增（`fault_seq` 递增），达到上限后不再新增；若命中其他未触发行则新增 `fault_seq=1` 且进程死
- 结果：☐ 通过 ☐ 失败

## TC5 多节点并发

- 操作：两个 test-app 实例（不同端口）同时启动并同时请求 create
- 命令预期：仅一个实例死（另一个存活或因其他行死亡）
- 数据库预期：t_jar_record 无重复行（uk(sha256) 登记），两个实例可能各自解析同一单元（幂等收敛），最终 completed；同一「行+线程+调用栈+第几次」组合 t_fault_record 仅一条（另一实例冲突放行）
- 结果：☐ 通过 ☐ 失败

## TC6 未完成时重新解析

- 操作：手工把 CLASSES 单元行改为 `status='pending', parsed_at=NULL` → 重启应用
- 命令预期：启动成功
- 日志预期：agent 直接重新解析（无等待、无抢占判定），出现 `unit stored`
- 数据库预期：该单元 status 回到 completed，ip/hostname 为本次解析节点；t_class_method 行数不变（逐行裸 INSERT 冲突跳过，SQLState 23 类判定；非 23 类错误走硬保护）
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

## W 系列：裸 INSERT 冲突判定（替代 INSERT IGNORE）

> 语义：唯一键冲突（SQLState 23 类）才放行/跳过；其他 SQL 错误（截断 22001、非法值等）一律硬保护 kill。

| # | 用例 | 结果 | 备注 |
| --- | --- | --- | --- |
| W1 | 正常轮：首次解析批量路径 + 命中 kill | ✅ | mount OK → FAULT HIT，批量快路径无异常 |
| W2 | 重解析幂等轮：置 pending → 重启 → 冲突批降级逐行，表2 行数不变 | ✅ | 26 行全冲突降级逐行跳过，unit stored 正常，表2=26 |
| W3 | 两实例并发解析同单元：互相冲突降级，表1 无重复、表2 收敛 | ✅ | 表1=2 行无重复；表2=26 收敛；表3 两实例同 tag 启动：实例1 抢占 line22、实例2 冲突放行推进 line23（顺带覆盖 W5） |
| W4 | **截断硬保护轮**：清空表2 → class_name 改 VARCHAR(10) → 重启解析 → STRICT 模式 Data truncation（非 23 类）→ HARD PROTECT kill → 恢复列宽后重启表2 自愈重建 26 行 | ✅ | `HARD PROTECT: phase=DB ... Data too long for column 'class_name'` + KILL——旧 INSERT IGNORE 会把此错误当冲突静默吞掉 |
| W5 | 表3 冲突放行轮：同 tag 重启复现 DuplicateKey 放行日志 | ✅ | W3/W5 均实测：line22 冲突放行 → line23 新行抢占 kill |

## 每行每线程多次故障（F 系列）

> 语义：判重键含 `thread_name`、`fault_seq` 与 `stack_hash`。同一行在同一线程、经同一调用栈执行时本轮最多发生 `inject.fault.times`（记为 N，未配置时默认 1）次故障；该行被 T 个线程、S 种调用栈执行时本轮最多 T × S × N 次。
> 为控制时长，验证用 `inject.filters`（单块 `scope: global`）只注入少量方法——验证目标是机制正确性（seq 连续、各线程独立计数、用尽后零撞库），不是覆盖率。

| # | 用例 | 预期 | 结果 |
| --- | --- | --- | --- |
| F1 | 单进程、N=3、`inject.filters` 单块 `scope: global` 限定单个方法，跑至稳定 | 该方法每行的每个线程最终 3 条记录，`fault_seq` = 1,2,3；表3 中无其他方法的记录 | ☐ 通过 ☐ 失败 |
| F2 | 按「行+线程」核对：每个组合 `cnt=3`、`min(fault_seq)=1`、`max(fault_seq)=3` 且 `cnt=max` | 无缺号、无重复、无超额 | ☐ 通过 ☐ 失败 |
| F3 | 线程维度独立性：同一行被 4 个线程执行 | 该行共 4 × 3 = 12 条，`(thread_name, fault_seq, stack_hash)` 组合唯一 | ☐ 通过 ☐ 失败 |
| F4 | 用尽后零撞库：跑满后继续运行一段时间 | 表3 不再新增记录；模块日志不再出现该行的 `fault seq already preempted` | ☐ 通过 ☐ 失败 |
| F5 | N=1 对照（其余配置不变，仅改 `inject.fault.times: 1`） | 退化成改造前的行为：每行每线程仅 1 条 | ☐ 通过 ☐ 失败 |
| F6 | 默认值：不配置 `inject.fault.times`（默认 1） | 每行每线程仅 1 条记录，行为与改造前一致 | ☐ 通过 ☐ 失败 |
| F7 | 配置健壮性：`inject.fault.times` 填 0 或负数 | 模块抛错 → 表4 留痕 → kill（硬保护） | ☐ 通过 ☐ 失败 |

核对 SQL：

```sql
SELECT class_name, method_name, line_no, thread_name,
       COUNT(*) cnt, MIN(fault_seq) min_seq, MAX(fault_seq) max_seq
FROM t_fault_record
WHERE tag = '<本轮tag>'
GROUP BY class_name, method_name, line_no, thread_name
HAVING cnt <> 3
ORDER BY class_name, method_name, line_no, thread_name;
```

**区分「未跑满」的性质**：线程跑满 N 次需跨多个进程生命周期累计执行该行约 `1+2+…+N` 次（每次 kill 后新进程计数归零，靠冲突逐次推进）。低频线程暂未跑满属正常，继续运行会补齐；出现 `fault_seq` 缺号，或执行次数已远超 `N(N+1)/2` 仍未跑满，则为计数缺陷。

## 调用栈判重（C 系列，2026-09-03 追加验证）

> 语义：表3 增加 `stack_hash`（`MD5(stack_text)` 32 位 hex，入唯一索引）与 `stack_text`（调用栈原文，MEDIUMTEXT）；
> 判重键 = 轮次 + 应用部署路径 + 行 + 线程 + 调用栈 + 第几次故障，配额按「行+线程+调用栈」独立推进。
> 故障命中（赢得抢占）后 kill 前打印完整调用栈并随记录落库，两者内容同源。

| # | 用例 | 结果 | 备注 |
| --- | --- | --- | --- |
| C1 | kill 前打印调用栈并落库（tag=cs-v1） | ✅ | 日志 `FAULT HIT ... stackHash=...` 换行接 `call stack (N frames):` 逐帧输出；表3 `stack_hash` 与日志一致、`stack_text` 完整（603 字符），命中后 kill 生效 |
| C2 | 栈裁剪：栈顶工具帧不落栈（取栈入口 / Spy 探针 / sandbox 事件分发 / 本模块回调） | ✅ | cs-v1 轮发现 Spy 探针帧真实包名为 `java.com.alibaba.jvm.sandbox.spy.Spy`（带 java. 前缀）未被裁掉；补裁剪前缀后 cs-v2 轮栈首帧 = 故障行自身，8 帧全业务帧 |
| C3 | fault_seq 推进 + 线程维度 + 调用栈维度独立计数（tag=cs-v3，`inject.fault.times=2`，include 限 method0/method1） | ✅ | 408 条 = method0 272（17 行 × 4 线程 × 2 栈 × 2 次）+ method1 136（17 × 4 × 1 × 2）；每个 (行,线程,栈) 组合恰 2 条、`fault_seq` ∈ {1,2} 无缺号（违规组合 0）；method0 每行 2 种 stack_hash × 4 线程；4 个 worker 线程全部出现，同一行不同线程可走不同调用路径 |
| C4 | 饱和判据 | ✅ | 225 个进程周期产生 408 次命中（4 线程在 kill 生效前并发命中，~1.65 条/周期）；跑满后命中停止、进程稳定存活 |

载荷说明：stress-app 重新生成（`gen-stress.ps1 -Methods 8 -SeqLines 5 -BranchLines 2 -Threads 4`），并把 `run()` 取模域扩为 `% (METHOD_COUNT + 4)`，使 else 兜底分支（同样调用 method0）真实可达——method0 因此拥有两条调用路径（`run:21` / `run:37` 两个调用点行号），用于构造「同一行不同调用栈」场景。

## 重载方法与签名（O 系列，2026-09-04 追加验证）

> 语义：sandbox 的 `onBehavior(String)` 只按方法名匹配，注册一次即织入同名方法的**全部重载**；
> 因此 `t_class_method` 中同一 `(class_name, method_name)` 的多个重载行对注入是冗余的，
> 模块以 `(class_name, method_name)` 为严格大于游标分页，每个方法名只注册一次。
> `t_fault_record.method_desc` 记录命中方法的 ASM 描述符，供排查区分重载（不参与判重）。

| # | 用例 | 结果 | 备注 |
| --- | --- | --- | --- |
| O1 | 按方法名注入是否影响所有重载（tag=ov-v1，载荷新增 `cn.stress.Overloaded.target(String)` 与 `target(int)`，行号范围 18-24 / 28-34） | ✅ | 两重载的行号**均出现命中**，各 4 次（4 个 worker 线程独立计数）——证实按名注入织入全部重载 |
| O2 | `t_class_method` 重载行落库与 `desc_hash`（tag=ov-v2） | ✅ | `Overloaded.target` 两行（`(I)J` / `(Ljava/lang/String;)J`），`desc_hash` 与 `SUBSTR(MD5(method_desc),1,16)` 一致（match=true） |
| O3 | `t_fault_record.method_desc` 区分重载 | ✅ | `(Ljava/lang/String;)J` 的记录行号 18-24、`(I)J` 的 28-34，各 28 条（7 行 × 4 线程）；在 `t_class_method` 中匹配不到的记录数 = 0 |
| O4 | 跨批去重（`inject.batch.size=1`，每个方法名必然跨批，tag=ov-v3） | ✅ | 17 条 `batch registered` 日志的 `cursor` 严格递增、每个 `(class_name, method_name)` 只出现一次；`inject done: injected=1 methods`（无重复注册）；命中 56 次、行号 18-24 与 28-34 各 4 次，**覆盖范围与去重前一致** |
| O6 | 注入统计口径（多批场景，`inject.batch.size=3`，tag=rg-v4） | ✅ | 逐批日志 `batch registered: scanned=3 rows, classes=C, injected=N methods, cursor=...` 同时给出 Class 级与方法级信息；首批 `scanned=3 rows, classes=1, injected=2 methods`（3 行中 `Overloaded.target` 的 2 个重载行只注入 1 个方法名）。汇总 `inject done: injected=17 methods, scanned=18 rows` 只含方法级与扫描行数 |
| O5 | 重解析幂等（单元置回 pending 触发重新解析，tag=ov-v4） | ✅ | `t_class_method` 行数仍 18（未翻倍）、单元回到 completed、唯一键重复组数 = 0 |

载荷说明：`stress-app` 新增 `cn.stress.Overloaded`（两个 `target` 重载，行号范围刻意错开），`StressWorker.run()` 每轮调用两个重载。
注意：该载荷为手工改动，**不要再执行 `gen-stress.ps1`**（会覆盖 `StressWorker.java`）。

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
> 轮次说明：操作者在目标应用 JVM 参数加 `-Dfault.tag=<轮次标识>`；模块 inject 时读取，缺失则直接 kill 进程（表4 留痕）。判重唯一索引 `uk(tag, boot_jar_hash, class_name, method_name, line_no, thread_name, fault_seq, stack_hash)`（判重键 = 轮次 + 应用部署路径 + 行 + 线程 + 调用栈 + 第几次故障；`ip` 不参与判重）——新 tag 开始所有行重新可注入。

## 接口与继承的注入口径（I 系列，2026-09-04 追加验证）

> 语义：`t_class_method` 的 `class_name` 恒为**声明该方法的类型**（ASM 逐 class 文件收集），
> 注入是 `onClass(精确类名).onBehavior(方法名)`，插桩落在该类的字节码上；
> 调用时 JVM 分派到**实际含方法体的那个类**的字节码。三者叠加决定了一行能否被命中。

**载荷**（`stress-app`，`cn.stress.inh` 包）：

| 类型 | 声明的方法 | 说明 |
| --- | --- | --- |
| `IfaceApi`（接口） | `apiAbstract`（abstract，无 Code）、`apiDefault`（default，有 Code） | 同一接口内两种形态对照 |
| `IfaceImpl implements IfaceApi` | `apiAbstract` | 只重写 abstract，不重写 default |
| `Base` | `overridden`、`inherited` | 父类两个方法 |
| `Child extends Base` | `overridden` | 只重写一个，`inherited` 保持继承 |
| `InhRunner`（`cn.stress` 包） | `tick` | 触发入口，**刻意放在 inh 包外**，使 `include` 限定时它不被注入 |

`StressWorker.run()` 每轮调用 `InhRunner.tick(seed)`，`tick` 内四个调用点各触发一次。

**字节码证据**（`javap -p -c`，本机验证）：

```
public interface cn.stress.inh.IfaceApi {
  public abstract long apiAbstract(long);      <- 无 Code 属性（织入器无处插桩）
  public default long apiDefault(long);
    Code: ...                                   <- 有方法体
}

public final class cn.stress.inh.Child extends cn.stress.inh.Base {
  public cn.stress.inh.Child();
  public long overridden(long);                 <- 无 inherited：t_class_method 不会有 (Child, inherited) 行
}

InhRunner.tick:
  10: invokeinterface #10  // InterfaceMethod cn/stress/inh/IfaceApi.apiAbstract:(J)J
  20: invokeinterface #16  // InterfaceMethod cn/stress/inh/IfaceApi.apiDefault:(J)J
  43: invokevirtual   #24  // Method cn/stress/inh/Child.overridden:(J)J
  55: invokevirtual   #29  // Method cn/stress/inh/Child.inherited:(J)J
```

最后一条是关键：符号引用写的是 `Child.inherited`，但 `Child.class` 里没有该方法，
运行时必然沿继承链解析到 `Base.inherited` 的字节码——而插桩就在 `Base.class` 上。

**判定表**（`inject.filters` 单块 `scope: global`、`include` = `cn\.stress\.inh\..*`，单轮即可覆盖）：

| # | 断言 | 依据 | 结果 |
| --- | --- | --- | --- |
| I1 | 表2 中有 `IfaceApi.apiAbstract` 行，但表3 永不出现该行 | 无 Code 属性，织入器无处插桩 | ✅ 通过（表2 有该行，表3 零记录） |
| I2 | 表3 出现 `IfaceImpl.apiAbstract` | 调用分派到实现类的字节码 | ✅ 通过（24 条，行 11-16） |
| I3 | 表3 出现 `IfaceApi.apiDefault` | default 方法有自己的方法体，实现类未重写 | ✅ 通过（24 条，行 15-20） |
| I4 | 表3 出现 `Child.overridden` | 子类重写方法在子类字节码上 | ✅ 通过（24 条，行 11-16） |
| I5 | 表3 不出现 `Base.overridden` | 被子类重写，执行的是子类字节码（除非 `super.` 调用） | ✅ 通过（表2 有该行，表3 零记录） |
| I6 | 表3 出现 `Base.inherited` | 子类未重写，调用解析到父类字节码（记父类名） | ✅ 通过（24 条，行 19-24） |
| I7 | 表2 中不存在 `(Child, inherited)` 行 | `Child.class` 无该方法声明 | ✅ 通过（表2 中 Child 只有 `<init>` 与 `overridden`） |

**实测结果**（tag=`inh-v1`，2026-09-04，单实例 25 个进程周期）：

```
t_fault_record（tag='inh-v1'）
  cn.stress.inh.Base      inherited     cnt=24  line 19-24  threads=4  stacks=6
  cn.stress.inh.Child     overridden    cnt=24  line 11-16  threads=4  stacks=6
  cn.stress.inh.IfaceApi  apiDefault    cnt=24  line 15-20  threads=4  stacks=6
  cn.stress.inh.IfaceImpl apiAbstract   cnt=24  line 11-16  threads=4  stacks=6

t_class_method（class_name LIKE 'cn.stress.inh.%'，共 9 行）
  Base: <init> / inherited / overridden
  Child: <init> / overridden            <- 无 inherited
  IfaceApi: apiAbstract / apiDefault    <- apiAbstract 有行但永不命中
  IfaceImpl: <init> / apiAbstract
```

**口径收紧后的回归**（tag=`inh-v2`，2026-09-04，`BootJarParser` 增加 `ACC_ABSTRACT` 排除后清库重跑）：

| # | 断言 | 结果 |
| --- | --- | --- |
| I8 | 表2 中 `IfaceApi` 只剩 `apiDefault`，`apiAbstract` 行消失（inh 包 9 行 → 8 行）；表3 命中分布与收紧前完全一致（四个调用点各 24 条） | ✅ 通过 |

收紧后 `t_class_method`（`class_name LIKE 'cn.stress.inh.%'`，共 8 行）：

```
Base: <init> / inherited / overridden
Child: <init> / overridden
IfaceApi: apiDefault            <- apiAbstract 已在解析阶段排除
IfaceImpl: <init> / apiAbstract
```

**native 排除（I9/I10，2026-09-05）**：

`EventWeaver` 的两条改写路径不对称——`rewriteNormalMethod` 重写了 `visitLineNumber`（插 LINE 事件）；
`rewriteNativeMethod` 虽去掉 `ACC_NATIVE`、装上方法体并生成代理方法（确实完成织入），
但只插 BEFORE / RETURN / THROWS、**不插 LINE**（native 无 Code 属性也就没有 LineNumberTable）。
本模块只监听 `beforeLine`，故 native 方法注册后同样永不回调——与 abstract 一样是死行，失败点不同。

| # | 断言 | 结果 |
| --- | --- | --- |
| I9 | 载荷新增 `NativeOps.nativeOp`（static native，无 JNI 实现，调用抛 `UnsatisfiedLinkError` 由调用方 catch）。用仅排除 abstract 的 agent 跑（tag=`inh-v3`）：表2 有 `(NativeOps, nativeOp)` 行、表3 零命中——**native 是死行，端到端证实** | ✅ 通过 |
| I10 | 换上同时排除 abstract + native 的 agent（tag=`inh-v4`）重跑：表2 无 `nativeOp` 行（inh 包 10 行 → 9 行），表3 四个调用点各 24 条与 I9 完全一致——**排除生效且无误伤** | ✅ 通过 |

> 附带观察：三对 `<init>`（默认构造器）在表2 有行、表3 无记录。

## 解析范围与注入范围（P 系列，2026-09-05）

> 载荷：`test-app.jar`（应用代码 `cn.chinaclear.fault.testapp.*` 4 类 20 方法；
> 第三方 `BOOT-INF/lib/test-lib-1.0.0.jar`，`cn.chinaclear.fault.testlib.*` 2 类 6 方法；合计 26 行）。
> 执行方式（当时）：agent 侧参数用 `-javaagent:fault-agent-1.0.0.jar=k=v,...` 覆盖——该写法现已移除，
> 重跑需将同样的配置写进 agent jar 内 `config.yml` 后重新打包上传；
> module 侧的 `inject.filters` 需改 jar 内 `config.yml`（`jar uf`）后上传，脚本见 `scripts/p-start.sh`。
> 判定主要看模块日志 `filters: ...` 与 `inject done: injected=N methods`（不等命中，快且确定）。

| # | 场景 | 配置 | 预期 | 结果 |
| --- | --- | --- | --- | --- |
| P0 | 默认回归 | 不配 `inject.filters`、`parse.classes.enabled` 不配 | `filters: none`；表1 有 CLASSES + LIB_JAR 两个单元；`injected=26` | ✅ 通过 |
| P1 | 只解析第三方 jar | `parse.classes.enabled=false` + `lib.whitelist=test-lib-.*.jar` | 表1 **只有** LIB_JAR 单元；表2 只有 testlib 两类；命中全在 testlib | ✅ 通过（表1 仅 1 行；命中 `IdGenerator.<init>`/`nextOrderId`） |
| P2 | 零单元硬保护 | `parse.classes.enabled=false` + 白名单不匹配 | `HARD PROTECT: phase=PARSE ... no parse unit for this round`，进程被 kill | ✅ 通过（日志带出两个配置的值） |
| P3a | scope=lib + libs 匹配 | 块：`scope: lib`、`libs: ['test-lib-.*.jar']`、`exclude: ['cn\.chinaclear\.fault\.testlib\..*']` | testlib 的 6 个方法被排除；`injected=20` | ✅ 通过（`injected=20, scanned=26`） |
| P3b | libs 不匹配 | 同上，仅 `libs` 改为 `['nomatch-.*.jar']` | 块不覆盖任何单元，exclude 不生效；`injected=26` | ✅ 通过（对照 P3a 差值恰为 testlib 的 6 个方法） |
| P4 | scope=class | 块：`scope: class`、`exclude: ['cn\.chinaclear\.fault\.testapp\..*']` | 应用代码 20 个方法被排除，第三方不受影响；`injected=6` | ✅ 通过（命中全在 testlib） |
| P5 | 多块串行 | 块1 `global` 排除 `TestApplication.*`；块2 `class` 只留 `service.OrderService.*` | `injected=12` = OrderService 6 + testlib 6 | ✅ 通过（`filters: 2 block(s), applied in order`；`injected=12`） |
| P6 | scope 非法 | `scope: xxx` | 表4 记录后 kill，消息带出块序号与非法值 | ✅ 通过（`inject failed: invalid inject.filters.0.scope "xxx"（global / class / lib）`） |
| P7 | filters 正则非法 | `exclude: ['[']`（未闭合字符类） | 表4 记录后 kill，消息带出块序号/类别/条号/原文 | ✅ 通过（`invalid inject.filters.0.exclude regex（第 0 条）"[": Unclosed character class`） |
| P8 | 白名单正则非法 | `-javaagent:...=lib.whitelist=[` | agent 侧 `HARD PROTECT: phase=PARSE` | ✅ 通过（`parse bootJar failed: invalid lib.whitelist regex（第 0 条）"["`） |

**键名简化后的复验**（`include.methods` / `exclude.methods` → `include` / `exclude`，去掉 `methods` 层）：

| # | 复验内容 | 结果 |
| --- | --- | --- |
| R1 | 本地 `ConfigCheck` 解析新键名：`inject.filters = [scope=global ... exclude=1, scope=class include=1 ...]` | ✅ 通过 |
| R2 | P5 多块串行改用新键名 | ✅ 通过（`injected=12`、`scanned=26`，与简化前一致） |
| R3 | P4 `scope: class` + `exclude` 改用新键名 | ✅ 通过（`injected=6`，确认 exclude 侧读到） |
| R4 | P7 正则非法，错误消息键名 | ✅ 通过（由 `inject.filters.0.exclude.methods regex` 变为 `inject.filters.0.exclude regex`） |

**"应用代码全不注入、只注入指定第三方"的两种写法**（P9 / P10，2026-09-05 验证）

| # | 写法 | 配置 | 预期 | 结果 |
| --- | --- | --- | --- | --- |
| P9 | **按包名/类名限定**（两块） | 块1 `scope: class` + `exclude: ['.*']`；块2 `scope: lib` + `include: ['cn\.chinaclear\.fault\.testlib\.IdGenerator\..*']` | 应用代码 20 个全排除；testlib 中只放行 IdGenerator（AmountChecker 被 include 拦下） | ✅ 通过（`injected=3`，命中全在 `IdGenerator`） |
| P10 | **按 jar 名限定**（三块 + 负向环视） | 块1 `scope: class` + `exclude: ['.*']`；块2 `scope: lib` + `libs: ['test-lib-.*\.jar']`（留空即放行）；块3 `scope: lib` + `libs: ['(?!test-lib-.*\.jar).*']` + `exclude: ['.*']` | 应用代码全排除；test-lib 放行；其余 jar（slf4j）排除 | ✅ 通过（`injected=6`、`scanned=426`；表1 三个单元，slf4j 的 348 个方法已解析进表2 却被过滤排除；命中全在 testlib） |

要点：

- **p9 只需两块**，因为块2 的 `libs` 不配即覆盖**全部** lib，用 `include` 精确放行即可，不需要单独处理"其余 jar"。
- **p10 必须三块**，且块2 与块3 的 `libs` 必须互斥：若块3 也写成不配 `libs`（覆盖全部 lib），它同样会覆盖 test-lib，把块2 放行的方法再拦掉——串行过滤下"被放行的方法还要继续过后面的块"。
- 块2 不写 `include`/`exclude` 即"放行"（空名单 = 全选 + 不排除）。已验证有效；若担心可读性，可显式写 `include: ['.*']`，与留空等价。
- 负向环视正则经本地验证：`(?!test-lib-.*\.jar).*` 对 `test-lib-1.0.0.jar` 为 no-match，对其余 jar 为 MATCH。
- **语义限制**：当前"串行淘汰 + 未覆盖即放行"下，"全排除 + 例外放行"没有直接表达法，例外只能靠让各块作用范围互斥实现。若此类需求频发，可考虑改为"首个命中块生效"或"未覆盖即排除"。

## 配置校验：留空即非法（S 系列，2026-09-05）

> 语义：配置项「写了键却没值」一律判定非法并硬保护；「整行不写」仍是合法的未配置（标量取默认值、列表为空集合）。
> 覆盖三种留空形态：`key:`（冒号后无值）、`key: ''`、列表项 `- ` 后无内容、过滤块 `- {}`。
> 另：agentArgs 覆盖写法（`-javaagent:...=k=v`）已移除，传入被忽略、不生效也不报错。
> 判定用本地 `ConfigCheck`（classpath = common classes + snakeyaml + 待验 config.yml 目录），秒级。

| # | 配置 | 预期 | 结果 |
| --- | --- | --- | --- |
| S0 | 合法基准（仅 `parse.timeout.ms: 900000`） | 默认值/空集合正常，不抛异常 | ✅ 通过（`parseClassesEnabled=true`、`filters=[]`、`whitelist=[]`） |
| S1 | `key:` 冒号后无值（`parse.classes.enabled:`） | 硬保护，消息含键路径 | ✅ 通过（`invalid config.yml: config "parse.classes.enabled" is present but has no value（要么填值，要么删除该行）`） |
| S2 | `key: ''` 显式空字符串 | 硬保护 | ✅ 通过（`config "parse.classes.enabled" is present but empty（要么填值，要么删除该行）`） |
| S3 | 列表项留空（`lib.whitelist:` 下 `- ` 无内容） | 硬保护 | ✅ 通过（`config "lib.whitelist" item 0 is empty`） |
| S4 | 空过滤块（`inject.filters` 下 `- {}`） | 硬保护 | ✅ 通过（`config "inject.filters" item 0 is an empty block`） |
| S5 | `key: []` 空列表 | 合法（空集合） | ✅ 通过（`lib.whitelist = []`） |
| S6 | 过滤块只写 `scope: global` | 合法（块存在、无约束） | ✅ 通过（`[scope=global libs=0 include=0 exclude=0]`） |
| S7 | agentArgs 传值（`lib.whitelist=xxx,parse.classes.enabled=false`） | 被忽略，结果与不传一致且不报错 | ✅ 通过（`whitelist=[]`、`parseClassesEnabled=true`） |

> S1 走展平阶段的 null 拦截（异常被包成 `invalid config.yml`），S2 走读取阶段的空串拦截，两条代码路径不同，需分别验证。

**远端复验**（2026-09-05，agent 配置改走 jar 内 config.yml、无 agentArgs）：

| # | 内容 | 结果 |
| --- | --- | --- |
| R5 | P5 多块串行（module 侧 filters 新键名） | ✅ 通过（`injected=12`、`scanned=26`） |
| R6 | P4 `scope: class` + `exclude` | ✅ 通过（`injected=6`） |
| R7 | agent 配置改走 config.yml（`sandbox.home=/home/lys/sandbox-inh` + 白名单），无 agentArgs | ✅ 通过（解析/挂载/注入全流程正常） |

**语义验证要点**（P3a 与 P5 共同确认）：

- 作用范围不覆盖的块**不表态**——该块既不选入也不排除（P4：class 块的排除没有波及第三方 jar）；
- 范围覆盖的块内先 include 选入、再 exclude 过滤，任一块拦下即不注入（P5 两块叠加后 `injected` 与逐块推算一致）；
- 由此推论：只配一个 `scope: lib` 块时，应用代码因无块约束而**全部注入**；需要一并约束应用代码时，应再加一个 `global` 或 `class` 块。

### 实现过程中发现并修复的两个既有问题

| 问题 | 现象 | 修复 |
| --- | --- | --- |
| `FaultConfig.flatten` 不递归 List 内的 Map | `inject.filters` 每个块被 `String.valueOf` 压成一个字符串，块内字段全部读不到，日志恒为 `filters: none` | List 元素为 Map 时递归展平（`inject.filters.N.scope` 等键才能生成） |
| agentArgs 覆盖列表键失效 | config.yml 模板里 `lib.whitelist` 是注释态（无 `.0` 键），`-javaagent:...=lib.whitelist=x` 只写成标量，`getList` 读回空列表，白名单静默失效 | 覆盖时同时写标量形式与 `.0` 形式，两条读取路径都成立 |

## 隔离类加载（A 系列，2026-09-06）

> agent 从「shadow relocate 改写包名」改造为「壳 + 嵌套 core jar + 隔离 ClassLoader」，依赖类名保持原名，
> `jdbc.driver` 两侧统一填标准类名。结构：外壳根上仅 `FaultAgent` / `AgentClassLoader` 两壳类 + `lib/agent-all.jar` 嵌套 entry；
> 隔离 loader parent 取 system loader 的父加载器（platform/ext），child-first 从内存嵌套 jar 加载。
> 验证环境：Windows 本机（JDK 21），test-app（Spring Boot 2.7.18 bootJar，额外加 `runtimeOnly com.mysql:mysql-connector-j:8.4.0`
> 构造与 agent 依赖同名的场景），`mount.enabled=false` 纯解析模式（本机无 sandbox，挂载链路代码未变）。

| # | 内容 | 预期 | 结果 |
| --- | --- | --- | --- |
| A0 | 静态结构断言：`jar tf fault-agent-1.0.0.jar` | 根上仅壳类（`FaultAgent.class`、`AgentClassLoader.class` 及其内部类）、`lib/agent-all.jar`、MANIFEST；无任何 `com/`、`org/`、`cn/.../common/`、`shaded` 依赖类 entry | ✅ 通过（嵌套 jar 内确认含 `com/mysql/cj/**` 原名驱动、`org/objectweb/asm/**`、`config.yml`） |
| A1 | 全链路：`java -javaagent:... -jar test-app.jar`（parse-only） | 壳日志 → core 解析落库 → 应用正常启动 → isolation-check 打印 | ✅ 通过（`isolated classloader ready: 2143 entries from ...fault-agent-1.0.0.jar!/lib/agent-all.jar`；`unit stored: type=CLASSES ... methods=21`；`Started TestApplication`） |
| A2 | agent 侧驱动类来源（决定性） | agent 的 `com.mysql.cj.jdbc.Driver` 来自隔离 loader / 嵌套 jar | ✅ 通过（`jdbc driver resolved: com.mysql.cj.jdbc.Driver -> loader=cn.chinaclear.fault.agent.AgentClassLoader@..., codeSource=(agentjar:...fault-agent-1.0.0.jar!/lib/agent-all.jar!/ ...)`，且为**标准类名**） |
| A3 | 应用侧同名类来源（决定性） | 应用的 `com.mysql.cj.jdbc.Driver` 命中应用自己 BOOT-INF/lib，不命中 agent | ✅ 通过（`classLoader = org.springframework.boot.loader.LaunchedURLClassLoader@...`，`codeSource = ...test-app.jar!/BOOT-INF/lib/mysql-connector-j-8.4.0.jar`） |
| A4 | 壳阶段硬保护 | 壳构造/反射失败时 `System.err` 留痕 + `halt(137)`，应用不得继续启动 | ✅ 通过（改造中途内部类缺失触发过真实场景：`[fault-agent] HARD PROTECT: agent bootstrap failed: NoClassDefFoundError`，进程即死、无 Spring 启动输出） |
| A5 | core 内硬保护路径 | `AgentBootstrap` 迁移后硬保护逻辑不变（写表4 尽力 + kill） | ✅ 通过（`HARD PROTECT` → `write t_error_record failed, fallback to local log only` → `KILL current process`；Windows 上 `kill` 命令不存在属既有行为，`halt` 兜底生效） |
| A6 | module 侧回归 | module 代码未改动（仅 config.yml 注释措辞），sandbox 挂载/注入链路不受影响 | ☐ 待原 Linux 环境（192.168.193.129）按 TC1~TC4 回归 |

> A2/A3 合并即隔离的双向证据：应用的 `com.mysql.*` 归应用（A3 证明 AppClassLoader 捞不到 agent 的类），
> agent 的归 agent（A2 证明 agent 能加载到且只可能来自嵌套 jar）。
>
> 改造过程中实测否决的方案细节：隔离 loader `parent=null` 不可行——JDK9+ 的 `java.sql` /
> `java.management` 等非 base 模块类由 platform loader 加载，`parent=null` 只委派到 bootstrap，
> core 一引用 `SQLException` 即 `NoClassDefFoundError`；外壳根上必须同时放 `AgentClassLoader` 的
> 内部类（构造 CodeSource 的 URL handler 时即触发加载）。

## 日志级别过滤（L 系列，2026-09-06）

> `FaultLogger` 增加 DEBUG 级与 `log.level` 阈值过滤（agent/module 各自配置，默认 INFO）：
> 低于阈值的日志 stdout 与文件都不输出；非法值按配置非法硬保护。
> 级别语义调整：`FAULT HIT & PREEMPTED`（故障命中，kill 前最后一条）由 ERROR 改为 **INFO**——
> 命中是演练的预期事件而非错误；多节点抢空的 `fault seq already preempted` 由 INFO 降为 **DEBUG**
>（用户指认的主要噪音，多节点并发时与冲突次数成正比）。全量日志清单见 README 第 7 章。
> 验证环境：Windows 本机，test-app（默认 INFO 集合中含 MOUNT 硬保护 kill，Windows 无 sandbox 所致）。

| # | 内容 | 预期 | 结果 |
| --- | --- | --- | --- |
| L0 | 构建验证 | agent/module 编译打包通过（含级别改造） | ✅ 通过（`:fault-agent:agentShellJar` + `:fault-module:shadowJar` BUILD SUCCESSFUL） |
| L1 | 默认（log.level 未配置） | init 日志带 `(level=INFO)`；INFO 里程碑齐全；无 DEBUG 输出 | ✅ 通过（`log file: ... (level=INFO)`、premain start / bootJar located / driver resolved / schema check OK / unit already completed, skip / parse units / mount cmd / HARD PROTECT / KILL 全部按预期级别出现） |
| L2 | `log.level: DEBUG` | init 日志带 `(level=DEBUG)`，DEBUG 明细恢复输出 | ✅ 通过（`(level=DEBUG)`；本场景无 DEBUG 源，module 侧 already preempted 的 DEBUG 输出待 Linux 复验） |
| L3 | `log.level: TRACE`（非法值） | 配置非法硬保护：写表4 后 kill，消息含允许值 | ✅ 通过（`HARD PROTECT: phase=PARSE, ... invalid log.level: "TRACE"（允许值：DEBUG / INFO / WARN / ERROR）` → KILL） |
| L4 | module 侧级别语义（需 Linux） | `FAULT HIT & PREEMPTED` 为 INFO（kill 前输出）；`already preempted` 默认 INFO 下静默、DEBUG 下出现；重复执行命中不再刷屏 | ☐ 待 Linux 环境（192.168.193.129）随 TC3/TC5 复验 |

## systemd 副本清理（M 系列，2026-09-08）

> 生产事故（focus@nwtr-de.service）：`KillMode=mixed` + `Restart=on-failure` 下，服务重启的停止阶段对
> cgroup 内除 Main PID 外的所有进程 SIGKILL，TmpReaper 在轮询间隔内被连坐杀死、`rm` 从未执行，
> 471 轮 × 6 份副本（sandbox 每次挂载复制 `sandbox-module/` 下全部 6 个模块 jar）写满 /tmp →
> `copyToTempFile` 报 `No space left on device` → 模块未注册 → 挂载 404 → 硬保护 kill。
> 方案：service 注入 `ExecStartPre`（建目录）+ `-Djava.io.tmpdir=tmp`（相对 WorkingDirectory）
> + `ExecStopPost` 清扫；TmpReaper 降级为非 systemd 环境兜底。
> 验证环境：测试机 192.168.193.129（systemd 245，user unit——与 system unit 行为等价）。

| # | 内容 | 预期 | 结果 |
| --- | --- | --- | --- |
| M1 | KillMode=mixed 连坐实证（user unit：worker spawn reaper 后长驻 → kill -9 主进程 → restart） | 旧 reaper 未及清理即被杀 | ✅ 通过（journal：`Killing process 2869 (bash) with SIGKILL`；EXECUTED CLEANUP 未出现、清理标记不存在；新 worker+reaper 正常顶上） |
| M2a | 修复组：ExecStartPre（mkdir tmp）+ `-Djava.io.tmpdir=tmp` + ExecStopPost（rm 副本）；worker 每轮创建 6 份副本 | kill 后 restart：副本收敛到当前轮 6 份，无累积 | ✅ 通过（T+8s=6=当前活 worker 份额；连续多轮 restart 均收敛，与 restart 新进程无副本竞争） |
| M2b | 对照组：无 ExecStopPost（依赖 reaper） | kill 后 restart：残留 6 + 新 6 = 12，每轮 +6 累积 | ✅ 通过（T+8s=12；两轮运行残留 18/12 与累积模型一致） |
| M2c | 多模块完整回归（真实 sandbox + copyToTempFile） | 独立 tmpdir 下多模块副本全清、挂载/注入正常 | ☐ 待生产部署时按 PLAN 第 6 节止血步骤回归 |

> M1 直接证据：主进程 kill -9 后 systemd 停止阶段立即 `Killing process <reaper-pid> (bash) with SIGKILL`
> ——reaper 轮询间隔（秒级）恒大于 cgroup 清理延迟（毫秒级），竞态结构上必输，与生产 471 轮全泄漏一致。
> M2a 附加发现：RestartSec=0 时 ExecStopPost 通配可能删到 restart 新进程刚创建的副本（生产 RestartSec=30s 无此风险）。
> 脚本验证抓到并修复的注入 bug：ExecStopPost 初版以 `>>` 追加到 service 文件尾，落入 `[Install]` 节被
> systemd 忽略（`systemd-analyze verify` 报 `Unknown key 'ExecStopPost' in section 'Install'`）——已改为
> sed 锚定 ExecStart 行前插入（[Service] 节内），重测：两遍执行 diff 一致（幂等）、ExecStopPost 落位
> [Service] 节内、`systemd-analyze verify` 干净。

> 第二个问题在本次实验中暴露为：`parse.classes.enabled=false` 生效、白名单却不生效，
> 于是本轮零解析单元 → 触发了 P2 的硬保护。硬保护把配置失效拦在了启动阶段，没有放行空跑进程。
> 默认构造器方法体只有 `super()` + `return`，而 sandbox 的织入刻意绕开 `super()`/`this()`
> （`EventWeaver` 的 `isMethodEnter` 标记），其后再无行号事件，因此不会产生 `beforeLine` 回调。

**执行方式**（目标机在线后）：

```bash
# 本机：上传产物（先复制到无中文路径，规避 pscp 乱码）
pscp -pw <pwd> <tmp>\fault-module-1.0.0.jar lys@192.168.193.129:/home/lys/upload/
pscp -pw <pwd> <tmp>\app.jar              lys@192.168.193.129:/home/lys/stress/inh1/app.jar
pscp -pw <pwd> scripts\inh-setup.sh       lys@192.168.193.129:/home/lys/
pscp -pw <pwd> scripts\inh-watchdog.sh    lys@192.168.193.129:/home/lys/

# 远程
bash /home/lys/inh-setup.sh
nohup bash /home/lys/inh-watchdog.sh inh-v1 > /home/lys/stress/inh1/watchdog.log 2>&1 &

# 核对（本机 JDBC 查库）
SELECT class_name, method_name, COUNT(*) cnt, MIN(line_no), MAX(line_no)
FROM t_fault_record WHERE tag='inh-v1'
GROUP BY class_name, method_name ORDER BY class_name, method_name;
```

> 说明：`fault-module-1.0.0.jar` 需先在本地把 `config.yml` 的 `inject.filters` 改为单块
> `scope: global` + `include: ['cn\.stress\.inh\..*']` 再打进 jar（仓库模板保持原样不动）。
> 独立 sandbox 副本 `/home/lys/sandbox-inh` 用于避免影响正式模块 jar。

