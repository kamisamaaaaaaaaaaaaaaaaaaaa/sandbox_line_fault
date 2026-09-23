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

## 织入边界执行层实测（B 系列，2026-09-09）

> 目标：把 DESIGN 3.3「织入边界」中各条目从读 sandbox 源码确认升级为**执行层实测**——验证期临时放开
> `BootJarParser` 对 native / `<clinit>` / synthetic 的收录排除（标 VERIFICATION ONLY，验证后已恢复），
> 让这些方法进表2 并注册 watch，实测 sandbox 对它们的真实行为；CGLIB 代理类另做通配实验注册
>（`*EnhancerBySpringCGLIB*`，独立最小 listener 只打日志不写表3）实测排除行为。
> 载荷：`test-lib/cn.chinaclear.fault.testlib.boundary` 场景类族（NativeCases 真实 so
> `nativeSum`、StaticInitCases 静态块、Box 桥接、OuterWithInner access$000、PlainCtor 构造器、
> BigMethodCases 8000 行超大方法 code=48041B）+ test-app 侧 ProxyTargetService（BoundaryAspect
> 切面触发 CGLIB）+ startupRunner 触发链（`[boundary]` 标记日志）。
> 执行：测试机 192.168.193.129，kill→restart 循环（tag=b1-exec2 全量口径 80 轮 → tag=b1-exec3
> 限定名单 69+ 轮），以 t_fault_record 命中记录对照"触发清单 vs 命中清单"。

| # | 场景 | 注册（注入目标） | 实测结果 | 结论 |
| --- | --- | --- | --- | --- |
| B1 | native（真实 so） | `NativeCases.nativeSum(J)J`（验证期放开收录） | so 正常调用返回（返回值 3 佐证）；表3 零 `nativeSum` 记录；调用它的 `callNative` 各行正常命中 | ✅ `rewriteNativeMethod` 只插 BEFORE/RETURN/THROWS 无 LINE 桩，beforeLine 永不回调——DESIGN 3.3.1 实证成立 |
| B2 | `<clinit>` | `StaticInitCases.<clinit>`（验证期放开收录） | 静态块执行过（`[boundary] <clinit> executing`）；表3 零 `<clinit>` 记录；同类 `read()` 正常命中 | ✅ sandbox 类结构收集阶段（ClassStructureImplByAsm）硬编码排除，实证成立 |
| B3 | bridge | `Box.compareTo(Object)`（验证期放开收录） | **有命中**：`Box.compareTo line=15`（类声明行）；原始 `compareTo(Box)` 命中业务行 26/27 | ⚠️ 桥接被织入并产生行事件，但行号指向**类声明行**——不是与目标方法同行的"重复命中"（修正 DESIGN 旧表述），而是产生语义无意义的声明行垃圾记录 |
| B4 | access$ | `OuterWithInner.access$000`（验证期放开收录） | **有命中**：`access$000 line=14`（类声明行）；`Inner.read`/`readViaInner` 业务行正常命中 | ⚠️ 同 B3：转发型 synthetic 产生声明行垃圾记录，收录只会污染覆盖率先行口径 |
| B5 | 64KB 方法体 | `BigMethodCases.bigMethod(J)J` + 同类对照 `smallMethod(J)J` | 两个方法都执行了（标记日志 23+ 次），表3 **零** `BigMethodCases` 记录 | ✅ 织入超限 → 整类回退原始字节码：**同类正常小方法一并静默**，"整类回退"实证成立 |
| B6 | CGLIB 主验证 | `ProxyTargetService.proxiedCall`（默认收录） | 调用经代理壳（`$$EnhancerBySpringCGLIB$$`），命中落**原始类**业务行 21~25；代理壳类零记录 | ✅ 织入目标是表2 里的原始类（代理类运行时生成不在表2），业务行字节码在原始类执行——命中归原始类 |
| B7 | CGLIB 实验项 | 通配 watch `*EnhancerBySpringCGLIB*`（module 侧 inject 后追加，VERIFICATION ONLY） | watch 注册成功（日志确认），代理方法被调用多次，`CGLIB PROXY LINE EVENT` **0 条** | ✅ sandbox `UnsupportedMatcher` 对代理类"注册得上、织不进"实证成立 |
| B8 | main | `TestApplication.main`（默认收录） | 启动即执行，表3 零 `main` 记录 | ✅ `UnsupportedMatcher.isJavaMainBehavior` 排除实证成立 |
| B9 | lambda 回归 | `lambda$runBoundaryChain$*` | 各 lambda 体行正常命中 | ✅ 既有结论回归通过 |

### B 系列的两个重要发现

| 发现 | 现象 | 影响 | 状态 |
| **CGLIB 调用链污染 stackHash → 集群判重失效** | 经 CGLIB 代理调用的方法命中时，调用栈含 `$$EnhancerBySpringCGLIB$$<随机hash>` 帧（如 `TestApplication$$EnhancerBySpringCGLIB$$dd42bfff.<init>`、切面 `logEntry` 的调用链），stackHash 每轮进程都不同 → 判重键永不重复 → 同一行被反复插入表3、反复 kill（实测 `BoundaryAspect.logEntry line=17` 94 条记录、`proxiedCall line=21` 29 条） | **真实缺陷**：任何被 CGLIB 代理的 bean（@Configuration/@Transactional/@Async/切面 bean）的命中判重都会失效，restart 循环永不收敛 | **已修复并回归**（G 系列）：`stack_hash` 改为对归一化帧序列取 MD5——有行号帧原样、无行号帧只取尾部固定标记，随机类名结构性不进摘要 |
| **module config.yml 模板 `inject:` 顶层键 + 子键全注释 = 启动即硬保护** | `inject:` 键存在但子键全注释 → YAML 解析为 null → S 系列"写了键却没值"硬保护在 mount 阶段拦截（错误页：`config "inject" is present but has no value`） | 模板已修复（`inject:` 整段注释，附说明）。S 系列留空硬保护在此意外场景下正确拦住了配置错误的进程 |

### 验证版改动还原记录

