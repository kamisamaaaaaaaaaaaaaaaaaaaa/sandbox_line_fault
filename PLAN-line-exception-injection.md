# 行级抛异常注入 —— 方案设计（未实施）

本文只回答三件事：这一行**能不能抛**、**能抛什么**、**怎么真的抛出去**，并给出落地顺序。
验证结论归 TEST_CASES.md；本文中的"待验证"项是落地前必须先确认的事实。

---

## 1. 目标与语义定义

### 1.1 现有能力
行级 `kill`：执行到某行、且该行尚未执行任何指令时（`beforeLine`），抢占成功即终止进程。

### 1.2 新增能力（本方案）
行级 `throw`：执行到某行、且该行尚未执行任何指令时，抛出一个异常对象，进程继续运行。

注入点语义与现有 `beforeLine` 完全一致：**该行第一条指令之前**。由此确定三条推论：

- 该行的副作用不发生（与"这一行执行到一半崩了"区分开，后者做不了，因为行内指令不可分割地推进）。
- 一行源码可对应多条指令（`a.b().c()`），注入点取该行第一条指令之前；一行也可能在方法里出现多次（循环体、多分支），LineNumberTable 里同一行号有多个 label，取全部位置的并集。
- 构造器 `super()` / `this()` 之前不可注入（JVM 校验：未初始化 `this` 的帧不允许 `athrow`）→ 与现有"绕开 `super()`"的口径一致。

### 1.3 "每一行只抛出可能抛出的异常"的形式化
对每一行 L 求一个**候选异常类型集合** `CanThrow(L)`：

```
CanThrow(L) = Implicit(L)      // 该行自身指令在 JVM 规范下可能抛的（NPE / AIOOBE / ASE / CCE / 算术 / …）
            ∪ Explicit(L)     // 该行显式 athrow 的类型
            ∪ Callee(L)       // 该行调用的方法可能传播上来的（声明的 checked + 其内部可能的运行时异常）
```

注入约束：选出 `E ∈ CanThrow(L)`；`CanThrow(L) = ∅` 的行不注入。

**必须提前说清的三个边界**（决定了这个目标能做到什么程度）：

1. `CanThrow(L)` 的精确计算不可判定（Rice 定理）。工程目标是**把 false positive 压到可接受**，而不是零。
2. 更真实 ⇄ 覆盖率更低：加入可行性过滤后，一部分行永远不会被注入。覆盖率口径要从"有效代码行"改为"**可注入行**"，分母会变小，这不是退步。
3. "能不能抛"与"抛了有没有用"是两件事。后者（异常是否被本方法/调用链吞掉）可静态判定且更影响演练效果，见 2.5。

---

## 2. 难点一：确定某一行是否真的会抛异常

### 2.1 用户的原始问题
`if (x != null) { x.foo(); }` —— 走到 `x.foo()` 时不可能空指针，行级注入不该在这一行抛 NPE。

**结论：能解决**，且是方案的主战场。手段是**流敏感 + 分支敏感的空值抽象解释**（L1）。

### 2.2 分层方案（逐层叠加，各层独立可开关）

| 层 | 内容 | 解决什么 | 残留 false positive | 成本 |
|----|------|----------|---------------------|------|
| **L0 语法/指令级** | 按行聚合指令类别：解引用类、数组访问类、`checkcast`、`idiv/irem`、`athrow`、`invoke*`、`new` | 给出"原理上可能"的粗集合 | 全部保留（`if (x != null)` 仍判"可能"） | 极低（解析时顺带） |
| **L1 空值数据流** | 方法内前向不动点，抽象域 `{NULL, NONNULL, UNKNOWN}`，分支敏感 | **用户的例子**：空值检查后的解引用判为"不可能"；已判空的分支内判定 | 字段重读、跨方法返回值、参数 | 中（约 400~600 行） |
| **L2 过程间类型集** | 对被调方法递归求 `CanThrow`，作为 `Callee(L)`；同 jar 内可解析的精确，不可解析的退化为 `UNKNOWN_REMOTE` | 给出**具体异常类型**（不只是 NPE 这种类别） | 反射、动态代理、多态分派、JDK 内部 | 中高（全 jar 记忆化，深度封顶） |
| **L3 值域分析**（可选，建议不做） | 下标 vs `arraylength`、除数非零、`checkcast` 类型已知 | AIOOBE / 算术 / CCE 的精化 | — | 高、收益低 |
| **L4 运行期反馈** | 抛出去之后是否被上层捕获、业务是否产生可观影响，回填置信度 | 修正静态分析的偏差 | — | 低（复用表3） |

