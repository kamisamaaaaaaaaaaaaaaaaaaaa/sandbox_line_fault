package cn.chinaclear.fault.agent;

import cn.chinaclear.fault.common.BootJarParser;
import cn.chinaclear.fault.common.FaultConfig;
import cn.chinaclear.fault.common.FaultLogger;
import cn.chinaclear.fault.common.JdbcHelper;
import cn.chinaclear.fault.common.MachineInfo;
import cn.chinaclear.fault.common.dao.ClassMethodDao;
import cn.chinaclear.fault.common.dao.ErrorRecordDao;
import cn.chinaclear.fault.common.dao.JarRecordDao;
import cn.chinaclear.fault.common.model.ClassMethodInfo;
import cn.chinaclear.fault.common.model.JarRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 解析编排：流式解析 bootJar → 单元登记与跳过判定 → 表2 分批幂等落库 → 单元 id 列表（供挂载命令传参）。
 *
 * 两态模型（各节点独立解析，无协调）：
 *  completed = 已完成，跳过该单元的内容解析；任何其他状态 = 未完成，流式解析并按批写库。
 * 多节点同时解析同一单元属预期行为：表2 唯一索引 + 裸 INSERT 冲突跳过（SQLState 23 类）幂等收敛；
 * 表1 由最后完成的节点覆盖（ip/机器名/计数/parsed_at 为观测信息，不参与决策）。
 *
 * 内存控制：解析出的方法按 parse.batch.size 分批写库，不驻留全量方法清单。
 */
final class ParseOrchestrator {

    private ParseOrchestrator() {
    }

    /** 返回全部解析单元对应的表1 主键 id 列表 */
    static List<Long> parseAndStore(FaultConfig config, String bootJarPath, long deadline) {
        JdbcHelper db = new JdbcHelper(config.jdbcUrl(), config.jdbcUsername(), config.jdbcPassword(),
                config.jdbcDriver());
        JarRecordDao recordDao = new JarRecordDao(db);
        ClassMethodDao methodDao = new ClassMethodDao(db);
        int batchSize = config.parseBatchSize();
        List<Long> unitIds = new ArrayList<>();

        try {
            BootJarParser.parse(java.nio.file.Paths.get(bootJarPath), config.parseClassesEnabled(),
                    config.libWhitelist(), (unitType, sourceJar, sha256) -> {
                        checkDeadline(deadline, bootJarPath);
                        UnitRegistration reg = registerUnit(recordDao, unitType, sha256, sourceJar, bootJarPath);
                        unitIds.add(reg.unitId);
                        if (reg.completed) {
                            FaultLogger.info("unit already completed, skip: source=" + sourceJar
                                    + " unitId=" + reg.unitId);
                            return null;    // 跳过该单元内容解析（省去 ASM 开销）
                        }
                        // 未完成：流式解析并按批写库（不抢占、不等待他节点）
                        return new UnitWriter(config, methodDao, recordDao, reg.unitId, unitType,
                                sourceJar, bootJarPath, batchSize);
                    });
        } catch (HardProtectException e) {
            throw e;
        } catch (RuntimeException e) {
            throw HardProtectException.exception("PARSE", "parse bootJar failed: " + e.getMessage(),
                    AgentBootstrap.stackOf(e), null, bootJarPath);
        }
        if (unitIds.isEmpty()) {
            // 本轮一个解析单元都没有：典型原因是关闭了 classes 解析、而 lib 白名单又没匹配到任何 jar。
            // 此时继续启动会得到一个「挂载了但零覆盖」的空跑进程，绝不放行。
            throw HardProtectException.exception("PARSE",
                    "no parse unit for this round: parse.classes.enabled=" + config.parseClassesEnabled()
                            + ", lib.whitelist=" + config.libWhitelist()
                            + "（两者不可同时为空：请至少保留 classes 解析或让白名单匹配到 jar）",
                    null, null, bootJarPath);
        }
        FaultLogger.info("parse units: count=" + unitIds.size() + " ids=" + unitIds
                + "（classes=" + config.parseClassesEnabled() + "）");
        return unitIds;
    }