- `BootJarParser`：三处排除已恢复（native/`<clinit>`/synthetic），注释按 B 系列实测结论修正表述（B3/B4：声明行垃圾记录，非同行重复命中）
- `FaultKillModule`：CGLIB 通配实验注册已移除
- agent `config.yml`：`lib.whitelist` 恢复注释态（默认空）；module `config.yml`：`inject.filters` 恢复注释态（全量注入）
- 保留：test-lib boundary 场景类族、test-app 触发链与 ProxyTargetService/BoundaryAspect（`proxyBeanMethods=false`）、`scripts/native/`、`scripts/gen-big-method.ps1`——作为后续回归载荷
- 部署机（192.168.193.129）已恢复正式版 `fault-agent-1.0.0.jar` 与 `fault-module-1.0.0.jar`；验证数据保留在表3（tag=b1-exec1/2/3）与 `/home/lys/b1-logs/`

## 线程名过滤（T 系列，2026-09-10）

> module 侧新增运行期线程名过滤：`thread.include` / `thread.exclude`（正则对线程名全串匹配，
> 一行一个），先 include 选入（空 = 全选）再 exclude 排除。不通过的线程执行到注入行时
> **静默放行**——不写表3、不 kill、不打日志；过滤是运行期行为，不影响解析收录与注册数，
> 不参与零覆盖判定。判定位于 `beforeLine` 取栈之前（被过滤线程连取栈开销都省掉），
> 每次回调直接对预编译 Pattern 匹配、**不做结果缓存**（线程名可被运行时 `setName` 修改，
> 缓存会陈旧失真）。定位：排除名字不稳定的噪音线程（监控/心跳/定时任务）。
> 载荷：test-app 触发链新增显式命名噪音线程 `fault-noise-worker`，执行与主线程相同的
> 注入名单内方法 `ProxyTargetService.proxiedCall`。

| # | 配置 | 预期 | 结果 |
| --- | --- | --- | --- |
| T1 | `thread.exclude: ['fault-noise-.*']` | 噪音线程执行 proxiedCall 但不命中；主线程正常命中 | ✅ 通过（tag=t1b：表3 仅 `thread_name=main` 5 条；日志 `thread filter: include=0 exclude=1`，噪音线程场景执行过 1 次零命中） |
| T2 | `thread.include: ['main']` | 同上 | ✅ 通过（tag=t2：`include=1 exclude=0`；main 5 条、噪音线程零命中） |
| T3 | **不配置 thread 段（对照组）** | 噪音线程同样命中（证明 T1/T2 的零命中确实来自过滤，而非载荷没执行到） | ✅ 通过（tag=t3：`thread filter: none (all threads participate)`；**main 5 + fault-noise-worker 5**，每行每线程各 1 次） |
| T4 | `thread.include: ['[']`（非法正则） | 挂载阶段硬保护：落表4 后 kill，错误消息定位到配置项 | ✅ 通过（tag=t4：`invalid thread.include regex（第 0 条)"[": Unclosed character class near index 0`，每轮 exit=137） |

> T3 是对照组的意义：T1/T2 若只看"噪音线程零命中"无法区分"被过滤"与"该线程压根没执行到注入行"，
> T3 证明同一载荷在不过滤时噪音线程确实会产生 5 条命中，反向确认过滤生效。

## 调用栈摘要归一化（G 系列，2026-09-09）

> 修复 B 系列发现①：CGLIB 调用链随机类名污染 `stack_hash` → 判重失效、同行反复 kill、restart 循环永不收敛。
> 方案（用户定稿）：`stack_hash` 的输入改为**归一化帧序列**——有行号的帧原样保留（带随机类名的帧全部无行号，
> 生产栈实证）；无行号的帧只保留尾部固定标记 `(<generated>)` / `(Unknown Source)` / `(Native Method)`
> （JVM 固定字面量）。不解析、不枚举任何生成类命名，随机段结构性排除。
> `stack_text` 仍为完整原文；表结构与判重键组成不变。实现：`fault-module/.../CallStack.java`。

| # | 内容 | 预期 | 结果 |
| --- | --- | --- | --- |
| G1 | CGLIB 场景收敛（核心）：`ProxyTargetService.proxiedCall` 经代理调用，各业务行命中 | 每行每调用栈各 1 次，之后判重放行、进程继续 | ✅ 通过（tag=b1-exec7：line=21~25 **各 1 条**，对比修复前 29/22/16/10/4 条；`cglib result` 场景跑通） |
| G2 | 全链路收敛性：boundary 载荷各场景 | 全部命中行 1 条，无异常重复 | ✅ 通过（exec7 全表 `cnt=1`：Box/viaBridge/compareTo、OuterWithInner/readViaInner、NativeCases/callNative、StaticInitCases/read、PlainCtor 各 1 条） |
| G3 | 中间态佐证：修复后全量注入下推进（tag=b1-exec4，177 轮） | 无单行异常重复；多栈场景每栈 1 条 | ✅ 通过（最高单行 9 条 = 9 种调用栈各 1 条，属正确多栈语义） |
| G4 | 兼容性：hash 算法变更不迁移历史数据 | 同 tag 内等值匹配，表结构零变更 | ✅ 通过；正式版已重打并上传部署机 |

> 归一化规则的依据（生产栈实证，Spring 6 / Boot 3）：带随机类名的帧全部是 `(<generated>)`——
> 包括 `BatchAutoConfiguration$$SpringCGLIB$$0.CGLIB$dataSourceManager$10(<generated>)` 与
> `...$$SpringCGLIB$$FastClass$$1.invoke(<generated>)`；有行号的帧类名全部干净（原始类、JDK 类、
> lambda 合成方法 `lambda$xxx$N`——编译期编号，同部署版本内跨 JVM 稳定，跨版本由判重键中
> `boot_jar_hash` 天然隔离）。

### G 系列踩到的两个配置坑（已记入 config.yml 注释）

| 坑 | 现象 | 说明 |
| --- | --- | --- |
| `filters:` 写在注释掉的 `# inject:` 块下 | 变成**顶层** `filters` 键，`inject.filters` 读不到 → 名单静默失效、按全量注入跑 | 必须挂在未注释的 `inject:` 键下（b1-exec3/exec4 因此白跑全量，exec5 修正暴露） |
| `include` 正则不带 `.*` 后缀 | `...ProxyTargetService` 全串匹配不了 `...ProxyTargetService.proxiedCall` → 该类整体 skip → 零注册 → 零覆盖硬保护 | 方法键是「完全限定类名.方法名」，正则需 `...\..*` |

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

## 方法有效代码行数（L 系列，2026-09-22）

