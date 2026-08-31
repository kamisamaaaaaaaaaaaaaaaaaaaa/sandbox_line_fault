package cn.chinaclear.fault.module;

import cn.chinaclear.fault.common.FaultLogger;
import cn.chinaclear.fault.common.KillUtil;
import cn.chinaclear.fault.common.MachineInfo;
import cn.chinaclear.fault.common.dao.FaultRecordDao;
import cn.chinaclear.fault.common.model.FaultRecord;
import com.alibaba.jvm.sandbox.api.listener.ext.Advice;
import com.alibaba.jvm.sandbox.api.listener.ext.AdviceListener;

import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 行级监听：beforeLine 中 INSERT 表3 抢占（唯一索引含轮次 tag，判重仅限轮内，跨机器多节点安全）。
 * 插入成功 = 本节点赢得该行本轮的故障执行权 → kill 当前进程；
 * DuplicateKey = 该行本轮已被集群内其他节点触发 → 放行继续执行。
 * AtomicBoolean 与已确认冲突行缓存仅作同进程防撞库优化，正确性完全由数据库唯一约束保证。
 */
final class KillAdviceListener extends AdviceListener {

    private final FaultRecordDao faultRecordDao;
    private final Map<String, Long> classToUnitId;
    private final long pid;
    /** 轮次标识（JVM -Dfault.tag），判重仅限轮内 */
    private final String tag;
    private final AtomicBoolean fired = new AtomicBoolean(false);
    /** 本轮已确认被抢占的行（避免重复执行时反复撞库） */
    private final Set<String> preemptedLines = ConcurrentHashMap.newKeySet();

    KillAdviceListener(FaultRecordDao faultRecordDao, Map<String, Long> classToUnitId, long pid, String tag) {
        this.faultRecordDao = faultRecordDao;
        this.classToUnitId = classToUnitId;
        this.pid = pid;
        this.tag = tag;
    }

    @Override
    protected void beforeLine(Advice advice, int lineNum) {
        if (fired.get()) {
            return;
        }
        try {
            final String className = advice.getBehavior().getDeclaringClass().getName();
            final Long unitId = classToUnitId.get(className);
            if (unitId == null) {
                return;
            }
            final String method = advice.getBehavior().getName();
            final String lineKey = className + "#" + method + "#" + lineNum;
            if (preemptedLines.contains(lineKey)) {
                return;
            }
            FaultRecord fr = new FaultRecord();
            fr.setUnitId(unitId);
            fr.setTag(tag);
            fr.setHostname(MachineInfo.hostname());
            fr.setIp(MachineInfo.ip());
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
                boolean killed = KillUtil.killCurrentProcess(pid);
                if (!killed) {
                    // 极端场景：所有 kill 手段未生效（进程仍存活）——回滚故障记录，
                    // 避免脏判重数据永久阻止该行本轮注入，随后 halt 兜底
                    FaultLogger.error("kill not effective, rollback fault record: " + lineKey);
                    try {
                        faultRecordDao.delete(fr);
                    } catch (Throwable ignore) {
                        // rollback failure is irrelevant
                    }
                    fired.set(false);
                    preemptedLines.remove(lineKey);
                    Runtime.getRuntime().halt(137);
                }
            } else {
                // DuplicateKey：该行本轮已被其他节点触发，记忆后放行（不再反复撞库）
                preemptedLines.add(lineKey);
                FaultLogger.info("line already preempted in this round (tag=" + tag
                        + "), release execution: " + lineKey);
            }
        } catch (Throwable t) {
            // 抢占/记录失败绝不能破坏业务方法本身的执行
            FaultLogger.error("beforeLine handling failed, release line execution", t);
        }
    }
}
