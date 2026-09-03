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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 行级监听：beforeLine 中 INSERT 表3 抢占，判重键 = 轮次 tag + bootJar 部署路径 + 行 + 线程 + 第几次故障。
 * 同一行同一线程本轮可发生多次故障（次数上限由 inject.fault.times 决定）：
 * 插入成功 = 本节点赢得该行该线程本轮第 fault_seq 次的故障执行权 → kill 当前进程；
 * DuplicateKey = 该次故障已被集群内其他节点/进程触发 → 计数推进后放行继续执行。
 * 正确性完全由数据库唯一索引保证；counters / exhausted 仅缓存本进程已确认的故障次数，
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
    /** 未用尽：行+线程 → 已确认的故障次数（插入成功与被他节点抢占各计一次） */
    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();
    /** 已用尽：只判存在性，命中即零撞库（从 counters 移出后加入） */
    private final Set<String> exhausted = ConcurrentHashMap.newKeySet();
    /** 每行每线程故障次数上限（配置 inject.fault.times） */
    private final int faultTimes;

    KillAdviceListener(FaultRecordDao faultRecordDao, ErrorRecordDao errorRecordDao,
                       Map<String, Long> classToUnitId, long pid, String tag,
                       String bootJar, String bootJarHash, int faultTimes) {
        this.faultRecordDao = faultRecordDao;
        this.errorRecordDao = errorRecordDao;
        this.classToUnitId = classToUnitId;
        this.pid = pid;
        this.tag = tag;
        this.bootJar = bootJar;
        this.bootJarHash = bootJarHash;
        this.faultTimes = faultTimes;
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
            final String threadName = Thread.currentThread().getName();
            // 计数/判重键含线程：同一行被不同线程执行时故障效果可能不同，各线程独立计数
            final String hitKey = className + "#" + method + "#" + lineNum + "#" + threadName;
            lineKey = className + "#" + method + "#" + lineNum;
            if (exhausted.contains(hitKey)) {
                return;    // 该行该线程本轮故障次数已用尽：不再撞库、不再计数
            }
            int seq = nextSeq(hitKey);
            if (seq < 0) {
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
            fr.setThreadName(threadName);
            fr.setFaultSeq(seq);
            fr.setFaultType("KILL_PROCESS");
            fr.setOccurredAt(new Date());

            boolean won = faultRecordDao.tryInsert(fr);
            if (won) {
                FaultLogger.error("FAULT HIT & PREEMPTED: tag=" + tag
                        + " unitId=" + unitId
                        + " class=" + className
                        + " method=" + method
                        + " line=" + lineNum
                        + " seq=" + seq + "/" + faultTimes
                        + " thread=" + threadName
                        + " machine=" + fr.getHostname() + "/" + fr.getIp());
                if (!KillUtil.killCurrentProcess(pid)) {
                    // kill 手段未生效：回滚记录并放行进程（进程继续运行，不在本工具内强行 halt）
                    FaultLogger.error("kill not effective, rollback fault record and release: "
                            + hitKey + " seq=" + seq);
                    recordError("kill not effective (process still alive), fault record rolled back", lineKey, null);
                    try {
                        faultRecordDao.delete(fr);
                    } catch (Throwable ignore) {
                        // rollback failure is irrelevant
                    }
                    rollback(hitKey, seq);
                }
                // kill 生效：进程终止，本方法不会正常返回
            } else {
                // DuplicateKey：该行该线程本轮的第 seq 次故障已被其他节点/进程触发。
                // 计数已在 nextSeq 中推进，放行继续执行（不再反复撞库）
                FaultLogger.info("fault seq already preempted in this round (tag=" + tag
                        + "), release execution: " + hitKey + " seq=" + seq);
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

    /**
     * 取本次应尝试的故障序号（= 已确认次数 + 1）；返回 -1 表示无需再尝试。
     * 计数在尝试前 +1：插入成功（本节点赢得该次）与冲突（他节点已赢得该次）
     * 都意味着该序号已被确认，据此与数据库状态同步，不需要预先查库。
     */
    private int nextSeq(String hitKey) {
        AtomicInteger counter = counters.computeIfAbsent(hitKey, k -> new AtomicInteger(0));
        int seq = counter.incrementAndGet();
        if (seq >= faultTimes) {
            // 已达上限：本次尝试后不再需要计数，移出计数器转入 exhausted（只判存在性）
            counters.remove(hitKey);
            exhausted.add(hitKey);
        }
        if (seq > faultTimes) {
            return -1;    // 并发同名线程造成的越界，不再尝试
        }
        return seq;
    }

    /** kill 未生效的回滚：删除记录后把计数回退到 seq-1，使该序号重新可尝试 */
    private void rollback(String hitKey, int seq) {
        exhausted.remove(hitKey);
        counters.put(hitKey, new AtomicInteger(seq - 1));
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