> 表2 `t_class_method` 新增 `code_lines INT NULL`：方法有效代码行数 = 该方法 `LineNumberTable` 中**不同行号的个数**
> ——注释行/空行没有字节码、天然不计入；纯 `}`、单独 `else` 等无字节码的行同样不计入（JaCoCo 口径）。
> 纯观测列：不入唯一键、不参与注入过滤与游标分页；class 未编译行号表（`javac -g:none`）时写 **NULL**，与「真的 0 行」区分。
> 实现：`BootJarParser.parseClass` 放弃 `SKIP_CODE`（改用 `SKIP_FRAMES`；**禁用 `SKIP_DEBUG`**，它会跳过行号表），
> `visitMethod` 返回 MethodVisitor 在 `visitLineNumber` 去重收集、在 `visitEnd` 产出记录（行号集合为方法级临时对象）。
> 验证环境：本机 Windows 便携 MySQL（root/root123:3306，需先手工启动 mysqld）+ 远程 192.168.193.129（lys）；
> 载荷 `test-app.jar`（内含 `test-lib-1.0.0.jar`，白名单 `test-lib.*`）。**本系列一律不挂 watchdog，一次性手动启动**
> （机上残留 `p-watchdog.sh` 等会自动拉起，会污染表2/表3 数据）。

| # | 操作 | 命令/日志预期 | 数据库预期 | 结果 |
| --- | --- | --- | --- | --- |
| L1 | 本机 mysqld 启动后对已有库执行 `ALTER TABLE t_class_method ADD COLUMN code_lines INT NULL ... AFTER desc_hash` | ALTER 成功 | 新列存在、可空、不在 `uk_method` 中；存量 201 行自动为 NULL | ✅ 通过（列 `code_lines int YES`；存量行全 NULL） |
| L2 | `TRUNCATE t_class_method` + `UPDATE t_jar_record SET status='pending',parsed_at=NULL`，以 parse-only（`mount.enabled=false`）启动 test-app | 日志 `unit stored ... classes=6 methods=39`（CLASSES，id=1695）与 `unit stored ... classes=9 methods=23`（test-lib，id=7）；无 HARD PROTECT | 表2 共 62 行（与改前排除口径一致），`code_lines` **全部非 NULL**（min=1，max=8005 = BigMethodCases.bigMethod） | ✅ 通过 |
| L3 | 远程解压 classes 与 test-lib，用 `javap -l -p` 统计每方法去重行号数，与表2 逐方法比对 | —— | DB 每行都能在 javap 侧找到**完全相同**的 (类,方法,行数) | ✅ 通过（62/62 命中，missing=0；javap 侧多出的 3 条恰为解析器排除项：`Box.compareTo` bridge synthetic、`OuterWithInner.access$000` synthetic、`NativeCases.nativeSum` native 无行号表） |
| L4 | 远程 `javac -g` 编译已知源码的 `CommentSample`（方法体内刻意插入单行注释、空行、块注释、纯 `{`、单独 `else`），打成 `cmt-lib-1.0.0.jar` 追加进 test-app 的 `BOOT-INF/lib`，白名单加 `cmt-lib.*` | 新增 LIB_JAR 单元并解析（id=1732，1 类 2 方法） | `compute` 的 `code_lines` = 手工计数 6（javap 行号 16/20/22/24/29/32），且**远小于**首末行跨度 17；构造器 = 3 | ✅ 通过 |
| L5 | 同法用 `javac -g:none` 编 `NoLineSample` 打成 `nolines-lib-1.0.0.jar` 追加，白名单加 `nolines-lib.*` | 新增 LIB_JAR 单元（id=1733，1 类 2 方法） | 该单元 2 行 `code_lines` **均为 NULL**，其他单元不受影响（仍 0 个 NULL） | ✅ 通过 |
| L6 | 同配置重启（已完成单元） | 日志 `unit already completed, skip`（CLASSES + test-lib + 两个新 lib） | 表2 行数与 `code_lines` 均不变（仅新增 L4/L5 两个新单元） | ✅ 通过 |
| L7 | 改回 `mount.enabled=true` 启动（tag=lc3） | 挂载成功 → 应用启动中命中注入行 → 日志 `KILL current process` | 表3 新增 1 条：`tag=lc3`、`TestApplication.<init>`、line=32、thread=main、fault_seq=1（证明 SELECT/映射改动未破坏注入链路） | ✅ 通过 |

### L3 的独立复核方法（无源码也能对账）

```bash
# 远程：解压 classes 与嵌套的 test-lib，逐 class 取 javap 行号表
javap -l -p <class> | awk '方法头=以两空格开头、含 ( 且以 ; 结尾；行号行=line N: M'
# 输出 类名|方法名|去重行号数，与下面 SQL 结果做多重集合比对
SELECT CONCAT(class_name,'|',method_name,'|',code_lines) FROM t_class_method WHERE unit_id IN (...);
```

> 两个脚本坑（已修正，复核脚本不入库）：① 带 `throws` 的签名不以 `);` 结尾，方法头正则要放宽为「含 `(` 且以 `;` 结尾」；
> ② `static {};` 出现在方法之后时，必须在它出现前**先 flush 上一个方法**，否则紧邻其前的方法会被吞掉（首轮因此漏了 `callNative`/`read` 两条）。

### L 系列踩坑

| 坑 | 现象 | 说明 |
| --- | --- | --- |
| 用 `zip`（默认压缩）把 lib jar 追加进 bootJar | 应用启动报 `Unable to open nested entry 'BOOT-INF/lib/cmt-lib-1.0.0.jar'. It has been compressed and nested jar files must be stored without compression` | 嵌套 jar 必须 STORED：追加时用 `zip -0` |

### 验证后还原（2026-09-22 已完成）

- 远程：`test-app.jar` 已从 `test-app.jar.bak-lc` 还原（两个临时 lib 已移除）；agent 内 `config.yml` 已还原为仓库默认版（可选配置全注释、mount 默认 true）；临时脚本/目录 `/tmp/lcsrc`、`/tmp/lcjavap`、`/home/lys/lc-run` 已删除；无残留 java 进程
- 数据库：验证单元 1732/1733 及其表2 行已删除（`fault-agent-1.0.0.jar.bak-lc` 备份保留在 `/home/lys/`）；其余数据保留
- 部署机上的 `fault-agent-1.0.0.jar` / `fault-module-1.0.0.jar` 为**含本功能的新版**

