package cn.chinaclear.fault.agent;

import cn.chinaclear.fault.common.FaultConfig;
import cn.chinaclear.fault.common.FaultLogger;
import cn.chinaclear.fault.common.JdbcHelper;
import cn.chinaclear.fault.common.KillUtil;
import cn.chinaclear.fault.common.MachineInfo;
import cn.chinaclear.fault.common.SchemaInitializer;
import cn.chinaclear.fault.common.dao.ErrorRecordDao;
import cn.chinaclear.fault.common.model.ErrorRecord;

import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * core 真实入口：运行在 {@link AgentClassLoader}（隔离 loader）内，由壳类 FaultAgent 反射调用。
 * 原 FaultAgent.premain 的全部主体逻辑在此，硬保护路径不变。
 *
 * 隔离边界：本方法只接收/返回 JDK 类型（String / void），跨 loader 不传任何自定义类型；
 * agent 与 sandbox module 仅通过 DB 表与进程调用交互，无共享类。
 */
public final class AgentBootstrap {

    private AgentBootstrap() {
    }

    public static void run(String agentArgs) {
        Thread current = Thread.currentThread();
        ClassLoader previous = current.getContextClassLoader();
        // premain 与应用 main 共用主线程：显式把 TCCL 指向隔离 loader，覆盖依赖内部用 TCCL
        // 做 SPI/资源加载的场景（run 内新建的线程也继承它）；返回时恢复，应用启动不受影响
        //（硬保护 kill 路径进程直接终止，无需恢复）
        current.setContextClassLoader(AgentBootstrap.class.getClassLoader());
        // config 声明在 try 外：加载失败时仍要按硬保护 kill（此时没有 DB 配置，仅本地日志留痕）
        FaultConfig config = null;
        long pid = currentPid();
        try {
            // agentArgs（-javaagent:fault-agent.jar=k=v）已不再支持：传入什么都不会生效。
            // 形参保留是因为 JVM -javaagent 规范要求 premain 必须有它，签名不可省略。
            config = FaultConfig.load(null);
            FaultLogger.init(config.logDir(), "fault-agent.log", config.logLevel());
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
            if (config.mountEnabled()) {
                // fail-fast：挂载必需 sandbox.home（必填），缺失则不必白跑解析，直接硬保护
                config.sandboxHome();
                // tmpdir 自建兜底（幂等）：systemd 部署的 ExecStartPre、手工部署漏配时，
                // agent 自建目录，避免挂载阶段 copyToTempFile 因目录缺失失败（No such file）；
                // 创建失败同样不必白跑解析——挂载必失败，直接硬保护 MOUNT
                try {
                    java.nio.file.Files.createDirectories(
                            java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")));
                } catch (java.io.IOException e) {
                    throw HardProtectException.exception("MOUNT",
                            "create java.io.tmpdir failed: " + e.getMessage(), stackOf(e), null, bootJar);
                }
            }
            List<Long> unitIds = ParseOrchestrator.parseAndStore(config, bootJar, parseDeadline);
            if (!config.mountEnabled()) {
                FaultLogger.info("mount.enabled=false -> parse-only mode (results in DB), "
                        + "release application startup without fault injection");
                return;
            }
            // 挂载阶段独立计时（mount.timeout.ms）
            long mountDeadline = System.currentTimeMillis() + config.mountTimeoutMs();
            try {
                SandboxMountInvoker.mountSync(config, pid, unitIds, bootJar, mountDeadline);
            } finally {
                // 无论挂载成败都托孤：mountSync 尝试期间 sandbox 已创建副本，失败路径同样泄漏
                TmpReaper.spawnAfterMount(pid);
            }
            FaultLogger.info("premain completed: mount OK, release application startup");
        } catch (HardProtectException e) {
            hardProtect(config, e);
        } catch (Throwable t) {
            hardProtect(config, HardProtectException.exception("PARSE",
                    "premain unexpected: " + t.getMessage(), stackOf(t), null, null));
        } finally {
            current.setContextClassLoader(previous);
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
            JdbcHelper db = new JdbcHelper(config.jdbcUrl(), config.jdbcUsername(), config.jdbcPassword(),
                    config.jdbcDriver());
            // 表1 不做任何回退：未完成（pending）的单元下次启动会被重新解析，表4 记录错误详情即可
            ErrorRecord er = new ErrorRecord();
            er.setPhase(e.phase);
            er.setErrorType(e.errorType);
            // 不做截断：message / detail 为 MEDIUMTEXT，列宽兜底统一由 ErrorRecordDao 负责（单点、覆盖全部写入路径）
            er.setMessage(e.getMessage());
            er.setDetail(e.detail);
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
}
