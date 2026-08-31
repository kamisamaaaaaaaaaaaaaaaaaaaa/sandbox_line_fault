---
name: simplify-claim-strategy
overview: 去掉表1 的抢占/等待/接管解析策略，简化为两态模型：completed 跳过、未完成直接幂等解析落库；连带删除 failed 状态、orphan.threshold.minutes 配置及等待轮询代码，并同步三份文档。
todos:
  - id: simplify-orchestrator
    content: 简化 ParseOrchestrator：删状态机/waitForOtherNode/inFlight，completed 跳过、未完成直接解析
    status: completed
  - id: dao-agent-cleanup
    content: JarRecordDao 删 claimForReparse/markFailed、markCompleted 带节点信息；FaultAgent.hardProtect 去 markFailed；FaultConfig 与 config.yml 删 orphan 配置
    status: completed
    dependencies:
      - simplify-orchestrator
  - id: docs-sync
    content: 同步 README（配置表+A5组）、DESIGN.md（D3/D4）、TEST_CASES.md（TC6）
    status: completed
    dependencies:
      - dao-agent-cleanup
  - id: regression
    content: 编译上传，回归正常轮/二次跳过轮/两实例并发轮，commit
    status: completed
    dependencies:
      - docs-sync
---

## 需求概述

去掉解析阶段的“抢占/等待/孤儿接管”策略，简化为两态模型：**completed（完成，跳过复用）** 与 **未完成（任何其他状态，直接解析）**。多节点同时解析同一未完成单元属预期行为——表2 `batchInsertIgnore` 幂等收敛，无正确性问题。

## 核心变更

- 删除状态机全部分支：failed 抢占（claimForReparse）、本机遗留接管、异机孤儿抢占、异机未超时轮询等待（waitForOtherNode）
- 中断/失败单元不再置 failed，保持 pending（即“未完成”，下次启动直接重解析）
- `orphan.threshold.minutes` 配置失去意义，随策略一并删除
- `completed` 跳过逻辑保留（二次启动仍快速跳过）
- 无 schema 变更，表结构不动

## 技术方案

### 改动文件与内容

**1. `fault-agent/src/main/java/cn/chinaclear/fault/agent/ParseOrchestrator.java`（主要简化）**

- `parseAndStore` 重写为：遍历单元 → `registerUnit` 登记（INSERT IGNORE 占位拿 id / 冲突查已有行）→ completed 则跳过 → 否则 `storeUnit`（幂等落库 + markCompleted）
- 删除：`handleUnit` 状态机、`waitForOtherNode` 轮询、`inFlight`/`snapshot`/`removeInFlight`
- `checkDeadline(deadline, bootJarPath)` 去掉 inFlight 参数（parse.timeout.ms 总预算语义不变）
- 新增私有 `registerUnit` 返回 (unitId, completed)（小静态类或 long+boolean）
- `storeUnit` 不变（幂等核心），异常仍抛硬保护并携带 unitId 供表4 记录

**2. `common/src/main/java/cn/chinaclear/fault/common/dao/JarRecordDao.java`**

- 删除 `claimForReparse`、`markFailed`
- `markCompleted` 增加参数 hostname/ip：完成后刷新为最终解析节点（承接原 claimForReparse 的观测功能）

**3. `fault-agent/src/main/java/cn/chinaclear/fault/agent/FaultAgent.java`**

- `hardProtect` 删除 markFailed 循环（中断单元保持 pending=未完成）；表4 仍写 phase/errorType/detail/unit_ids/bootJar/机器信息

**4. `common/src/main/java/cn/chinaclear/fault/common/FaultConfig.java` + `fault-agent/src/main/resources/config.yml`**

- 删除 `orphanThresholdMinutes()` 与 yml 的 orphan 段

**5. 文档同步**

- README：配置表删 orphan 行、第八章 A5 组重写（抢占/等待/孤儿条目移除，替换为“未完成直接解析”语义）
- DESIGN.md：D3 状态机表改为两态模型说明（多节点重复解析幂等收敛）、D4 微调
- tests/TEST_CASES.md：TC6 描述更新（手工置 pending → 重启 → 直接重解析，无等待日志）

### 语义决策（已确认）

- completed 跳过保留；历史 failed 行视为“未完成”直接解析（无需兼容分支）
- 硬保护中断 → 表1 保持 pending；failed 状态不再产生
- 多节点并发解析同一单元：表2 幂等收敛、markCompleted 计数一致，无锁无等待
- parse.timeout.ms 保留（本节点解析总预算）；mount.timeout.ms 不变

### 验证计划

- 直测 FaultConfig（orphan 配置移除后无残留）
- 全链路回归：正常轮（首次解析+命中 kill）、二次启动轮（completed 跳过日志）、多节点两实例并发轮（无重复 completed 行、表3 集群唯一）