默认启用 L0 + L1，L2 按 `parse.throw.interproc=true` 开关（默认关），L3 不做。

### 2.3 L1 抽象解释器的具体设计

**输入**：`MethodNode`（ASM Tree API，`ClassReader` 不跳指令；`SKIP_FRAMES` 可保留，帧由自己算）。
**结构**：`InsnList` 上的基本块 CFG + `TryCatchBlockNode[]`，工作队列迭代到不动点。

**抽象域**（每个操作数栈槽 / 局部变量槽一个抽象值）：

```
Nullness ∈ { NULL, NONNULL, UNKNOWN }        ⊔ : NULL ⊔ NONNULL = UNKNOWN，相同则不变
（栈深度/类型由 ASM 的 Frame 模型提供，本分析只叠加引用类型的空值属性）
```

**转移函数要点**：

- `aconst_null → NULL`；`new → NONNULL`；`ldc`（String / Class / MethodHandle / MethodType / ConstantDynamic）`→ NONNULL`。
- `getfield` / `getstatic` / `invoke*` 返回值 / 方法参数 / catch 到的异常对象 `→ UNKNOWN`。
- **解引用类指令**（`getfield` `putfield` `invokevirtual` `invokespecial` `invokeinterface` `arraylength` `*aload` `*astore` `monitorenter`）：
  - 被解引用槽 = `NULL` → 该行 NPE **必然发生**（标记 MUST，可用于"这行本来就是 bug"的观测）；
  - = `UNKNOWN` → **可能**；
  - = `NONNULL` → **不可能**（本行 NPE 出局）。
- **解引用后精化**：指令正常执行后的状态里，把被解引用的槽置为 `NONNULL`（若该槽同时是某个局部变量，同步精化该 local）。这使 `x.a(); x.b();` 的第二行也判定为不可能。
- **分支精化（关键）**：`ifnull L` —— 跳转目标处该槽 `NULL`、落下处 `NONNULL`；`ifnonnull L` 反之。`if_acmpeq/if_acmpne` 与 `aconst_null` 比较时同样处理。javac 把 `if (x != null)` 直译为 `ifnonnull`，`if (x == null) return;` 直译为 `ifnull`，两种写法都被覆盖。
- **已知方法白名单**（可选，默认只放一条）：`java.util.Objects.requireNonNull(x)` 返回后参数槽 `NONNULL`。
- **合并（join）**：逐个槽取最小上界；栈深度不同的路径按 ASM 语义处理（本分析沿用指令流的栈深，深度不一致即视为异常路径）。
- **异常处理器入口帧**：栈 = `[NONNULL(异常对象)]`；局部变量 = 保护区间内**全部**指令处帧的合并（保守）。结果是 `try { x.foo(); } catch (NPE e) { x.bar(); }` 的 catch 内 `x` 仍为 `UNKNOWN` —— 正确（能进 catch 恰恰说明它可能是 null）。
- **不动点**：格高为 2，有限次迭代收敛；循环体按工作队列重复直到不变化。

**输出**：每行一个三态结论 —— `不可能 / 可能 / 必然`（按行内指令取并集，保守）。

### 2.4 能解决到什么程度