## 行覆盖率与未命中行归因（L8 / L9，2026-09-22）

> 要回答的问题：`code_lines`（去重行号数）统计出来的行，**是不是每一行都能被 sandbox 捕获成故障行**。
> 方法：应用包全部方法（`cn.chinaclear.fault.testapp.*`，unitId=1695，**39 方法 / code_lines 合计 180 行**）全量注入，
> 3 个实例（端口 8081/8082/8083，同 tag `lc-cov1`）由 watchdog 反复拉起推进；后期按用户要求**只统计 main 线程**
> （module 侧加 `thread.include: ['main']`，Tomcat 线程不计）。
> 判停：三实例重启次数停止增长（66 / 66 / 64）、进程能正常启动并长跑 = main 路径上能命中的行已全部命中并放行。

### 推进机制与并行（本轮实测认识）

- 命中即 kill，**一个进程周期只推进一行**；放行后进程继续往下执行，遇到下一个未命中行再命中。
- **并行有效**：同 tag 下同一行只被抢占一次，抢到的实例被 kill，**其余实例因冲突放行继续往下走到下一个未命中行**，
  因此 N 个实例能在同一时间窗口推进多行（本轮 3 实例）。
- **并行的副作用**：Tomcat 线程名含端口且随请求序号漂移（实测出现 18 种 `http-nio-808x-exec-N`），
  同一行在不同线程名下会各命中一次，白白多耗轮次 → 这也是收窄到「只统计 main 线程」的直接原因。

### L8 行覆盖率（main 线程口径）

| 方法 | code_lines | 命中 | 说明 |
| --- | --- | --- | --- |
| `TestApplication.lambda$startupRunner$0` | 15 | 15 | 启动 runner 主体，全覆盖 |
| `TestApplication.runBoundaryChain` | 11 | 11 | 全覆盖 |
| `OrderService.auditAll` | 7 | 7 | 全覆盖 |
| `TestApplication.printIsolationCheck` | 9 | 7 | 缺 catch 分支 2 行（见 L9） |
| `TestApplication.safe` / `lambda$runBoundaryChain$7` | 5 / 5 | 5 / 5 | 全覆盖 |
| `ProxyTargetService.proxiedCall` | 5 | 5 | 全覆盖 |
| `BoundaryAspect.logEntry` | 2 | 2 | 全覆盖 |
| `lambda$runBoundaryChain$12` | 8 | 6 | 缺 InterruptedException catch 2 行 |
| `OrderService.lambda$auditAll$0` | 7 | 6 | 缺 reject 分支 1 行 |
| `OrderService.<init>` / `TestApplication.<init>` / `OrderController.<init>` | 6 / 3 / 3 | 5 / 2 / 2 | 各缺构造器首行 1 行 |
| 其余 10 个小 lambda / `amountChecker` / `idGenerator` / `startupRunner` | 共 16 | 16 | 全覆盖 |
| `lambda$runBoundaryChain$11` | 4 | 0 | 噪音线程 Runnable，非 main 线程执行 |
| `TestApplication.main` | 2 | 0 | sandbox 硬编码排除 java main |
| `OrderController` 的 create/pay/count/audit | 18 | 0 | 仅 HTTP 线程执行 |
| `OrderService` 的 create/pay/count | 27 | 0 | 仅 HTTP 线程执行 |
| `PayService.doPay` / `refund` / `<init>` | 15 / 8 / 1 | 0 | 仅 HTTP 线程执行（`<init>` 为默认构造器） |
| `ProxyTargetService.<init>` / `BoundaryAspect.<init>` | 1 / 1 | 0 | 默认构造器（只有 super() 行） |
| **合计** | **180** | **95** | **52.8%** |

> 另有一处口径交叉验证：远程 `javap -l -p` 统计的 LineNumberTable 去重行数**合计正好 180**，与表2 `SUM(code_lines)` 完全一致。

### L9 未命中 85 行的逐类归因

| 类别 | 行数 | 具体行 | 依据 |
| --- | --- | --- | --- |
| ① 只在 Tomcat 线程执行（本次口径不含） | 68 | Controller 4 方法 18 行；`OrderService.create/pay/count` 27 行；`PayService.doPay/refund` 23 行 | 这些方法只在 HTTP 请求线程被调用；停止 curl 驱动 + `thread.include=main` 后不再产生记录 |
| ② 构造器首行（`super()`/`this()` 所在行） | 6 | `TestApplication.<init>\|31`、`OrderService.<init>\|23`、`OrderController.<init>\|19`、`PayService.<init>\|9`、`ProxyTargetService.<init>\|17`、`BoundaryAspect.<init>\|13` | sandbox 织入刻意绕开 `super()`/`this()`，其后才有行号事件；**推论：默认构造器（code_lines=1）整方法永不命中**（`PayService`/`ProxyTargetService`/`BoundaryAspect` 三个 `<init>` 即此） |
| ③ java main 方法被 sandbox 排除 | 2 | `TestApplication.main\|36`、`main\|37` | sandbox 在 matching 阶段硬编码排除 java main（`UnsupportedMatcher.isJavaMainBehavior`），永不织入 |
| ④ 异常 / 分支路径未执行 | 5 | `printIsolationCheck\|83,84`；`lambda$runBoundaryChain$12\|133,134`；`lambda$auditAll$0\|83` | `printIsolationCheck`：字节码 `73: goto 102` / `76: astore_0` 起为 `catch(Throwable)` 块，`Class.forName` 成功故不走；`λ$runBoundaryChain$12`：Exception table `from 20 to 27 target 30`，133/134 即 `InterruptedException` 的 catch（`Thread.join` 未被打断）；`λ$auditAll$0`：字节码 `31: ifle 38` / `41: ifeq 72`，83 行是 `amount.signum() <= 0` 的 reject 分支，审计金额均为正 |
| ⑤ 非 main 线程执行（被线程过滤静默放行） | 4 | `lambda$runBoundaryChain$11\|126-129` | 该 lambda 是噪音线程 Runnable（体内打印 `noise thread executing cglib scenario` 并调 `proxiedCall`），不在 main 线程执行；`thread.include=main` 下静默放行 |

### 结论

