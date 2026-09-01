package cn.chinaclear.fault.module;

import cn.chinaclear.fault.common.FaultLogger;
import cn.chinaclear.fault.common.KillUtil;
import cn.chinaclear.fault.common.MachineInfo;
import cn.chinaclear.fault.common.dao.ErrorRecordDao;
import cn.chinaclear.fault.common.dao.FaultRecordDao;
import cn.chinaclear.fault.common.model.ErrorRecord;
import cn.chinaclear.fault.common.model.FaultRecord;
import com.alibaba.jvm.sandbox.api.listener.ext.Advice;
import com.alibaba.jvm.sandbox.api.listener.ext.AdviceListener;

import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 行级监听：beforeLine 中 INSERT 表3 抢占（唯一索引含轮次 tag，判重仅限轮内，跨机器多节点安全）。
 * 插入成功 = 本节点赢得该行本轮的故障执行权 → kill 当前进程；
 * DuplicateKey = 该行本轮已被集群内其他节点触发 → 放行继续执行。
 * 正确性完全由数据库唯一索引保证；preemptedLines 仅缓存"本进程已确认被抢占的行"，
 * 避免存活节点在热路径行上反复撞库。
 * 所有异常路径（kill 失败、记录失败、DB 异常）均落表4。
 */
final class KillAdviceListener extends AdviceListener {

    private final FaultRecordDao faultRecordDao;
    private final ErrorRecordDao errorRecordDao;
    private final Map<String, Long> classToUnitId;
    private final long pid;
    /** 轮次标识（JVM -Dfault.tag），判重仅限轮内 */
    private final String tag;
    /** 应用 bootJar 部署路径（判重键：路径即应用标识） */
    private final String bootJar;
    /** MD5(bootJar) 前 16 位 hex（索引键） */
    private final String bootJarHash;
    /** 本轮已确认被抢占的行（避免存活节点在热路径行上反复撞库） */
    private final Set<String> preemptedLines = ConcurrentHashMap.newKeySet();

    KillAdviceListener(FaultRecordDao faultRecordDao, ErrorRecordDao errorRecordDao,
                       Map<String, Long> classToUnitId, long pid, String tag,
                       String bootJar, String bootJarHash) {
        this.faultRecordDao = faultRecordDao;
        this.errorRecordDao = errorRecordDao;
        this.classToUnitId = classToUnitId;
        this.pid = pid;
        this.tag = tag;
        this.bootJar = bootJar;
        this.bootJarHash = bootJarHash;
    }

    @Override
    protected void beforeLine(Advice advice, int lineNum) {
        String lineKey = null;
        try {
            final String className = advice.getBehavior().getDeclaringClass().getName();
            final Long unitId = classToUnitId.get(className);
            if (unitId == null) {
                return;
            }
            final String method = advice.getBehavior().getName();
            lineKey = className + "#" + method + "#" + lineNum;
            if (preemptedLines.contains(lineKey)) {
                return;
            }
            FaultRecord fr = new FaultRecord();
            fr.setUnitId(unitId);
            fr.setTag(tag);
            fr.setHostname(MachineInfo.hostname());
            fr.setIp(MachineInfo.ip());
            fr.setBootJar(bootJar);
            fr.setBootJarHash(bootJarHash);
            fr.setClassName(className);
            fr.setMethodName(method);
            fr.setLineNo(lineNum);
            fr.setThreadName(Thread.currentThread().getName());
            fr.setFaultType("KILL_PROCESS");
            fr.setOccurredAt(new Date());

            boolean won = faultRecordDao.tryInsert(fr);
            if (won) {
                FaultLogger.error("FAULT HIT & PREEMPTED: tag=" + tag
                        + " unitId=" + unitId
                        + " class=" + className
                        + " method=" + method
                        + " line=" + lineNum
                        + " thread=" + fr.getThreadName()
                        + " machine=" + fr.getHostname() + "/" + fr.getIp());
                if (!KillUtil.killCurrentProcess(pid)) {
                    // kill 手段未生效：回滚记录并放行进程（进程继续运行，不在本工具内强行 halt）
                    FaultLogger.error("kill not effective, rollback fault record and release: " + lineKey);
                    recordError("kill not effective (process still alive), fault record rolled back", lineKey, null);
                    try {
                        faultRecordDao.delete(fr);
                    } catch (Throwable ignore) {
                        // rollback failure is irrelevant
                    }
                    preemptedLines.remove(lineKey);
                }
                // kill 生效：进程终止，本方法不会正常返回
            } else {
                // DuplicateKey：该行本轮已被其他节点触发，记忆后放行（不再反复撞库）
                preemptedLines.add(lineKey);
                FaultLogger.info("line already preempted in this round (tag=" + tag
                        + "), release execution: " + lineKey);
            }
        } catch (Throwable t) {
            // 任何异常（含 DB 不可用）：记表4 后按硬保护 kill —— 故障工具宁可不放行，
            // 也不能让一个"已注入但可能不生效"的进程继续运行
            FaultLogger.error("beforeLine handling failed, kill process per policy: " + lineKey, t);
            recordError("beforeLine failed: " + t.getMessage(), lineKey, t);
            if (!KillUtil.killCurrentProcess(pid)) {
                Runtime.getRuntime().halt(137);
            }
        }
    }

    /** 表4 留痕：写失败仅告警（此时可能正是 DB 不可用） */
    private void recordError(String message, String lineKey, Throwable t) {
        try {
            ErrorRecord er = new ErrorRecord();
            er.setPhase("INJECT");
            er.setErrorType("EXCEPTION");
            er.setMessage(message + (lineKey != null ? " (" + lineKey + ")" : ""));
            er.setDetail(t == null ? null : stackOf(t));
            er.setUnitIds(null);
            er.setBootJar(System.getProperty("sun.java.command"));
            er.setHostname(MachineInfo.hostname());
            er.setIp(MachineInfo.ip());
            errorRecordDao.insert(er);
        } catch (Throwable ignore) {
            FaultLogger.warn("write t_error_record failed (local log only)");
        }
    }

    private static String stackOf(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }
}