能精确判定：

- `if (x != null) x.foo();`、卫语句 `if (x == null) return;`、`x != null && x.y()`；
- 三元 / 卫语句后的局部变量解引用；
- 同一局部变量连续多次解引用（第一次之后都是 NONNULL）；
- `Objects.requireNonNull` 之后。

判定为"不可能"只信任**局部变量**：字段即使在同方法内做过空值检查，重读字段仍可能被并发/副作用改掉，javac 若未复制到局部变量（第二次 `getfield`）则结果仍为 `UNKNOWN` → 保守判"可能"。这是刻意的，不是缺陷。

明确判定不了的（一律退化为"可能"，计入低置信度）：

- 跨方法返回值（除非开 L2 且被调方法可解析）；
- 反射 / 动态代理 / `MethodHandle` 调用；
- 多态分派（被调实现取决于运行期类型）；
- `Error` 家族（OOM / SOE）与 `Throwable` 全族兜底，不纳入候选（业务上不可控，注入无意义）；
- 并发下被其它线程修改的状态；
- 数组下标越界、除零（不做 L3，默认"可能"）。

### 2.5 附带可精确计算的一件事：抛出去有没有用

异常表的判定是**精确**的（本方法内）：

- 沿该行所在位置向外找覆盖它的 `TryCatchBlockNode`，用类继承关系判断 catch 类型能否覆盖候选异常 `E`；
- `catch (Exception)` / `catch (Throwable)` → 一定被吞；
- 若吞掉后不再抛出（handler 里没有 `athrow`）→ 该行的这次注入**对调用方无影响**，标记 `caught_locally=1`。

这条信息比"能不能抛"更影响演练价值：把 `caught_locally=1` 的行降权或排除，可以让注入落在真正会产生业务影响的位置。跨方法部分保守处理（上层是否 catch 不展开，只判本方法）。

### 2.6 分类与落库

表2 是**方法级**的，没有行级明细。行级抛异常必须有行级数据 → 新增表5。