- **静态上，LNT 的每一行都会被 sandbox 插桩**（`EventWeaver` 对每个 `visitLineNumber` 插 `onLine`）：本轮命中的 95 行**全部落在** javap 统计的 180 行静态集合内，无一条集合外命中；且 javap 总数与 `SUM(code_lines)` 相等。
- **但 `code_lines` 是「静态可注入行上限」，不是「必然产生故障的行」**。某行是否真的产生表3 记录，取决于四件事：
  ① 运行时是否执行到（分支 / 异常路径覆盖，如类别 ④）；② 是否落在 sandbox 硬编码排除的方法里（java main，类别 ③）；
  ③ 是否是被织入绕开的构造器首行（类别 ②，默认构造器因此整方法不可命中）；④ 线程维度（线程过滤或该行只在其他线程执行，类别 ①⑤）。
- 实践含义：用 `code_lines` 估「本轮最多可能产生多少条故障/需要多少个进程周期」时要按上述四类打折扣；本应用 main 路径实测 95/180。

### 附：行号归属规则（哪些行会进入 `code_lines` 统计）

> `LineNumberTable` 是 javac 生成的「字节码 pc → 源码行号」映射表：**每条真实生成的指令**被标注为"来自源码第几行"，
> 没有指令生成的位置（注释、空行、纯语法行）就没有条目。所以行号不是关键字的编号，而是**指令的归属行**。
> 本表全部来自本项目实测字节码。

| 源码构造 | 是否有行号条目 | 实测依据 |
| --- | --- | --- |
| `if (cond)` 行 | **有** | 条件跳转 `ifeq`/`ifle` 落在该行：`lambda$auditAll$0` 的 80 行 = `iload_2` + `ifeq 72` |
| `} else {` / 单独 `else` | 通常**没有** | 分支内第一条指令落在 else 块内的语句行：`lambda$auditAll$0` 缺 82 行，reject 分支首条指令在 83 |
| `else if (...)` | **有** | 该行承载新的条件跳转指令 |
| `try {` 独占一行 | 通常**没有** | try 块内第一条指令落在块内首个语句行 |
| `} catch (X e) {` | **有** | handler 入口的 `astore`（异常对象存入局部变量）在该行：`printIsolationCheck` 83 行 = pc 76 `astore_0`；`lambda$runBoundaryChain$12` 133 行 = pc 30 `astore_2` |
| `finally` 块内语句 | **有** | 编译器把 finally 复制到各出口路径，行号仍指向 finally 内源码行 |
| `throw xxx;` | **有** | `athrow` 指令 |
| try 块结尾 `}` | 可能**有** | 块结束的 `goto` 落在该行：`printIsolationCheck` 85 行 = pc 73 `goto 102`；`lambda$runBoundaryChain$12` 135 行 = pc 27 `goto 37` |
| 方法结尾 `}`（**无显式 return**） | **有** | javac 补的隐式 `return` 落在该行：`printIsolationCheck` 86 行 = pc 102 `return`；`lambda$startupRunner$0` 68 行 = pc 158 `return` |
| 方法结尾 `}`（**有显式 `return xxx;`**） | **没有** | 返回指令标注在 return 语句行，结尾 `}` 不再产生指令：`OrderService.count` 的 LNT 只有 65/66/67（67 = pc 36 `ireturn`，即 `return size;` 行）；`amountChecker` 只有 47/48（48 = pc 9 `areturn`） |
| 显式 `return xxx;` 行 | **有** | 表达式求值 + `ireturn`/`areturn` 等均在该行（同上）；void 的裸 `return;` 同理 |
| 普通块结尾 `}` | 通常**没有** | 无指令生成 |
| 注释 / 空行 / 单独 `{` | **没有** | 无指令生成。L4 的 `CommentSample.compute` 实测只有 16/20/22/24/29/32 六行，注释、空行、单独 `{`、`}`、`else` 全部不在内 |

### 附 2：try/finally 的合成出口（实证）

javac 会把 finally 的代码**复制**到正常路径与异常路径各一份，并在结构末尾合成 `goto`（跳过 handler）与 `athrow`（异常路径重新抛出）。
实测样例（`/tmp/lcfin`，`javac -g`，行号见下）：`5 plainFinally(){ / 6 try{ / 7 body / 8 }finally{ / 9 finally / 10 } / 11 }`，
`13 returnInTry(){ / 14 try{ / 15 return 1 / 16 }finally{ / 17 finally / 18 } / 19 }`，
`21 returnAfterTry(){ / 22 try{ / 23 body / 24 }finally{ / 25 finally / 26 } / 27 return 2 / 28 }`。

| 方法 | LineNumberTable（行:pc） | 去重行 | 说明 |
| --- | --- | --- | --- |
| `plainFinally()`（void，无显式 return） | `7:0, 9:8, 10:16, 9:19, 10:28, 11:30` | 7, 9, 10, 11 | finally 两份副本都标 **9**；合成的 `goto`(pc16) 与异常路径 `athrow`(pc29) 都标在 **10**（finally 块结尾 `}` 行）；隐式 `return`(pc30) 标在 **11**（方法尾 `}`） |
| `returnInTry()`（try 内 `return 1`） | `15:0, 17:2, 15:10, 17:12, 18:21` | 15, 17, 18 | `ireturn` 标在 **15**（return 语句行），方法尾 `}`（19）**不计入**；异常路径 `athrow` 标在 **18**（结尾 `}`） |
| `returnAfterTry()`（finally 后 `return 2`） | `23:0, 25:8, 26:16, 25:19, 26:28, 27:30` | 23, 25, 26, 27 | 同上：`goto`/`athrow` 落在 **26**，方法尾 `}`（28）**不计入** |

结论：

- finally 的字节码被复制两份，但**行号都指向 finally 块内同一源码行** → 去重后**不会**因复制而虚增行数。
- 编译器合成的出口（`goto` 与异常路径的 `athrow`）落在 **try/finally 结构的结尾 `}` 行** → 该行**会**进入统计，
  即使运行期从未发生异常（这行能否命中取决于是否真走到异常路径的 `athrow`）。
- 方法尾 `}` 仍遵循上一节的规则：**有显式 return → 不计入；无显式 return（隐式 return）→ 计入**。
- 顺带确认：`try {` 行（6/14/22）与 `} finally {` 行（8/16/24）**均无条目**（finally 内首条指令落在 9/17/25）。

