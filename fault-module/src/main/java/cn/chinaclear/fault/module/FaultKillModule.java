package cn.chinaclear.fault.module;

import cn.chinaclear.fault.common.FaultConfig;
import cn.chinaclear.fault.common.FaultLogger;
import cn.chinaclear.fault.common.JdbcHelper;
import cn.chinaclear.fault.common.KillUtil;
import cn.chinaclear.fault.common.MachineInfo;
import cn.chinaclear.fault.common.dao.ClassMethodDao;
import cn.chinaclear.fault.common.dao.ErrorRecordDao;
import cn.chinaclear.fault.common.dao.FaultRecordDao;
import cn.chinaclear.fault.common.model.ClassMethodInfo;
import cn.chinaclear.fault.common.model.ErrorRecord;
import com.alibaba.jvm.sandbox.api.Information;
import com.alibaba.jvm.sandbox.api.Module;
import com.alibaba.jvm.sandbox.api.annotation.Command;
import com.alibaba.jvm.sandbox.api.listener.ext.EventWatchBuilder;
import com.alibaba.jvm.sandbox.api.resource.ModuleEventWatcher;
import org.kohsuke.MetaInfServices;

import javax.annotation.Resource;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 故障注入模块：
 * ./sandbox.sh -p &lt;pid&gt; -d "fault-module/inject?id=1,2,3"
 * 从表2 拉取指定解析单元的全部类-方法，对每个类挂 withLoad+withLine 行级 watch；
 * beforeLine 中 INSERT 表3 抢占，成功则 kill 当前进程，DuplicateKey 放行。
 */
@MetaInfServices(Module.class)
@Information(id = "fault-module", version = "1.0.0", author = "fault-sandbox")
public class FaultKillModule implements Module {

    @Resource
    private ModuleEventWatcher moduleEventWatcher;

    /** 已注入的解析单元（重复 inject 防重，避免重复注册 watch 导致多倍回调） */
    private final Set<Long> injectedUnits = ConcurrentHashMap.newKeySet();

