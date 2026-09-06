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
import com.alibaba.jvm.sandbox.api.listener.ext.Behavior;

import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 行级监听：beforeLine 中 INSERT 表3 抢占，
 * 判重键 = 轮次 tag + bootJar 部署路径 + 行 + 线程 + 调用栈 + 第几次故障。
 * 同一行同一线程同一调用栈本轮可发生多次故障（次数上限由 inject.fault.times 决定）：
 * 插入成功 = 本节点赢得该行该线程该调用栈本轮第 fault_seq 次的故障执行权
 *          → 打印调用栈后 kill 当前进程；
 * DuplicateKey = 该次故障已被集群内其他节点/进程触发 → 计数推进后放行继续执行。
 * 正确性完全由数据库唯一索引保证；counters / exhausted 仅缓存本进程已确认的故障次数，
 * 避免存活节点在热路径行上反复撞库。
 *
 * 判重键含调用栈摘要，摘要只能由取栈算出，因此 beforeLine 每次回调都会做一次完整栈遍历。
 * 所有异常路径（取栈失败、kill 失败、记录失败、DB 异常）均落表4。
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
    /** 未用尽：行+线程+调用栈 → 已确认的故障次数（插入成功与被他节点抢占各计一次） */
    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();
    /** 已用尽：只判存在性，命中即返回，不再撞库（从 counters 移出后加入） */
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
            final Behavior behavior = advice.getBehavior();
            final String className = behavior.getDeclaringClass().getName();
            final Long unitId = classToUnitId.get(className);
            if (unitId == null) {
                return;
            }
            final String method = behavior.getName();
            final String threadName = Thread.currentThread().getName();
            // 判重键含调用栈摘要，摘要只能由取栈算出，故取栈位于快速返回之前。
            // 行级回调是热路径，一次完整栈遍历的开销远高于一次集合查找，
            // 这是判重粒度细化到调用栈的既定代价。
            final CallStack stack = CallStack.capture();
            // 方法描述符：由 Behavior 的参数类型与返回类型还原 JVM 描述符（与 t_class_method 存储格式一致），
            // 供排查时区分重载；纯观测字段，不参与判重
            final String methodDesc = descOf(behavior.getReturnType(), behavior.getParameterTypes());
            // 计数/判重键含线程与调用栈：同一行被不同线程执行、或经不同调用路径执行时
            // 故障场景不同，每个「线程 + 调用栈」组合独立计数、各占一次故障机会
            final String hitKey = className + "#" + method + "#" + lineNum + "#" + threadName
                    + "#" + stack.hash();
            lineKey = className + "#" + method + "#" + lineNum;
            if (exhausted.contains(hitKey)) {
                return;    // 该行该线程该调用栈本轮故障次数已用尽：不再撞库、不再计数
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
            fr.setMethodDesc(methodDesc);
            fr.setLineNo(lineNum);
            fr.setThreadName(threadName);
            fr.setFaultSeq(seq);
            fr.setStackHash(stack.hash());
            fr.setStackText(stack.text());
            fr.setFaultType("KILL_PROCESS");
            fr.setOccurredAt(new Date());

            boolean won = faultRecordDao.tryInsert(fr);
            if (won) {
                // kill 之前打印触发本次故障的调用栈，便于定位故障发生在哪条调用路径上。
                // 仅在本节点赢得抢占时打印：冲突放行分支会被反复执行，打印会造成日志膨胀。
                // 故障命中是演练的预期事件而非错误，级别为 INFO（但它是 kill 前最后一条，永远输出）。
                FaultLogger.info("FAULT HIT & PREEMPTED: tag=" + tag
                        + " unitId=" + unitId
                        + " class=" + className
                        + " method=" + method
                        + " line=" + lineNum
                        + " seq=" + seq + "/" + faultTimes
                        + " thread=" + threadName
                        + " stackHash=" + stack.hash()
                        + " machine=" + fr.getHostname() + "/" + fr.getIp()
                        + "\ncall stack (" + stack.frames() + " frames):\n" + stack.text());
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
                // 计数已在 nextSeq 中推进，放行继续执行（不再反复撞库）。
                // 多节点并发时该分支与冲突次数成正比，属过程明细，降为 DEBUG（log.level=INFO 下静默）
                FaultLogger.debug("fault seq already preempted in this round (tag=" + tag
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
     * 计数键为「行 + 线程 + 调用栈摘要」，每个组合独立享有 inject.fault.times 次故障机会。
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

    /**
     * 还原方法的 JVM 描述符（如 (Ljava/lang/String;I)J），与 t_class_method 中 ASM 解析出的格式一致。
     * sandbox 的 Behavior 未暴露描述符，但参数类型与返回类型足以按 JVM 规范还原；
     * 数组与内部类的 Class#getName 本身就是 JVM 内部名形式（点分替换成分隔符即可）。
     */
    private static String descOf(Class<?> returnType, Class<?>[] paramTypes) {
        StringBuilder sb = new StringBuilder(32);
        sb.append('(');
        for (Class<?> p : paramTypes) {
            sb.append(typeDesc(p));
        }
        sb.append(')').append(typeDesc(returnType));
        return sb.toString();
    }

    private static String typeDesc(Class<?> c) {
        if (c.isArray()) {
            return c.getName().replace('.', '/');
        }
        if (!c.isPrimitive()) {
            return "L" + c.getName().replace('.', '/') + ";";
        }
        if (c == int.class) {
            return "I";
        }
        if (c == long.class) {
            return "J";
        }
        if (c == double.class) {
            return "D";
        }
        if (c == float.class) {
            return "F";
        }
        if (c == boolean.class) {
            return "Z";
        }
        if (c == byte.class) {
            return "B";
        }
        if (c == char.class) {
            return "C";
        }
        if (c == short.class) {
            return "S";
        }
        return "V";
    }
}
