package cn.chinaclear.fault.module;

import cn.chinaclear.fault.common.FaultConfig;
import cn.chinaclear.fault.common.FaultLogger;
import cn.chinaclear.fault.common.JdbcHelper;
import cn.chinaclear.fault.common.KillUtil;
import cn.chinaclear.fault.common.MachineInfo;
import cn.chinaclear.fault.common.dao.ClassMethodDao;
import cn.chinaclear.fault.common.dao.ErrorRecordDao;
import cn.chinaclear.fault.common.dao.FaultRecordDao;
import cn.chinaclear.fault.common.dao.JarRecordDao;
import cn.chinaclear.fault.common.model.ClassMethodInfo;
import cn.chinaclear.fault.common.model.ErrorRecord;
import cn.chinaclear.fault.common.model.InjectFilter;
import cn.chinaclear.fault.common.model.JarRecord;
import cn.chinaclear.fault.common.model.ThreadFilter;
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
        // module 日志目录独立配置（见本模块 config.yml 的 log.dir / log.level）
        FaultLogger.init(config.logDir(), "fault-module.log", config.logLevel());
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
            // 过滤块：配置非法（scope 非法 / 正则非法）在构造时抛出，由本方法外层 catch 落表4 后 kill
            final List<InjectFilter> filters = config.injectFilters();
            FaultLogger.info("filters: " + (filters.isEmpty()
                    ? "none (all parsed methods are injection candidates)"
                    : filters.size() + " block(s), applied in order -> " + filters));
            // 线程名过滤：配置非法（正则非法）在构造时抛出，由本方法外层 catch 落表4 后 kill。
            // 运行期判定发生在 listener.beforeLine 取栈之前，不通过的线程静默放行
            final ThreadFilter threadFilter = config.threadFilter();
            FaultLogger.info("thread filter: " + (threadFilter.enabled()
                    ? threadFilter + " (non-passing threads are silently released)"
                    : "none (all threads participate)"));

            JdbcHelper db = new JdbcHelper(config.jdbcUrl(), config.jdbcUsername(), config.jdbcPassword(),
                    config.jdbcDriver());
            ClassMethodDao methodDao = new ClassMethodDao(db);
            FaultRecordDao faultRecordDao = new FaultRecordDao(db);
            ErrorRecordDao errorRecordDao = new ErrorRecordDao(db);

            // 单元元信息（unit_type + source_jar）：表2 只带 unit_id，方法属于应用代码
            // 还是哪个第三方 jar 需回到表1 才能确定，用于过滤块的作用范围判定。
            // 一次性查完（单元数为个位数），不随方法批次增长。
            Map<Long, JarRecord> unitMeta = new JarRecordDao(db).findByIds(unitIds);
            if (unitMeta.size() != unitIds.size()) {
                throw new IllegalStateException("unit meta missing: expected=" + unitIds.size()
                        + " found=" + unitMeta.size() + ", unitIds=" + unitIds);
            }
            for (JarRecord u : unitMeta.values()) {
                FaultLogger.info("unit: id=" + u.getId() + " type=" + u.getUnitType()
                        + " source=" + u.getSourceJar());
            }

            // 游标分批读取：每批注册后即可被回收，避免大项目全量方法一次性读入内存。
            // 游标为 (类名, 方法名, 主键) 三元组，使同一 (类名, 方法名) 的重载行连续分布，
            // 配合 registerBatch 内按方法名去重，避免同一方法被跨批重复注册 watch
            String lastClass = "";
            String lastMethod = "";
            long totalMethods = 0L;
            long injectedMethods = 0L;
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
                // 每批一个 listener（只持本批映射，随批次释放）；threadFilter 各批共享同一实例
                KillAdviceListener listener =
                        new KillAdviceListener(faultRecordDao, errorRecordDao, classToUnitId, pid, tag,
                                bootJar, bootJarHash, config.faultTimes(), threadFilter);
                // 每批独立的结果对象：仅两个计数器，不持有类名等集合（类数多时避免常驻内存）
                RegisterStat batchStat = new RegisterStat();
                registerBatch(classMethods, classToUnitId, listener, errorRecordDao, unitIds,
                        filters, unitMeta, batchStat);
                injectedMethods += batchStat.methods;
                FaultLogger.info("batch registered: scanned=" + batch.size()
                        + " rows, classes=" + batchStat.classes
                        + ", injected=" + batchStat.methods + " methods"
                        + ", cursor=" + lastClass + "#" + lastMethod);
            }

            if (totalMethods == 0) {
                // 保护不完整：挂载了却没有任何方法 = 绝不放行
                FaultLogger.error("no methods found for unitIds=" + unitIds + " -> kill per policy");
                recordInjectError(errorRecordDao,
                        "no methods found for unitIds=" + unitIds + " (check t_class_method)", null, unitIds);
                KillUtil.killCurrentProcess(pid);
                return;
            }

            if (injectedMethods == 0) {
                // 解析到了方法，但经 include/exclude 过滤后一个方法都没注入：名单与解析结果无交集，
                // 等价于"挂载了却零覆盖"，按硬保护处理（绝不放行）
                FaultLogger.error("no class registered after include/exclude filtering, unitIds=" + unitIds
                        + " -> kill per policy");
                recordInjectError(errorRecordDao,
                        "no class registered after include/exclude filtering, unitIds=" + unitIds
                                + " (check inject.filters)", null, unitIds);
                KillUtil.killCurrentProcess(pid);
                return;
            }

            injectedUnits.addAll(unitIds);
            FaultLogger.info("inject done: injected=" + injectedMethods + " methods, scanned="
                    + totalMethods + " rows, tag=" + tag + ", waiting for first line hit");
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
     *
     * 过滤按配置顺序串行作用：作用范围不覆盖该类所属单元的块不表态；范围覆盖的块内
     * 先 include 选入（空 = 全选）再 exclude 过滤，任一块把方法拦下即不注入。
     * 结果累加进 stat（类级 watch 数、方法级注入点数）。
     */
    private void registerBatch(Map<String, List<String>> classMethods,
                             Map<String, Long> classToUnitId, KillAdviceListener listener,
                             ErrorRecordDao errorRecordDao, List<Long> unitIds,
                             List<InjectFilter> filters, Map<Long, JarRecord> unitMeta,
                             RegisterStat stat) {
        int skipped = 0;
        for (Map.Entry<String, List<String>> entry : classMethods.entrySet()) {
            String className = entry.getKey();
            JarRecord unit = unitMeta.get(classToUnitId.get(className));
            if (unit == null) {
                throw new IllegalStateException("unit meta not found for class=" + className);
            }
            List<String> kept = new ArrayList<>();
            for (String name : entry.getValue()) {
                if (passesFilters(filters, unit, className + "." + name)) {
                    kept.add(name);
                }
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

    /**
     * 串行过滤：按配置顺序逐块判定。
     * 作用范围不覆盖该方法所属单元的块不表态；范围覆盖的块内，include 未命中或 exclude 命中即被拦下。
     * 于是范围互斥的块（class 与 lib）各自管各自，范围重叠的块（global 与 class）则需逐块过关。
     */
    private static boolean passesFilters(List<InjectFilter> filters, JarRecord unit,
                                         String qualifiedName) {
        for (InjectFilter f : filters) {
            if (!f.covers(unit.getUnitType(), unit.getSourceJar())) {
                continue;
            }
            if (!f.passes(qualifiedName)) {
                return false;
            }
        }
        return true;
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