    @Command("inject")
    public void inject(final Map<String, String> param) {
        FaultConfig config = FaultConfig.load(null);
        // module 日志目录独立配置（见本模块 config.yml 的 log.dir）
        FaultLogger.init(config.logDir(), "fault-module.log");
        final long pid = currentPid();
        List<Long> unitIds = null;
        try {
            // 轮次 tag：JVM 系统属性 -Dfault.tag；缺失则直接在模块内 kill（不依赖 agent 传递）
            final String tag = System.getProperty("fault.tag", "").trim();
            if (tag.isEmpty()) {
                FaultLogger.error("jvm property 'fault.tag' missing -> kill process per policy (no injection without a round tag)");
                recordMissingTag(config, pid, param);
                KillUtil.killCurrentProcess(pid);
                return;
            }

            unitIds = parseUnitIds(param.get("id"));
            // 应用 bootJar 部署路径（判重键：路径即应用标识）：agent 传入（URL 编码），
            // 缺失时兜底从 sun.java.command 解析 -jar 参数，再不行填 unknown（兼容旧 agent 不崩）
            String bootJar = resolveBootJar(param.get("bootJar"));
            final String bootJarHash = md5Hex16(bootJar);
            FaultLogger.info("bootJar resolved: " + bootJar + " (hash=" + bootJarHash + ")");
            if (injectedUnits.containsAll(unitIds)) {
                FaultLogger.info("inject skipped: units already injected, unitIds=" + unitIds + ", tag=" + tag);
                return;
            }
            FaultLogger.info("inject requested, unitIds=" + unitIds + ", tag=" + tag
                    + ", batchSize=" + config.injectBatchSize());
            int includeCount = config.includeMethods().size();
            FaultLogger.info("filter: include=" + includeCount + " regex(es)"
                    + (includeCount == 0 ? " (all methods are candidates)" : " (only matched methods are injected)")
                    + ", exclude=" + config.excludeMethods().size() + " regex(es)"
                    + ", driver=" + config.jdbcDriver());

            JdbcHelper db = new JdbcHelper(config.jdbcUrl(), config.jdbcUsername(), config.jdbcPassword(),
                    config.jdbcDriver());
            ClassMethodDao methodDao = new ClassMethodDao(db);
            FaultRecordDao faultRecordDao = new FaultRecordDao(db);
            ErrorRecordDao errorRecordDao = new ErrorRecordDao(db);

            // 游标分批读取：每批注册后即可被回收，避免大项目全量方法一次性读入内存。
            // 游标为 (类名, 方法名, 主键) 三元组，使同一 (类名, 方法名) 的重载行连续分布，
            // 配合 registerBatch 内按方法名去重，避免同一方法被跨批重复注册 watch
            String lastClass = "";
            String lastMethod = "";
            RegisterStat stat = new RegisterStat();
            long totalMethods = 0L;
            while (true) {
                List<ClassMethodInfo> batch = methodDao.findPageByUnitIds(unitIds, lastClass,
                        lastMethod, config.injectBatchSize());
                if (batch.isEmpty()) {
                    break;
                }
                // 断点推进到本批最后一条（批次已按 类名, 方法名 升序）
                ClassMethodInfo tail = batch.get(batch.size() - 1);
                lastClass = tail.getClassName();
                lastMethod = tail.getMethodName();
                totalMethods += batch.size();

                // 本批：类分组 + 方法名去重（onBehavior 按名匹配，注册一次即覆盖全部重载）。
                // 跨批无需去重：下一批以 (类名, 方法名) 严格大于断点，同名重载行不会再次出现
                Map<String, List<String>> classMethods = new LinkedHashMap<>();
                Map<String, Long> classToUnitId = new HashMap<>();
                for (ClassMethodInfo m : batch) {
                    List<String> names = classMethods.computeIfAbsent(m.getClassName(), k -> new ArrayList<>());
                    if (!names.contains(m.getMethodName())) {
                        names.add(m.getMethodName());
                    }
                    classToUnitId.put(m.getClassName(), m.getUnitId());
                }
                // 类数不去重累计：按类名去重需要常驻一个 Set，类数多时占用可观，
                // 且各批情况已由上面的 batch registered 逐批打印，无需再汇总出一个口径易混淆的总数

                // 每批一个 listener（只持本批映射，随批次释放）
                KillAdviceListener listener =
                        new KillAdviceListener(faultRecordDao, errorRecordDao, classToUnitId, pid, tag,
                                bootJar, bootJarHash, config.faultTimes());
                registerBatch(classMethods, listener, errorRecordDao, unitIds,
                        config.includeMethods(), config.excludeMethods(), stat);
                FaultLogger.info("batch registered: methods=" + batch.size()
                        + " classes=" + classMethods.size()
                        + " cursor=" + lastClass + "#" + lastMethod);
            }

            if (totalMethods == 0) {
                // 保护不完整：挂载了却没有任何方法 = 绝不放行
                FaultLogger.error("no methods found for unitIds=" + unitIds + " -> kill per policy");
                recordInjectError(errorRecordDao,
                        "no methods found for unitIds=" + unitIds + " (check t_class_method)", null, unitIds);
                KillUtil.killCurrentProcess(pid);
                return;
            }

            if (stat.methods == 0) {
                // 解析到了方法，但经 include/exclude 过滤后一个方法都没注入：名单与解析结果无交集，
                // 等价于"挂载了却零覆盖"，按硬保护处理（绝不放行）
                FaultLogger.error("no class registered after include/exclude filtering, unitIds=" + unitIds
                        + " -> kill per policy");
                recordInjectError(errorRecordDao,
                        "no class registered after include/exclude filtering, unitIds=" + unitIds
                                + " (check inject.include.methods / exclude.methods)", null, unitIds);
                KillUtil.killCurrentProcess(pid);
                return;
            }

            injectedUnits.addAll(unitIds);
            FaultLogger.info("inject done: injected=" + stat.methods + " methods (=" + stat.classes
                    + " class-watches), scanned=" + totalMethods + " rows, tag=" + tag
                    + ", waiting for first line hit");
        } catch (Throwable t) {
            // 模块内自行兜住所有致命异常：表4 留痕后直接 kill（不依赖 sandbox/agent 传递）
            FaultLogger.error("inject failed, kill process per policy", t);
            try {
                recordInjectError(new ErrorRecordDao(new JdbcHelper(config.jdbcUrl(),
                                config.jdbcUsername(), config.jdbcPassword(), config.jdbcDriver())),
                        "inject failed: " + t.getMessage(), stackOf(t), unitIds);
            } catch (Throwable ignore) {
                // DB 不可用：仅本地日志
            }
            KillUtil.killCurrentProcess(pid);
        }
    }