    /** 表1 登记：裸 INSERT 占位拿 id（冲突返回 null）；已存在则读取状态判定是否已完成 */
    private static UnitRegistration registerUnit(JarRecordDao recordDao, String unitType, String sha256,
                                                 String sourceJar, String bootJarPath) {
        try {
            Long newId = recordDao.tryInsertPending(unitType, sha256, sourceJar, bootJarPath);
            if (newId != null) {
                return new UnitRegistration(newId, false);
            }
            JarRecord existing = recordDao.findBySha256(sha256);
            if (existing == null) {
                throw HardProtectException.exception("DB", "record disappeared after conflict: " + sha256,
                        null, null, bootJarPath);
            }
            return new UnitRegistration(existing.getId(), "completed".equals(existing.getStatus()));
        } catch (HardProtectException e) {
            throw e;
        } catch (RuntimeException e) {
            throw HardProtectException.exception("DB", "register unit failed: " + e.getMessage(),
                    AgentBootstrap.stackOf(e), null, bootJarPath);
        }
    }

    private static void checkDeadline(long deadline, String bootJarPath) {
        if (System.currentTimeMillis() > deadline) {
            throw HardProtectException.timeout("PARSE", "parse deadline exceeded", null, null, bootJarPath);
        }
    }

    /** 表1 登记结果：单元 id + 该单元是否已完成（已完成则跳过解析） */
    private static final class UnitRegistration {
        final long unitId;
        final boolean completed;

        UnitRegistration(long unitId, boolean completed) {
            this.unitId = unitId;
            this.completed = completed;
        }
    }

    /**
     * 单元写入器：累积到 batchSize 条即写库一次，单元结束 flush 剩余并置 completed。
     * 类名集合用于统计 class_count（类名数远小于方法数，内存可控）。
     */
    private static final class UnitWriter implements BootJarParser.UnitSink {
        private final FaultConfig config;
        private final ClassMethodDao methodDao;
        private final JarRecordDao recordDao;
        private final long unitId;
        private final String unitType;
        private final String sourceJar;
        private final String bootJarPath;
        private final int batchSize;
        private final List<ClassMethodInfo> buffer;
        private final Set<String> classes = new HashSet<>();
        private long methodCount;

        UnitWriter(FaultConfig config, ClassMethodDao methodDao, JarRecordDao recordDao, long unitId,
                   String unitType, String sourceJar, String bootJarPath, int batchSize) {
            this.config = config;
            this.methodDao = methodDao;
            this.recordDao = recordDao;
            this.unitId = unitId;
            this.unitType = unitType;
            this.sourceJar = sourceJar;
            this.bootJarPath = bootJarPath;
            this.batchSize = batchSize;
            this.buffer = new ArrayList<>(batchSize);
        }

        @Override
        public void accept(ClassMethodInfo method) {
            buffer.add(method);
            classes.add(method.getClassName());
            methodCount++;
            if (buffer.size() >= batchSize) {
                flush();
            }
        }

        @Override
        public void finish() {
            flush();
            recordDao.markCompleted(unitId, classes.size(), (int) methodCount,
                    MachineInfo.hostname(), MachineInfo.ip());
            FaultLogger.info("unit stored: type=" + unitType + " source=" + sourceJar
                    + " unitId=" + unitId + " classes=" + classes.size() + " methods=" + methodCount);
        }

        private void flush() {
            if (buffer.isEmpty()) {
                return;
            }
            try {
                methodDao.batchInsertSkipConflict(unitId, buffer);
            } catch (RuntimeException e) {
                throw HardProtectException.exception("DB", "store unit failed: " + e.getMessage(),
                        AgentBootstrap.stackOf(e), Collections.singletonList(unitId), bootJarPath);
            }
            buffer.clear();
        }
    }
}
