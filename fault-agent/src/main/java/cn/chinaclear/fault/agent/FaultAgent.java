package cn.chinaclear.fault.agent;

import cn.chinaclear.fault.common.FaultConfig;
import cn.chinaclear.fault.common.FaultLogger;
import cn.chinaclear.fault.common.JdbcHelper;
import cn.chinaclear.fault.common.KillUtil;
import cn.chinaclear.fault.common.MachineInfo;
import cn.chinaclear.fault.common.SchemaInitializer;
import cn.chinaclear.fault.common.dao.ErrorRecordDao;
import cn.chinaclear.fault.common.dao.JarRecordDao;
import cn.chinaclear.fault.common.model.ErrorRecord;

import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * premain 入口：
 * 读配置 → 定位 bootJar → 建库建表 → 解析落库（状态机幂等）→ 同步挂载 sandbox 模块。
 * 任何阶段超时/异常 → 表1 置 failed（解析中单元）+ 写表4 → kill 当前进程（硬保护）。
 * 唯一放行例外：agent.enabled=false。
 */
public final class FaultAgent {

    private static final int MAX_MESSAGE = 1000;
    private static final int MAX_DETAIL = 60000;

    private FaultAgent() {
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        // config 声明在 try 外：加载失败时仍要按硬保护 kill（此时没有 DB 配置，仅本地日志留痕）
        FaultConfig config = null;
        long pid = currentPid();
        try {
            config = FaultConfig.load(agentArgs);
            FaultLogger.init(config.logDir(), "fault-agent.log");
            long parseDeadline = System.currentTimeMillis() + config.parseTimeoutMs();
            FaultLogger.info("premain start: pid=" + pid + ", parseTimeout=" + config.parseTimeoutMs()
                    + "ms, mountTimeout=" + config.mountTimeoutMs() + "ms"
                    + ", machine=" + MachineInfo.hostname() + "/" + MachineInfo.ip());
            String bootJar = BootJarLocator.locate();
            if (bootJar == null) {
                throw HardProtectException.exception("PARSE",
                        "bootJar not located: application must be started with -jar", null, null, null);
            }
            try {
                SchemaInitializer.checkTables(config);
            } catch (RuntimeException e) {
                throw HardProtectException.exception("DB", "schema check failed: " + e.getMessage(),
                        stackOf(e), null, bootJar);
            }
            List<Long> unitIds = ParseOrchestrator.parseAndStore(config, bootJar, parseDeadline);
            if (!config.mountEnabled()) {
                FaultLogger.info("mount.enabled=false -> parse-only mode (results in DB), "
                        + "release application startup without fault injection");
                return;
            }
            // 挂载阶段独立计时（mount.timeout.ms）
            long mountDeadline = System.currentTimeMillis() + config.mountTimeoutMs();
            SandboxMountInvoker.mountSync(config, pid, unitIds, bootJar, mountDeadline);
            FaultLogger.info("premain completed: mount OK, release application startup");
        } catch (HardProtectException e) {
            hardProtect(config, e);
        } catch (Throwable t) {
            hardProtect(config, HardProtectException.exception("PARSE",
                    "premain unexpected: " + t.getMessage(), stackOf(t), null, null));
        }
    }

    /** 硬保护：解析中单元置 failed → 写表4（尽力）→ kill 当前进程 */
    private static void hardProtect(FaultConfig config, HardProtectException e) {
        FaultLogger.error("HARD PROTECT: phase=" + e.phase + ", type=" + e.errorType
                + ", msg=" + e.getMessage() + ", unitIds=" + e.unitIds, e);
        if (config == null) {
            FaultLogger.error("config unavailable (missing/invalid), skip DB records (local log only)");
            if (!KillUtil.killCurrentProcess(currentPid())) {
                Runtime.getRuntime().halt(137);
            }
            return;
        }
        try {
            JdbcHelper db = new JdbcHelper(config.jdbcUrl(), config.jdbcUsername(), config.jdbcPassword());
            JarRecordDao recordDao = new JarRecordDao(db);
            if (e.unitIds != null) {
                for (Long id : e.unitIds) {
                    try {
                        recordDao.markFailed(id);
                    } catch (Throwable ignore) {
                        // keep marking others
                    }
                }
            }
            ErrorRecord er = new ErrorRecord();
            er.setPhase(e.phase);
            er.setErrorType(e.errorType);
            er.setMessage(trim(e.getMessage(), MAX_MESSAGE));
            er.setDetail(trim(e.detail, MAX_DETAIL));
            er.setUnitIds(joinIds(e.unitIds));
            er.setBootJar(e.bootJar);
            er.setHostname(MachineInfo.hostname());
            er.setIp(MachineInfo.ip());
            new ErrorRecordDao(db).insert(er);
        } catch (Throwable t2) {
            // DB 不可用：退化为本地日志
            FaultLogger.error("write t_error_record failed, fallback to local log only", t2);
        }
        if (!KillUtil.killCurrentProcess(currentPid())) {
            // 命令级 kill 未生效：halt 最终兜底（表4 记录保留作审计痕迹）
            Runtime.getRuntime().halt(137);
        }
    }

    static long currentPid() {
        try {
            // RuntimeMXBean#getName(): "<pid>@<hostname>"
            return Long.parseLong(ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);
        } catch (Throwable t) {
            return -1L;
        }
    }

    static String stackOf(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    static String joinIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }

    private static String trim(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...(truncated)";
    }
}