    /**
     * 累计注册结果（不记录具体类名，避免为统计常驻一个随类数增长的集合）。
     */
    private static final class RegisterStat {
        /** 类级 watch 数：一个类一次链式注册 */
        int classes;
        /** 方法级注入点数：一个方法名即一个注入点（按名匹配会覆盖它的全部重载） */
        int methods;
    }

    /**
     * 注册一批类的 watch（每个类一次链式注册，方法逐个 onBehavior 链上）。
     * 过滤顺序：先按 inject.include.methods 选入（名单为空则全部入选），再按 exclude.methods 排除；
     * 只有既在名单内、又未被排除的方法会被注入。
     * 结果累加进 stat（类级 watch 数、方法级注入点数）。
     */
    private void registerBatch(Map<String, List<String>> classMethods, KillAdviceListener listener,
                             ErrorRecordDao errorRecordDao, List<Long> unitIds,
                             List<String> includeMethodRegex, List<String> excludeMethodRegex,
                             RegisterStat stat) {
        java.util.regex.Pattern[] includeMethod = compilePatterns(includeMethodRegex);
        java.util.regex.Pattern[] excludeMethod = compilePatterns(excludeMethodRegex);
        int skipped = 0;
        for (Map.Entry<String, List<String>> entry : classMethods.entrySet()) {
            String className = entry.getKey();
            List<String> kept = new ArrayList<>();
            for (String name : entry.getValue()) {
                String qualifiedName = className + "." + name;
                if (includeMethod.length > 0 && !matchesAny(includeMethod, qualifiedName)) {
                    continue;    // 配置了名单但未命中：不注入
                }
                if (matchesAny(excludeMethod, qualifiedName)) {
                    continue;    // 命中排除：不注入
                }
                kept.add(name);
            }
            if (kept.isEmpty()) {
                FaultLogger.info("no method kept after include/exclude filtering, skip class: " + className);
                skipped++;
                continue;
            }
            try {
                List<String> methodNames = kept;
                EventWatchBuilder.IBuildingForBehavior building = new EventWatchBuilder(moduleEventWatcher)
                        .onClass(entry.getKey())
                        .onBehavior(methodNames.get(0));
                for (int i = 1; i < methodNames.size(); i++) {
                    building = building.onBehavior(methodNames.get(i));
                }
                building.onWatching()
                        .withLine()
                        .onWatch(listener);
                // 一个类一次 watch 注册（多个方法名链在同一个 watch 上）；
                // 注入故障点数按方法名计——一个方法名即一个注入点，按名匹配会覆盖它的全部重载
                stat.classes++;
                stat.methods += methodNames.size();
            } catch (Throwable t) {
                // 单类注册失败 = 故障覆盖不完整，落表4 后抛出，由 inject 外层统一 kill
                FaultLogger.error("register watch failed for class=" + entry.getKey(), t);
                recordInjectError(errorRecordDao, "watch register failed for class=" + entry.getKey(),
                        stackOf(t), unitIds);
                throw new IllegalStateException("watch register failed for class=" + entry.getKey(), t);
            }
        }
        if (skipped > 0) {
            FaultLogger.info("skipped classes (no method kept): " + skipped);
        }
    }

