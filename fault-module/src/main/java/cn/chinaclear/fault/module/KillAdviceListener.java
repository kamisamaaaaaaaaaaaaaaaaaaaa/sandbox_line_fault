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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 行级监听：beforeLine 中 INSERT 表3 抢占（唯一索引并发控制，跨机器多节点安全）。
 * 插入成功 = 本节点赢得该行故障执行权 → kill 当前进程；
 * DuplicateKey = 该行已被集群内其他节点触发 → 放行继续执行。
 * AtomicBoolean 仅作同进程多线程防撞库优化，正确性完全由数据库唯一约束保证。
 */
final class KillAdviceListener extends AdviceListener {

    private final FaultRecordDao faultRecordDao;
    private final Map<String, Long> classToUnitId;
    private final long pid;
    private final AtomicBoolean fired = new AtomicBoolean(false);

    KillAdviceListener(FaultRecordDao faultRecordDao, Map<String, Long> classToUnitId, long pid) {
        this.faultRecordDao = faultRecordDao;
        this.classToUnitId = classToUnitId;
        this.pid = pid;
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
            FaultRecord fr = new FaultRecord();
            fr.setUnitId(unitId);
            fr.setHostname(MachineInfo.hostname());
            fr.setIp(MachineInfo.ip());
            fr.setClassName(className);
            fr.setMethodName(advice.getBehavior().getName());
            fr.setLineNo(lineNum);
            fr.setThreadName(Thread.currentThread().getName());
            fr.setFaultType("KILL_PROCESS");
            fr.setOccurredAt(new Date());

            boolean won = faultRecordDao.tryInsert(fr);
            if (won) {
                fired.set(true);
                FaultLogger.error("FAULT HIT & PREEMPTED: unitId=" + unitId
                        + " class=" + className
                        + " method=" + advice.getBehavior().getName()
                        + " line=" + lineNum
                        + " thread=" + fr.getThreadName()
                        + " machine=" + fr.getHostname() + "/" + fr.getIp());
                KillUtil.killCurrentProcess(pid);
            }
            // DuplicateKey：该行已被其他节点触发，放行继续执行
        } catch (Throwable t) {
            // 抢占/记录失败绝不能破坏业务方法本身的执行
            FaultLogger.error("beforeLine handling failed, release line execution", t);
        }
    }
}
