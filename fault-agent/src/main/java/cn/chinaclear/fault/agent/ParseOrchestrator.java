package cn.chinaclear.fault.agent;

import cn.chinaclear.fault.common.BootJarParser;
import cn.chinaclear.fault.common.FaultConfig;
import cn.chinaclear.fault.common.FaultLogger;
import cn.chinaclear.fault.common.JdbcHelper;
import cn.chinaclear.fault.common.MachineInfo;
import cn.chinaclear.fault.common.dao.ClassMethodDao;
import cn.chinaclear.fault.common.dao.JarRecordDao;
import cn.chinaclear.fault.common.model.JarRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 解析编排：单元划分结果 → 表1 状态机 → 表2 幂等落库 → 单元 id 列表（供挂载命令传参） */
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
        checkDeadline(deadline, bootJarPath, null);

        JdbcHelper db = new JdbcHelper(config.jdbcUrl(), config.jdbcUsername(), config.jdbcPassword());
        JarRecordDao recordDao = new JarRecordDao(db);
        ClassMethodDao methodDao = new ClassMethodDao(db);

        List<Long> unitIds = new ArrayList<>();
        List<Long> inFlight = new ArrayList<>();
        for (BootJarParser.ParseUnit unit : units) {
            checkDeadline(deadline, bootJarPath, snapshot(inFlight));
            unitIds.add(handleUnit(config, recordDao, methodDao, unit, bootJarPath, deadline, inFlight));
        }
        return unitIds;
    }

    /** 单元状态机：返回该单元最终对应的表1 id */
    private static long handleUnit(FaultConfig config, JarRecordDao recordDao, ClassMethodDao methodDao,
                                   BootJarParser.ParseUnit unit, String bootJarPath,
                                   long deadline, List<Long> inFlight) {
        long unitId;
        Long newId;
        try {
            newId = recordDao.tryInsertPending(unit.unitType, unit.sha256, unit.sourceJar, bootJarPath);
        } catch (RuntimeException e) {
            throw HardProtectException.exception("DB", "insert pending failed: " + e.getMessage(),
                    FaultAgent.stackOf(e), snapshot(inFlight), bootJarPath);
        }

        if (newId != null) {
            // 首次解析：本节点负责
            unitId = newId;
            inFlight.add(unitId);
            storeUnit(recordDao, methodDao, unit, unitId, bootJarPath);
            removeInFlight(inFlight, unitId);
            return unitId;
        }

        // 已存在：读状态走状态机
        JarRecord existing = recordDao.findBySha256(unit.sha256);
        if (existing == null) {
            throw HardProtectException.exception("DB", "record disappeared after conflict: " + unit.sha256,
                    null, snapshot(inFlight), bootJarPath);
        }
        unitId = existing.getId();
        String status = existing.getStatus();

        if ("completed".equals(status)) {
            FaultLogger.info("unit already completed, skip: source=" + unit.sourceJar + " unitId=" + unitId);
            return unitId;
        }

        if ("failed".equals(status)) {
            if (recordDao.claimForReparse(unitId, MachineInfo.hostname(), MachineInfo.ip())) {
                FaultLogger.info("re-parse claimed (previous FAILED): unitId=" + unitId + " source=" + unit.sourceJar);
                inFlight.add(unitId);
                storeUnit(recordDao, methodDao, unit, unitId, bootJarPath);
                removeInFlight(inFlight, unitId);
            } else {
                FaultLogger.info("re-parse claimed by other node first, skip: unitId=" + unitId);
            }
            return unitId;
        }

        // pending：区分本机中断 与 异机解析中
        boolean ownPending = MachineInfo.ip().equals(existing.getIp())
                && bootJarPath.equals(existing.getBootJar());
        boolean orphan = System.currentTimeMillis() - existing.getUpdatedAt().getTime()
                >= (long) config.orphanThresholdMinutes() * 60000L;
        if (ownPending || orphan) {
            String reason = ownPending ? "pending left by THIS machine (interrupted)" : "orphan pending on other node (timeout)";
            if (recordDao.claimForReparse(unitId, MachineInfo.hostname(), MachineInfo.ip())) {
                FaultLogger.info("re-parse claimed (" + reason + "): unitId=" + unitId + " source=" + unit.sourceJar);
                inFlight.add(unitId);
                storeUnit(recordDao, methodDao, unit, unitId, bootJarPath);
                removeInFlight(inFlight, unitId);
            } else {
                FaultLogger.info("claim lost to other node (" + reason + "), skip: unitId=" + unitId);
            }
            return unitId;
        }

        // 异机 pending 且未超时：其他节点解析中——轮询等待其完成（数据齐了才能保证本节点挂载覆盖完整）
        while (true) {
            checkDeadline(deadline, bootJarPath, snapshot(inFlight));
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw HardProtectException.exception("PARSE", "wait interrupted", null, snapshot(inFlight), bootJarPath);
            }
            JarRecord latest = recordDao.findBySha256(unit.sha256);
            if (latest == null) {
                throw HardProtectException.exception("DB", "record disappeared while waiting: " + unit.sha256,
                        null, snapshot(inFlight), bootJarPath);
            }
            if ("completed".equals(latest.getStatus())) {
                break;
            }
            if ("failed".equals(latest.getStatus())
                    && recordDao.claimForReparse(unitId, MachineInfo.hostname(), MachineInfo.ip())) {
                // 对方解析失败置了 failed，本节点接手
                inFlight.add(unitId);
                storeUnit(recordDao, methodDao, unit, unitId, bootJarPath);
                removeInFlight(inFlight, unitId);
                break;
            }
            // 仍是 pending：若 updated_at 超过孤儿阈值则接手重解析
            if ("pending".equals(latest.getStatus())
                    && System.currentTimeMillis() - latest.getUpdatedAt().getTime()
                    >= (long) config.orphanThresholdMinutes() * 60000L
                    && recordDao.claimForReparse(unitId, MachineInfo.hostname(), MachineInfo.ip())) {
                inFlight.add(unitId);
                storeUnit(recordDao, methodDao, unit, unitId, bootJarPath);
                removeInFlight(inFlight, unitId);
                break;
            }
        }
        return unitId;
    }

    /** 落库：表2 幂等批量插入 → 表1 置 completed；异常抛硬保护（携带该单元 id） */
    private static void storeUnit(JarRecordDao recordDao, ClassMethodDao methodDao,
                                  BootJarParser.ParseUnit unit, long unitId, String bootJarPath) {
        try {
            methodDao.batchInsertIgnore(unitId, unit.methods);
            Set<String> classes = new HashSet<>();
            for (cn.chinaclear.fault.common.model.ClassMethodInfo m : unit.methods) {
                classes.add(m.getClassName());
            }
            recordDao.markCompleted(unitId, classes.size(), unit.methods.size());
            FaultLogger.info("unit stored: type=" + unit.unitType + " source=" + unit.sourceJar
                    + " unitId=" + unitId + " classes=" + classes.size() + " methods=" + unit.methods.size());
        } catch (RuntimeException e) {
            throw HardProtectException.exception("DB", "store unit failed: " + e.getMessage(),
                    FaultAgent.stackOf(e), Collections.singletonList(unitId), bootJarPath);
        }
    }

    private static void checkDeadline(long deadline, String bootJarPath, List<Long> inFlight) {
        if (System.currentTimeMillis() > deadline) {
            throw HardProtectException.timeout("PARSE", "premain deadline exceeded during parse/store",
                    null, snapshot(inFlight), bootJarPath);
        }
    }

    private static void removeInFlight(List<Long> inFlight, long unitId) {
        inFlight.remove(Long.valueOf(unitId));
    }

    private static List<Long> snapshot(List<Long> inFlight) {
        return inFlight.isEmpty() ? null : new ArrayList<>(inFlight);
    }
}
