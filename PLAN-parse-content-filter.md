# 解析内容过滤（parse.classes/libs.content-filter）设计方案

> 2026-09-29 · 配套实现：`common/ParseFilter`（新增）、`BootJarParser`、`FaultConfig`、`ParseOrchestrator`

## 1. 背景与目标

现有解析过滤只有一个维度：`lib.whitelist`（旧键）按 **jar 文件名**决定 `BOOT-INF/lib/` 下哪些第三方 jar 被解析。
需求：再增加一个**内容维度**——jar 包（或 classes）内部**包含某个目录/文件**时才解析，支持多个条件、每个条件是正则，语法与 whitelist 一致。

典型场景：第三方 jar 数量多且文件名无规律，希望"只解析包含指定标记目录/文件（如 SPI 配置、特定包结构）的 jar"。

## 2. 配置层级（新旧对照）

```
旧（已废弃）                      新
─────────────────────────────    ─────────────────────────────────────────
lib.whitelist                    parse:
                                   classes:
                                     enabled: true          # 原 parse.classes.enabled，键不变
                                     content-filter:        # 新增
                                       - 'cn/example/service/.*'
                                   libs:
                                     whitelist:             # 原 lib.whitelist（迁移）
                                       - 'test-lib.*'
                                     content-filter:        # 新增
                                       - 'META-INF/spring\.factories'
```

- content-filter 本身就是正则列表（无 entries 子层级），沿用 `FaultConfig.getList` 展平读取。
- classes 的条目是 `.class` 文件相对路径（相对 `BOOT-INF/classes/` 前缀；目录不单独成条目，目录匹配写「包路径/.*」）。
- lib 的条目是 jar 内每个 ZipEntry 的完整路径（目录条目以 `/` 结尾，如 `com/example/marker/`）。
- 旧键 `lib.whitelist`（标量或列表形态）一旦出现即启动报错（fail-fast，防止静默失效），提示迁移到 `parse.libs.whitelist`。

## 3. 过滤语义

| 维度 | classes 单元 | libs 每个 jar |
|---|---|---|
| 名字维度 | `parse.classes.enabled` 开关 | `parse.libs.whitelist` 对 jar 文件名全串匹配 |
| 内容维度 | `parse.classes.content-filter` 对 classes 下 `.class` 相对路径 | `parse.libs.content-filter` 对 jar 内条目完整路径 |
| 组合关系 | **开关 AND 内容过滤** | **白名单 AND 内容过滤** |

- 同一维度内多个条件是 **OR**：任一条件命中任一条目即通过（命中即早退，不再继续扫）。
- 跨维度是 **AND**：名字维度先判，内容维度后判，都通过才解析；内容命中不能单独放行。
- content-filter 未配置（空列表）= 该维不限制，行为与引入前**完全一致**（lib 连扫描流都不开，保持单遍 hash 快路径）。

### 滤空与硬保护

- classes 内容全不命中 → **整个 classes 单元跳过**（不落表1、不算 classes 哈希），lib 照常解析，正常放行。
- libs 全被滤空（白名单命中但内容不命中）→ 同上，只看 classes 是否还在。
- **零解析单元**（classes 被关/滤空 且 lib 零命中）→ 硬保护 `PARSE`，不放行"挂载了却零覆盖"的进程；报错文案包含四个过滤键的当前值，可直接定位是哪一维滤空的。

## 4. 执行点与实现要点

`BootJarParser.parse(bootJar, ParseFilter, UnitHandler)`：

1. **classes**：`JarHashUtil.listClassesEntries` 枚举出的条目先过内容过滤（相对路径匹配），全不命中则跳过整个单元（连哈希计算一并省掉）；命中才照旧算哈希 + 逐类 ASM 解析。
2. **libs**：白名单命中后、计算 sha256 与 `beginUnit` 登记之前，对该 jar 开 `ZipInputStream` 只枚举条目名（不读条目内容、无 ASM 开销）做匹配；不命中直接跳过——**保证被滤掉的 jar 不产生 pending 单元、不写库**。
3. hash 仍对压缩原始字节流计算，与条目枚举是两遍流，不合并（合并会因 ZipInputStream 预读破坏摘要正确性）。
4. 所有正则在循环外 `RegexPatterns.compile` 预编译一次；非法正则抛异常，由 `ParseOrchestrator` 的 catch 收口为硬保护 PARSE。
5. 过滤参数由 `ParseFilter`（小型不可变参数对象）承载，`ParseOrchestrator` 从 `FaultConfig` 组装。

## 5. 可观测性

- classes 滤空：`classes unit skipped by content-filter: no .class entry matches parse.classes.content-filter=[...]`
- lib 跳过汇总（分开计数，不逐 jar 刷屏）：`lib units skipped: whitelist=N content-filter=M`
- 零单元硬保护：`no parse unit for this round: parse.classes.enabled=..., parse.classes.content-filter=..., parse.libs.whitelist=..., parse.libs.content-filter=...`

## 6. 兼容性

- 不配置任何 content-filter = 行为与旧版完全一致（回归基线 LF1/CC1）。
- 旧键 `lib.whitelist` fail-fast：不给静默降级的机会（与本项目"配置非法即硬保护"约定一致）。
- 过滤只影响"哪些单元被解析"，不影响解析口径（方法收录规则、行号统计等均不变）；落库结构（t_jar_record / t_class_method）不变。

## 7. 测试矩阵（详见 TEST_CASES.md「内容过滤（LF/CC 系列）」）

- **LF 系列（libs）12 例**：基线兼容 / 精确匹配文件 / 精确匹配目录 / 模糊匹配目录（含无显式目录条目兜底）/ 一个条件放行一批 jar / OR 多条件 / AND 名字不命中内容命中 / AND 两维都命中 / 叠加滤空（含 classes.enabled=false 组合触发硬保护）/ 非法正则 / 旧键 fail-fast / 滤除日志。
- **CC 系列（classes）7 例**：基线兼容 / 精确匹配类文件 / 模糊匹配包目录 / 整体滤空（只落 libs 放行）/ AND enabled=false 内容命中 / 双滤空硬保护 / 非法正则。
- **注入侧回归**（main 线程稳定方法，非 HTTP 触发）：过滤开启后 line / exception 注入命中、行×线程计数语义、行覆盖率均正常。
- 验证环境：远程 Linux（lys@192.168.193.129）实际挂载运行，本机 fault_sandbox 库核对 t_jar_record / t_class_method / t_fault_record。