    private static java.util.regex.Pattern[] compilePatterns(List<String> regexes) {
        List<java.util.regex.Pattern> out = new ArrayList<>();
        for (String r : regexes) {
            try {
                out.add(java.util.regex.Pattern.compile(r));
            } catch (Throwable t) {
                FaultLogger.warn("invalid regex \"" + r + "\", skipped: " + t.getMessage());
            }
        }
        return out.toArray(new java.util.regex.Pattern[0]);
    }

    private static boolean matchesAny(java.util.regex.Pattern[] patterns, String value) {
        for (java.util.regex.Pattern p : patterns) {
            if (p.matcher(value).matches()) {
                return true;
            }
        }
        return false;
    }

    /** 表4 留痕：写失败仅告警（DB 可能正是不可用的一方） */
    private void recordInjectError(ErrorRecordDao dao, String message, String detail, List<Long> unitIds) {
        try {
            ErrorRecord er = new ErrorRecord();
            er.setPhase("INJECT");
            er.setErrorType("EXCEPTION");
            er.setMessage(message);
            er.setDetail(detail);
            er.setUnitIds(join(unitIds));
            er.setBootJar(System.getProperty("sun.java.command"));
            er.setHostname(MachineInfo.hostname());
            er.setIp(MachineInfo.ip());
            dao.insert(er);
        } catch (Throwable ignore) {
            FaultLogger.warn("write t_error_record failed (local log only)");
        }
    }

    private static String join(List<Long> ids) {
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

    private static String stackOf(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    /** 无 tag 策略：记录表4 后 kill（写表失败仅留本地日志，kill 必达） */
    private void recordMissingTag(FaultConfig config, long pid, Map<String, String> injectParam) {
        try {
            JdbcHelper db = new JdbcHelper(config.jdbcUrl(), config.jdbcUsername(), config.jdbcPassword(),
                    config.jdbcDriver());
            ErrorRecord er = new ErrorRecord();
            er.setPhase("MOUNT");
            er.setErrorType("EXCEPTION");
            er.setMessage("jvm property 'fault.tag' missing, process killed per policy");
            er.setDetail(null);
            er.setUnitIds(injectParam != null ? injectParam.get("id") : null);
            er.setBootJar(System.getProperty("sun.java.command"));
            er.setHostname(MachineInfo.hostname());
            er.setIp(MachineInfo.ip());
            new ErrorRecordDao(db).insert(er);
        } catch (Throwable t) {
            FaultLogger.error("write t_error_record failed (local log only)", t);
        }
    }

    /**
     * 解析应用 bootJar 部署路径：优先取 agent 经 inject 参数传入（URL 编码），
     * 缺失时兜底从 sun.java.command 解析 -jar 参数，均不可得填 unknown（兼容旧 agent 不崩）。
     */
    private static String resolveBootJar(String urlEncoded) {
        if (urlEncoded != null && !urlEncoded.trim().isEmpty()) {
            try {
                return java.net.URLDecoder.decode(urlEncoded, "UTF-8");
            } catch (Throwable t) {
                FaultLogger.warn("decode bootJar param failed: " + t.getMessage());
            }
        }
        String command = System.getProperty("sun.java.command", "");
        for (String tok : command.trim().split("\\s+")) {
            if (tok.endsWith(".jar")) {
                return tok;
            }
        }
        return "unknown";
    }

    /** MD5 前 16 位 hex（bootJar 路径的索引键；与测试库回填算法一致） */
    private static String md5Hex16(String value) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.substring(0, 16);
        } catch (Throwable t) {
            throw new IllegalStateException("md5 unavailable", t);
        }
    }

    private static List<Long> parseUnitIds(String ids) {
        if (ids == null || ids.trim().isEmpty()) {
            throw new IllegalArgumentException("param 'id' is required, e.g. id=1,2,3");
        }
        List<Long> out = new ArrayList<>();
        for (String s : ids.split(",")) {
            if (!s.trim().isEmpty()) {
                out.add(Long.parseLong(s.trim()));
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("param 'id' is empty");
        }
        return out;
    }

    private static long currentPid() {
        try {
            return Long.parseLong(ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);
        } catch (Throwable t) {
            return -1L;
        }
    }
}