两个易混淆点：

- **同一行号可出现多次**：`printIsolationCheck` 的 81 行同时对应 pc 42 与 pc 70（中间插了别的行的指令），
  故 `code_lines` 取**去重**行号数，它既不等于指令段数、也不等于源码物理行数。
- **"看起来只是语法"的行也会计入**：`if` / `catch` / 方法尾 `}` 分别承载了条件跳转、`astore`、`return`，
  所以都在统计内；被排除的只有**完全不产生指令**的行。这正是该口径叫"有效代码行"（同 JaCoCo）而非"物理行数"的原因。

### 本轮踩坑与还原

| 坑 | 现象 | 处理 |
| --- | --- | --- |
| Tomcat 线程名漂移 | 同一行在 `exec-1/2/4/5…` 各命中一次，记录数涨但覆盖率不涨 | 收窄到 main 线程口径（用户决策） |
| 并行实例端口 | 多实例必须不同 `--server.port` | 8081/8082/8083 |
| watchdog 无限拉起 | 不主动停会一直重启 | 判停后 `pkill` watchdog + java，并确认无残留 |

## add-agent-to-service.sh 支持修改 RestartSec（S 系列，2026-09-22）

> 脚本新增第 4 个可选位置参数 `[重启间隔RestartSec]`：提供时替换 service 的 `RestartSec=<值>`
> （值限「数字+可选时间单位后缀」，如 `5` / `5s` / `500ms` / `1min`，拒绝空格与特殊字符防止 sed 注入）；
> 不提供则不修改。原 service 无 `RestartSec=` 行但存在 `Restart=` 行时，插入到 `Restart=` 之后；
> 两者都没有时警告跳过。故障注入每命中一行都 kill 进程，`RestartSec` 决定两次故障之间的间隔时间。
> 测试环境：129，`focus@.service` 模板副本（不动真实文件）。

| # | 操作 | 预期 | 结果 |
| --- | --- | --- | --- |
| S1 | 传第 4 参数 `5s` | `RestartSec=30s` 被替换为 `5s`；-javaagent / -Dfault.tag / 副本清理三行配置不受影响 | ✅ |
| S2 | 不传第 4 参数 | `RestartSec` 保持原值不变 | ✅ |
| S3 | 传非法值 `a b`（含空格） | 启动即拒绝（rc=1），service 未被修改 | ✅ |
| S4 | service 无 `RestartSec=` 行（有 `Restart=`） | 插入 `RestartSec=10s` 到 `Restart=` 之后 | ✅ |
| S5 | service 无 `RestartSec=` 与 `Restart=` 行 | 警告跳过、正常完成（不再误报"修改未生效"） | ✅ |
| S6 | 传非法值 `5s;rm`（含分号） | 启动即拒绝 | ✅ |

### 还原

远程测试目录 `/tmp/svc-test` 与测试脚本已删除；`/home/lys/add-agent-to-service.sh` 为新版（该路径即脚本部署位置）。

### v2：轮次 tag 必填 + 支持多个 JVM 参数（2026-09-22）

> 第 5 个参数起为 JVM 参数（`-Dkey=value` 或 `key=value` 自动补 `-D`），数量不限：ExecStart 已有同名
> `-Dkey=` 时替换其值，否则插到 `-jar` 之前；值限「字母数字与 `._:/=,+@%^*?!~-`」，拒绝空格与
> shell 特殊字符（`& | \ 引号 $ 反引号 分号`）防注入。**轮次 tag 改为必填**（agent 硬保护要求，
> 缺失时模块挂载完即 kill）——原提示把它标为可选是错误的；`RestartSec` 仍为可选。

| # | 操作 | 预期 | 结果 |
| --- | --- | --- | --- |
| S7 | 缺 tag（只传 2 参数） | usage 报错 | ✅ |
| S8 | tag 为空字符串 | 「轮次tag 必填」报错，service 未被修改 | ✅ |
| S9 | `tag 5s -Dframework.zk.url=xxx -Dconn.timeout=30` | `RestartSec=5s` + 两个 JVM 参数追加到 `-jar` 前 | ✅ |
| S10 | 同参数重复执行（zk.url 改值） | 替换而非重复追加（`grep -c` = 1） | ✅ |
| S11 | `-Djava.io.tmpdir=other`（3b 已插过 tmpdir） | 替换其值；3b 的原警告（核对 ExecStopPost 目录）保留 | ✅ |
| S12 | 不带 `-D` 前缀 `x.y=1`（RestartSec 缺省） | 自动补 `-D` 追加；`RestartSec=30s` 保持不变 | ✅ |
| S13 | 非法：`-Dnovalue`（无等号）/ 值含空格 / 值含 `$(`、反引号 | 启动即拒绝（白名单报错） | ✅ |

- 已还原：module jar 内 `config.yml` 恢复为仓库默认版（验证用的 `inject.filters` 与 `thread.include` 均已移除，仓库模板未改动）；
  远程临时脚本、`/home/lys/lc-cov`、`/tmp/lclnt` 等临时目录已删除；无残留 java/watchdog 进程；`test-app.jar` 本轮未改动。
- 数据保留：表3 中 `tag=lc-cov1` 的记录（含前期 Tomcat 线程部分）保留备查，统计时已按 `thread_name='main'` 过滤。

## 特殊情况行号口径与可命中性（L10 / L11，2026-09-22）

> 目的：回答「agent 解析出的 `code_lines` 行集合是否符合预期」与「sandbox 故障能否真正挂上这些行」。
> 载荷：**自制 mini bootJar**（`special-cases.jar`）——样例类放 `BOOT-INF/classes/`，复用 `test-app.jar` 内的
> `org/springframework/boot/loader/*`（MANIFEST：`Main-Class=JarLauncher` / `Start-Class=lcsample.SpecialMain`），
> 样例覆盖全部特殊语法结构；**不动 test-app.jar**。jar sha256 在解析与 javap 两侧核对一致（`30fb47f1…`）。
> 流程：先 parse-only 验证解析口径（L10），再注入 `lcsample.*` + `thread.include: ['main']`，
> 3 实例并行 watchdog 推进（tag=`lc-sc1`，约 3 分钟收敛）验证命中（L11）。

### L10 解析口径（unitId=2038，3 类 23 方法 / 92 行）