```sql
CREATE TABLE t_method_line (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  unit_id       BIGINT       NOT NULL COMMENT '→ t_jar_record.id',
  class_name    VARCHAR(256) NOT NULL,
  method_name   VARCHAR(128) NOT NULL,
  desc_hash     CHAR(16)     NOT NULL COMMENT '区分重载（同表2）',
  line_no       INT          NOT NULL,
  opcode_kinds  INT          NOT NULL COMMENT '该行指令类别位图：解引用/数组读/数组写/强转/除/调用/athrow/new',
  npe           TINYINT      NOT NULL COMMENT '0 不可能 / 1 可能 / 2 必然',
  aioobe        TINYINT      NOT NULL COMMENT '同上口径',
  ase           TINYINT      NOT NULL COMMENT 'ArrayStoreException，同上口径',
  cce           TINYINT      NOT NULL COMMENT 'ClassCastException，同上口径',
  arith         TINYINT      NOT NULL COMMENT '除零，同上口径',
  athrow_types  TEXT         NULL COMMENT '该行显式抛出的类型（可推断时），逗号分隔，观测不入索引',
  callee_ex     TEXT         NULL COMMENT 'Callee(L)：该行调用可解析的方法时其可能异常类型，观测不入索引',
  remote_unknown TINYINT     NOT NULL DEFAULT 0 COMMENT '1=该行调用了不可解析的目标（反射/跨jar/JDK），候选集需并入未知运行时异常',
  caught_locally TINYINT     NOT NULL DEFAULT 0 COMMENT '1=本方法的 try/catch 会吞掉候选异常（注入对调用方无影响）',
  confidence    TINYINT      NOT NULL COMMENT '结论置信度：1 高（可证）/ 2 中 / 3 低（含不可判定因素）',
  UNIQUE KEY uk_line (unit_id, class_name, method_name, desc_hash, line_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

唯一键字节数 ≈ 8 + 256×4 + 128×4 + 16×4 + 4 = 1560B < 3072B，与表2 同量级。

产出位置：`BootJarParser.parseClass` 内，解析阶段一次性算出（该处已持有 class 字节与 ASM 访问器）。按 `parse.batch.size` 分批写库。

**成本预警**：表5 行数 = 全量有效代码行（中型 Spring Boot 应用 5~20 万行量级），比表2 放大 1~2 个数量级，解析耗时与写库量同步放大。建议用开关 `parse.throw.analysis.enabled`（默认 false）控制：只在跑"抛异常"轮次时开启，跑 kill 轮次时不产出行级明细。

### 2.7 异常类型选择优先级

`CanThrow(L)` 非空时按此顺序选一个具体类型：

1. `Explicit(L)`：该行显式抛出的类型（最真实）；
2. `Callee(L)` 中的 checked 异常（上层本就在处理，语义最自然）；
3. `Implicit(L)` 中的运行时异常（`NullPointerException` / `ArrayIndexOutOfBoundsException` / `ClassCastException` / `ArithmeticException`）；
4. `remote_unknown=1` 时并入一个通用运行时异常（如 `IllegalStateException`）；
5. 兜底 `RuntimeException`（JVM 一定允许，语义最弱）。

异常对象的可见性：优先 JDK 内置类型（任何 classloader 都能加载）；选应用自定义类型时，构造它的 `new` 指令在被改写类的常量池里必须能被该类的 classloader 解析（应用类通常可以；bootstrap 加载的类不可以）。

---

## 3. 难点二：怎么真的在这一行抛出去

### 3.1 sandbox 1.4.0 的现状（源码事实）

- `ProcessController` 提供 `throwsImmediately(Throwable)` / `returnImmediately(Object)`，机制是抛出 `ProcessControlException`。
- `EventWeaver.visitLineNumber` 生成的代码只有三段：`push(lineNumber)` → `push(namespace)` → `push(listenerId)` → `invokestatic Spy.spyMethodOnLine`，**没有** `processControl(...)`。对比 `onMethodEnter`（BEFORE）、`onMethodExit`（RETURN）、`visitMaxs`（THROWS）三处都调用了 `processControl(...)` —— 流程控制只在这三类事件上织入了消费代码。
- `Spy.spyMethodOnLine` 返回 `void`：行事件在字节码层面没有任何消费 `Spy.Ret` 的通路。
- `EventListenerHandler.handleEvent` 捕获顺序：`catch (ProcessControlException)` → `catch (Throwable)`（非中断处理器只 `logger.warn` 后放行）。行事件里抛 `ProcessControlException` 会走进前一支、返回 `Spy.Ret`，但 `handleOnLine` 丢弃该返回值 → **行事件里抛任何异常都不会改变业务流程**。

结论：**sandbox 原生的流程阻断只在方法级（BEFORE / RETURN / THROWS）生效，行级（LINE）没有阻断通路。**
（已在 129 上以一次性测试模块实测四种形态对照，验证记录见 TEST_CASES.md X 系列。）

### 3.2 执行路线

| 路线 | 做法 | 状态 |
|------|------|------|
| C 方法级近似 | 在 `onBefore` 里 `ProcessController.throwsImmediately(ex)`（原生支持，BEFORE 织入了 `processControl`） | 可行；粒度是方法入口而非行，作 POC / 降级选项 |
| D 中断式监听器 | 监听器类标注 sandbox-core 的 `@Interrupted`，`EventListenerHandler` 对中断式处理器 `throw throwable` | **不可行**：sandbox 对模块隔离 core 类（模块内 `Class.forName` 该注解即 `ClassNotFoundException`），注解解析时按定义类 loader 找不到类型被 JVM 静默丢弃；模块侧自带同名注解类也不行（`isAnnotationPresent` 的注解映射以 `Class` 对象为键，跨 loader 同名类不相等） |
| **B core 最小补丁**（推荐） | 只改 `EventListenerHandler.handleOnLine` 一处：`handleEvent(...)` 返回的 `Spy.Ret` 原版被丢弃，补丁改为 `RET_STATE_THROWS` 时 `throw (Throwable) ret.respond` —— 异常即从 `Spy.spyMethodOnLine` 调用点冒出到业务方法该行。**不改 `Spy` 接口、不改 `EventWeaver`**。配套：模块运行期反射置 `Spy.isSpyThrowException=true`（Spy 位于 bootstrap 全局唯一，否则 `Spy.handleException` 只 printStackTrace） | **已实测成立**：业务在目标行收到注入异常、进程存活、process stack 平衡。注意三点：① 补丁类须保留原版 `getSingleton()`；② `isSpyThrowException` 是全局开关，生产化应把 rethrow 收窄为按 namespace/listenerId 判断（补丁内定向，不用全局开关）；③ 需重新分发 sandbox-core.jar 并版本化管理 |
| A 自研字节码注入 | fault-agent premain 注册 `ClassFileTransformer`，ASM 在目标行首插 `if (Gate.hit(...)) throw ...` | 长期备选：完全自主（精确构造异常、运行期条件判定），但要自行实现行号定位 / 帧重算 / Gate 可见性 / 与 sandbox 织入共存 |

B 补丁形态（约 2 行，位于 `handleOnLine` 的事件分发处）：

```java
final Spy.Ret ret = handleEvent(listenerId, processId, invokeId, event, wrap);
if (null != ret && ret.state == Spy.Ret.RET_STATE_THROWS) {
    throw (Throwable) ret.respond;   // 异常从 spyMethodOnLine 冒出 → 业务方法该行
}
```

模块侧写法：`beforeLine` 内 `ProcessController.throwsImmediately(ex)`（`ProcessControlException` 为受检异常，`beforeLine` 不声明 throws，需 sneaky-throw 抛出）。

异常传播路径上的既有机制无需处理：异常会被 sandbox 为 THROWS 事件织入的方法体级 `try/catch(Throwable)` 捕获 → 触发 `spyMethodOnThrows`（process stack 对齐）→ rethrow，业务侧行为正确。

### 3.3 落地顺序

- **P0（已完成）**：执行机制验证 —— X 系列实测，路线 B 最小补丁成立，路线 D 判死。
- **P1（下一步）**：难点一的静态分析 + 表5 行级能力数据（与执行机制解耦）。
- **P2**：B 补丁产品化 —— 补丁纳入 sandbox-core 构建（版本化），rethrow 收窄为按 namespace/listenerId 判定，去掉对全局 `isSpyThrowException` 的依赖；部署流程覆盖所有目标机。
- **P3**：抛异常的推进语义与落库（见第 4 节）。

若后续能力要长期演进（不止抛异常），再评估路线 A。

### 3.4 字节码层面的硬约束（任何路线都适用）

- `athrow` 在 JVM 层只要求栈顶是 `Throwable` 子类，**JVM 不校验 checked 异常是否被 `throws` 声明**（checked 是 javac 规则）。所以任意位置抛任意异常都不会 `VerifyError`。
- 局部变量与操作数栈：`athrow` 会清空操作数栈，栈上残留的半成品操作数合法；局部变量不变。
- `super()` / `this()` 之前不可插入（未初始化 `this` 的帧不允许 `athrow`）。
- 新增基本块（条件抛分支的出口、异常处理器入口）必须有正确的 StackMapTable 帧：用 `ClassWriter(COMPUTE_FRAMES)` 重算，并覆写 `getCommonSuperClass` 使其能解析目标类（解析失败退化 `java/lang/Object`）。
- 建议做成**条件式**抛（`if (hit) throw;`）而非无条件，避免制造不可达代码。
- `synchronized` 块内抛异常会正常触发 `monitorexit`，锁不会泄漏。

---

## 4. 难点三：抛异常与 kill 的语义共存

现有语义是"命中即 kill"，进程死亡天然带来两个副作用：① 本轮到此结束；② 不会有第二次命中。改成抛异常后进程继续，这两点都要重新定义。

需要决策的四项：

1. **动作开关**：`inject.fault.action: kill | throw`（默认 `kill`，保持现状）。
2. **次数与推进**：抛异常模式下沿用"每行 + 每线程 + 每调用栈最多 `inject.fault.times` 次"，用完转 exhausted（现有 `counters` / `exhausted` 双结构可直接复用）。区别在于用尽后进程继续正常运行。
3. **抛完是否收尾**：可选 `throw` 用尽后是否 kill（用于"演练完就清理"），默认不 kill。
4. **落库**：表3 增加
   - `fault_type` 取值扩展：`KILL_PROCESS` | `THROW_EXCEPTION`；
   - 新增 `thrown_type VARCHAR(256)` —— 实际注入的异常类型（观测，不入索引）；
   - 新增 `caught_locally TINYINT` —— 来自表5 的静态结论（该行抛出去会被本方法吞掉）。
   唯一键不变（`tag, boot_jar_hash, class, method, line, thread, fault_seq, stack_hash`），语义从"该行该线程该调用栈第 N 次被 kill"扩展为"第 N 次被注入故障"。

**待决策**：抛异常模式下"整轮何时结束"由谁决定（演练时长 / 覆盖率达标 / 外部停止命令）。这会影响 agent 侧是否需要新增收尾逻辑。

---

## 5. 改动清单（按模块）

**common**
- 新增 `LineThrowAnalyzer`（L1 抽象解释器 + L0 分类；L2 可选）。
- 新增 `model/MethodLineInfo`、`dao/MethodLineDao`（分批写入、按类+方法批量读取）。
- `ClassMethodInfo` 不动；`schema.sql` 新增表5（开发期约定：drop 全表重建）。

**fault-agent**
- `BootJarParser.parseClass`：收集行号时同步做能力分析（受 `parse.throw.analysis.enabled` 控制）。
- `FaultConfig`：新增 `parse.throw.analysis.enabled`、`parse.throw.interproc`（默认 false）。

**fault-module**
- `inject` 阶段：按批次额外读取表5，构造 `Map<class+method+descHash, Map<line, Candidate>>` 交给 listener。
- listener：`beforeLine` 命中候选行时，按 2.7 的优先级选类型并抛出（机制取决于 P2 选型）；落表3 的 `fault_type=THROW_EXCEPTION` + `thrown_type`。
- 过滤块 / 线程过滤 / 判重 / 计数逻辑不变。

**sandbox**：仅在路线 B 下需要改动并重新打包部署。

---

## 6. 验收口径（对应 TEST_CASES.md）

- 静态分析：构造包含空值检查、卫语句、短路求值、循环、try/catch、字段重读、跨方法调用的载荷类，逐行核对表5 的 `npe` 三态结论与 `caught_locally`。
- 注入行为：限定少数方法，抛异常后核对 ① 业务侧看到异常且栈中该行是抛出点；② 表3 记录 `THROW_EXCEPTION` + `thrown_type`；③ 次数用尽后不再抛；④ 进程存活。
- 与 kill 的对照：同载荷跑 `action=kill` 与 `action=throw`，对比表3 差异。

---

## 7. 开放问题（需先决策）

1. 是否接受"更真实 ⇒ 部分行永不注入"（覆盖率分母改为可注入行）？
2. 抛异常模式下整轮如何结束（见第 4 节第 5 条）？
3. 是否启用 L2 过程间分析（产出具体异常类型，代价是解析耗时与 jar 内类层次构建）？
4. P2 选 D 还是直接上 B / A（取决于 D 的验证结果与对 sandbox 分支的接受度）？
