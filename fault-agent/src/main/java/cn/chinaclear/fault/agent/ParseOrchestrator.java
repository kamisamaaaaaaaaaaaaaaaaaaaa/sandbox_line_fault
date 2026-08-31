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
import cn.chinaclear.fault.common.model.ErrorRecord;
import cn.chinaclear.fault.common.model.JarRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 解析编排：单元划分 → 表1 登记与跳过判定 → 表2 幂等落库 → 单元 id 列表（供挂载命令传参）。
 *
 * 两态模型（无抢占、无等待）：
 *  completed = 已完成，跳过复用；任何其他状态 = 未完成，本节点直接解析。
 * 多节点同时解析同一单元属预期行为：表2 唯一索引 + INSERT IGNORE 幂等收敛，
 * 表1 由最后完成的节点覆盖（ip/机器名/计数/parsed_at 为观测信息，不参与决策）。
 */
final class ParseOrchestrator {

    private ParseOrchestrator() {
    }

    /** 返回全部解析单元对应的表1 主键 id 列表 */
    static List<Long> parseAndStore(FaultConfig config, String bootJarPath, long deadline) {
        List<BootJarParser.ParseUnit> units;
        try {
            units = BootJarParser.parse(java.nio.file.Paths.get(bootJarPath), config.libWhitelist());
        } catch (RuntimeException e) {
            throw HardProtectException.exception("PARSE", "parse bootJar failed: " + e.getMessage(),
                    FaultAgent.stackOf(e), null, bootJarPath);
        }
        checkDeadline(deadline, bootJarPath);

        JdbcHelper db = new JdbcHelper(config.jdbcUrl(), config.jdbcUsername(), config.jdbcPassword());
        JarRecordDao recordDao = new JarRecordDao(db);
        ClassMethodDao methodDao = new ClassMethodDao(db);

        List<Long> unitIds = new ArrayList<>();
        for (BootJarParser.ParseUnit unit : units) {
            checkDeadline(deadline, bootJarPath);
            UnitRegistration reg = registerUnit(recordDao, unit, bootJarPath);
            unitIds.add(reg.unitId);
            if (reg.completed) {
                FaultLogger.info("unit already completed, skip: source=" + unit.sourceJar
                        + " unitId=" + reg.unitId);
                continue;
            }
            // 未完成：本节点直接解析（不抢占、不等待他节点），表2 幂等收敛
            storeUnit(config, recordDao, methodDao, unit, reg.unitId, bootJarPath);
        }
        return unitIds;
    }

    /** 表1 登记：INSERT IGNORE 占位拿 id；已存在则读取状态判定是否已完成 */
    private static UnitRegistration registerUnit(JarRecordDao recordDao, BootJarParser.ParseUnit unit,
                                                 String bootJarPath) {
        try {
            Long newId = recordDao.tryInsertPending(unit.unitType, unit.sha256, unit.sourceJar, bootJarPath);
            if (newId != null) {
                return new UnitRegistration(newId, false);
            }
            JarRecord existing = recordDao.findBySha256(unit.sha256);
            if (existing == null) {
                throw HardProtectException.exception("DB", "record disappeared after conflict: " + unit.sha256,
                        null, null, bootJarPath);
            }
            return new UnitRegistration(existing.getId(), "completed".equals(existing.getStatus()));
        } catch (HardProtectException e) {
            throw e;
        } catch (RuntimeException e) {
            throw HardProtectException.exception("DB", "register unit failed: " + e.getMessage(),
                    FaultAgent.stackOf(e), null, bootJarPath);
        }
    }

    /** 落库：表2 幂等批量插入 → 表1 置 completed（记录本节点为完成者）；异常抛硬保护（携带该单元 id） */
    private static void storeUnit(FaultConfig config, JarRecordDao recordDao, ClassMethodDao methodDao,
                                  BootJarParser.ParseUnit unit, long unitId, String bootJarPath) {
        try {
            methodDao.batchInsertIgnore(unitId, unit.methods);
            Set<String> classes = new HashSet<>();
            for (ClassMethodInfo m : unit.methods) {
                classes.add(m.getClassName());
            }
            recordDao.markCompleted(unitId, classes.size(), unit.methods.size(),
                    MachineInfo.hostname(), MachineInfo.ip());
            FaultLogger.info("unit stored: type=" + unit.unitType + " source=" + unit.sourceJar
                    + " unitId=" + unitId + " classes=" + classes.size() + " methods=" + unit.methods.size());
            // 解析失败的 class 逐个记录表4（不中断整体流程）
            recordFailedClasses(config, unit, unitId, bootJarPath);
        } catch (RuntimeException e) {
            throw HardProtectException.exception("DB", "store unit failed: " + e.getMessage(),
                    FaultAgent.stackOf(e), Collections.singletonList(unitId), bootJarPath);
        }
    }

    /** 单个 class 解析失败：逐条落表4（写失败仅告警，不影响主流程） */
    private static void recordFailedClasses(FaultConfig config, BootJarParser.ParseUnit unit,
                                            long unitId, String bootJarPath) {
        if (unit.failedClasses.isEmpty()) {
            return;
        }
        try {
            JdbcHelper db = new JdbcHelper(config.jdbcUrl(), config.jdbcUsername(), config.jdbcPassword());
            ErrorRecordDao dao = new ErrorRecordDao(db);
            for (String failed : unit.failedClasses) {
                ErrorRecord er = new ErrorRecord();
                er.setPhase("PARSE");
                er.setErrorType("EXCEPTION");
                er.setMessage("class parse failed: " + failed);
                er.setUnitIds(String.valueOf(unitId));
                er.setBootJar(bootJarPath);
                er.setHostname(MachineInfo.hostname());
                er.setIp(MachineInfo.ip());
                try {
                    dao.insert(er);
                } catch (Throwable ignore) {
                    FaultLogger.warn("record class parse failure failed: " + failed);
                }
            }
        } catch (Throwable t) {
            FaultLogger.warn("record class parse failures failed (local log only): " + t.getMessage());
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
}