逐方法基数校验：javap 去重行数 == 表2 `code_lines`，**23/23 全部一致，总和 92 = 92**。静态行集合逐条核对：

| 方法（SpecialCases 除非注明） | 静态行 | 核对点 |
| --- | --- | --- |
| `commentsAndBlanks` | 13, 17, 18 | 单行注释 12、空行 14、块注释 15-16 **均不含** |
| `branch` | 22, 23, 24, 25, 27 | `} else {`（26）**不含**；`else if`（24）**含** |
| `elseOnOwnLine` | 32, 34, 38 | 单独 `{`/`}`/`else`（33/35/36/37/39）**全不含** |
| `catchPath` | 44, 45, 47, 48, 49 | `try {`（43）**不含**；`} catch` 行（48）**含**（handler 入口 `astore`） |
| `finallyOnly` | 55, 56, 58, 60, **61**, **62** | try/finally 结构尾 `}`（61）**含**（合成 `goto`/`athrow`）；方法尾 `}`（62）含（隐式 return） |
| `allThree` | 66, 67, 69, 70, 71, 73, **74** | 结构尾 `}`（74）含；有显式 return → 方法尾 `}`（75）**不含** |
| `explicitReturn` / `implicitReturn` | 78, 79 / 83, **84** | 显式 return → 方法尾 `}` 不含；隐式 → 含 |
| `bareReturn` | 87, **88**, 90, **91** | 裸 `return;`（88）含；`}`（89）不含；方法尾 `}`（91）含（隐式 return） |
| `sameLineTwice` | 94, 95 | 一行三条语句只算 1 行 |
| `chainedLines` | 99, 100, 101, 102 | 链式调用折行，每行都有 |
| `multiLineStatement` | 106, **109** | **跨行语句只标起始行**，续行 107/108 不含（新发现） |
| `switchCase` | 113, 115, 117, 119 | **`case 1:` 等标签行（114/116/118）不含**，只有 case 内语句行含（新发现） |
| `loop` | 124, 125, 126, 128 | for 行含，块尾 `}`（127）不含 |
| `lambdaBody` / `lambda$lambdaBody$0` | 132, 133 / 132 | lambda 体是独立合成方法 |
| `ternary` / `neverCalled` | 137 / 143 | 三元表达式算一行 |
| `SpecialCases.<init>` | 7, 8, 9 | 7 行 = 隐式 `super()`（静态含，能否命中见 L11） |
| `nativeOp` / `SpecialAbstract.abstractOp` | **不在表2** | native / abstract 解析阶段即排除 |
| `SpecialMain.main` | 5..32 共 28 行 | 静态含（能否命中见 L11） |

### L11 可命中性（tag=lc-sc1）

- 结果：命中 71 条记录 / **58 个不同行**；**`命中集合 ⊆ 静态集合`（0 条集合外命中）**；覆盖率 **58 / 92 = 63.0%**。
- 差集 34 行，**逐条归因且与预期完全一致，无异常项**：

| 类别 | 行数 | 明细 |
| --- | --- | --- |
| java `main` 被 sandbox 硬编码排除 | 28 | `SpecialMain.main` 全部（5-32） |
| 构造器首行（隐式 `super()`） | 3 | `SpecialCases.<init>\|7`、`SpecialMain.<init>\|3`、`SpecialAbstract.<init>\|3` |
| 方法未被调用 | 2 | `neverCalled\|143`、`SpecialAbstract.concreteOp\|7`（main 未调用 SpecialAbstract） |
| 合成 `athrow` 不可达 | 1 | `allThree\|74`：try/catch/finally 尾 `}` 的 `athrow` 只在 catch 之后再抛异常才走，而 catch 已捕获全部异常 → 不可达 |

- **对照样例**：`finallyOnly` **6/6 全命中**——`finallyOnly(true)` 构造异常 → 异常路径的 finally 副本（60）→
  **合成 `athrow`（61）** → 异常传播到 main 被 catch。证明「try/finally 尾 `}` 上的合成 `athrow` 确实能挂上故障」。
- 其余全部方法（branch / elseOnOwnLine / catchPath / chainedLines / loop / switchCase / lambda 等）**行级全覆盖**。

### 结论

- 解析口径：静态行集合与 `code_lines` 完全一致（逐方法基数 + 逐行），`code_lines` 可安全用作覆盖率分母。
- 命中：`命中 ⊆ 静态` 成立；未命中的每一行都能归因到四类已知机制（sandbox 排除 java main、`super()` 行、未被调用、不可达路径），
  **不存在"解析有行号但 sandbox 挂不上且无法解释"的行**。
- 三重校验方法（后续复用）：① 逐方法基数（javap 去重行数 == code_lines）② 子集（命中 ⊆ 静态）③ 同源（解析与 javap 用同一 jar，sha256 核对）。

### 还原

agent / module 内 `config.yml` 均从备份还原为仓库默认版；`special-cases.jar`、样例源码目录、watchdog 目录、临时脚本已删除；无残留 java 进程。

## 重载方法按 method_desc 区分覆盖率（L12，2026-09-22）

> 背景：方法级覆盖率若按 `(class, method_name)` 合并，同名重载会被并成一行，无法区分各重载的覆盖情况。
> 表3 的 `method_desc` 记录了命中重载的完整签名，可按重载拆分统计。
> 载荷：样例新增 `lcsample.Overloaded`（`calc(int)` 与 `calc(String)` 两个重载，各 1 行有效代码），
> jar 重建后 sha256 变化 → 新单元 2287（4 类 26 方法 / 98 行）；tag=`lc-sc2`，3 实例并行推进。

| # | 验证点 | 预期 | 结果 |
| --- | --- | --- | --- |
| L12-1 | 解析：两个 `calc` 重载在表2 为两行（`desc_hash` 不同） | 是 | ✅（`calc` × 2 行，各自 1 行 code_lines） |
| L12-2 | 命中：两个重载各自命中各自的行，表3 `method_desc` 分别为 `(I)I` / `(Ljava/lang/String;)I` | 是 | ✅（`calc(int)` → line 6；`calc(String)` → line 10） |
| L12-3 | 方法级 SQL 按 `desc_hash` 拆分 | 两个 `calc` 各一行，各 1/1 = 100% | ✅ |
| L12-4 | 类级合并 | `Overloaded` 3 行 / 2 命中 = 66.7%（`<init>` 的 `super()` 行未命中） | ✅ |
| L12-5 | 整体对账 | 静态 98；命中 60；差集 38 = main 31 + `super()` 首行 4（SC/Main/OV `<init>`）+ 未调用 2（`neverCalled`/`concreteOp`）+ 不可达 `athrow` 1（`allThree\|74`） | ✅ |

### L12 附带发现：不同调用点 → 不同调用栈 → 各占一次故障机会

`SpecialCases.allThree` 的 66 行与 73 行**各出现两条表3 记录**——不是同一行多插桩点，而是
`allThree(false)` 与 `allThree(true)` 在 main 里的**调用点行号不同**（`main:17` vs `main:18`），
调用栈文本不同 → `stack_hash` 不同 → 判重键不同 → 各占一次独立的故障机会（`fault_seq` 均为 1）。
这是判重键按调用栈区分的既定语义：估算故障总量时，"同一行"要按**不同调用路径**分别计数。

### 方法级覆盖率的两种口径（README 6.5 已同步）

- **按重载拆分**（默认）：`GROUP BY (class, method, desc_hash)`，分子按表3 `method_desc` 匹配到具体重载；
- **按方法名合并**：重载行号互不重叠，类 / jar 级合并不重复计数；
- 分母始终是表2 `code_lines`（含全部重载行）。

## 行级抛异常可行性（X 系列，2026-09-23）

> 背景：行级抛异常方案（见 `PLAN-line-exception-injection.md`）的执行机制选型验证。
> 载荷：自制 `ThrowTestApp`（`work()` 内 5 个有效行，目标行 = `int len = s.length();`，行号 32），
> main 循环调用并 catch Throwable，打印 `[CAUGHT] type/msg/at`；业务收到异常 = 注入生效。
> 模块：一次性测试模块 `ft-throw-test`（`/home/lys/.sandbox-module/`，验证后已删），三种 listener 形态
> （pce / plain / @Interrupted）按行号命中抛异常，每模式限 3 次。
> 执行环境：129 正式 sandbox（`/home/lys/sandbox`，1.4.0）；**全部验证完成后 `sandbox-core.jar` 已从备份还原
>（cmp 一致）、测试目录与模块 jar 已删除、无残留进程**。

| # | 内容 | 预期 | 结果 |
| --- | --- | --- | --- |
| X0 | sandbox-core.jar 内含 API 摸底 | fat jar：含 sandbox-api、`com/.../core/enhance/annotation/Interrupted.class`、`javax/annotation/Resource.class`；`Spy` 在独立 `sandbox-spy.jar` | ✅（编译 classpath 用 core+spy 两 jar） |
| X1 | **模块能否加载 sandbox-core 类**（路线 D 前提） | 未知 → 实测定 | ✅ **不可见**：模块内 `Class.forName("com.alibaba.jvm.sandbox.core.enhance.annotation.Interrupted")` 抛 `ClassNotFoundException`（ModuleJarClassLoader 只暴露 api/common-api，core 类被隔离） |
| X2 | T1 `beforeLine` 里 `ProcessController.throwsImmediately(ex)`（原生 API，零改动） | 无效（Ret 被 handleOnLine 丢弃） | ✅ 业务全程 `[NORMAL]`（PCE 被 listener 内 catch 后重抛给 sandbox，未传播） |
| X3 | T2 `beforeLine` 里直接 `throw new RuntimeException`（无 @Interrupted） | 被 `handleEvent` 吞（WARN 日志） | ✅ 业务全程 `[NORMAL]`；sandbox 日志 `event|LINE|...|1008 occur an error` |
| X4 | T3 listener 带 `@Interrupted`（core 注解） | 异常穿透到业务该行 | ✅ **不成立**：注解类运行期不可见（X1），JVM 解析注解时按定义类 loader 找不到类型即静默丢弃 → `isAnnotationPresent` 恒 false → 异常仍被吞（业务 `[NORMAL]`）。**模块侧自带同名注解类的变体同样不可行**：`Class.isAnnotationPresent` 的注解映射以 `Class` 对象为键，两个 loader 的同名注解类不相等 |
| X5 | T4 **core 最小补丁**：`EventListenerHandler.handleOnLine` 消费 `Spy.Ret`，`RET_STATE_THROWS` 时 `throw (Throwable) ret.respond`（原版丢弃返回值；补丁 2 行）+ 模块置 `Spy.isSpyThrowException=true`（Spy 在 bootstrap 全局唯一，可直接反射改）+ `ProcessController.throwsImmediately(ex)` | 业务在目标行收到异常，进程存活 | ✅ **成立**：`[CAUGHT ] i=19 type=java.lang.RuntimeException msg=FT-PCE#1`（连续 3 次，seq=1/2/3），进程继续 `[NORMAL]`；注入后无新增 `occur an error`、无 `ERROR process-stack`（异常经方法体 THROWS 桩回调后 rethrow，process stack 平衡） |
| X6 | 补丁方式 | 单类重编译 + `jar uf` 替换 | ✅ 注意点：补丁类必须保留原版 `getSingleton()`（`SpyUtils.init` 反射依赖，漏掉则 attach 阶段 `NoSuchMethodError`）；连内部类 `EventListenerHandler$1.class` 一并替换 |

### X 系列结论

- **路线 D（`@Interrupted` 穿透）在官方 1.4.0 上不可行**：sandbox 有意对模块隔离 core 类，注解无法被模块侧解析。
- **路线 B 存在比方案预估更小的形态**：只改 `EventListenerHandler.handleOnLine`（消费 Ret 并 rethrow）一处，
  **不需要**改 `Spy` 接口、不需要改 `EventWeaver`（异常从 spy 调用点冒出，落点天然就是"该行第一条指令之前"）；
  配套运行期设置 `Spy.isSpyThrowException=true`（bootstrap 全局唯一，模块反射可改）。
- **全局开关风险**：`isSpyThrowException=true` 对所有 listener 生效（sandbox 自身模块的异常也会穿透到业务），
  生产化需评估改为补丁内定向判断（如按 namespace/listenerId 过滤后 rethrow）。
- 模块侧 API 写法：`beforeLine` 内 `ProcessController.throwsImmediately(ex)`；`ProcessControlException` 是受检异常，
  `beforeLine` 不声明 throws，需 sneaky-throw 抛出。

