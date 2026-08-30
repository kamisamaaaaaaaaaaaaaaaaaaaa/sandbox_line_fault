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
        FaultLogger.init("fault-module.log");
        FaultConfig config = FaultConfig.load(null);
        final long pid = currentPid();

        // 轮次 tag：JVM 系统属性 -Dfault.tag；缺失则直接 kill，绝不让进程无轮次标识地跑下去
        final String tag = System.getProperty("fault.tag", "").trim();
        if (tag.isEmpty()) {
            FaultLogger.error("jvm property 'fault.tag' missing -> kill process per policy (no injection without a round tag)");
            recordMissingTag(config, pid, param);
            KillUtil.killCurrentProcess(pid);
            return;
        }

        List<Long> unitIds = parseUnitIds(param.get("id"));
        if (injectedUnits.containsAll(unitIds)) {
            FaultLogger.info("inject skipped: units already injected, unitIds=" + unitIds + ", tag=" + tag);
            return;
        }
        FaultLogger.info("inject requested, unitIds=" + unitIds + ", tag=" + tag);

        JdbcHelper db = new JdbcHelper(config.jdbcUrl(), config.jdbcUsername(), config.jdbcPassword());
        List<ClassMethodInfo> methods = new ClassMethodDao(db).findByUnitIds(unitIds);
        if (methods.isEmpty()) {
            FaultLogger.warn("no methods found for unitIds=" + unitIds + ", nothing to watch");
            return;
        }

        // 类分组 + 方法名去重（onBehavior 按名匹配，天然覆盖重载）；类名 → unitId 映射供表3 记录
        Map<String, List<String>> classMethods = new LinkedHashMap<>();
        Map<String, Long> classToUnitId = new HashMap<>();
        for (ClassMethodInfo m : methods) {
            List<String> names = classMethods.computeIfAbsent(m.getClassName(), k -> new ArrayList<>());
            if (!names.contains(m.getMethodName())) {
                names.add(m.getMethodName());
            }
            classToUnitId.put(m.getClassName(), m.getUnitId());
        }
        FaultLogger.info("watch target: classes=" + classMethods.size()
                + " methods=" + methods.size() + ", tag=" + tag);

        final FaultRecordDao faultRecordDao = new FaultRecordDao(db);
        final KillAdviceListener listener = new KillAdviceListener(faultRecordDao, classToUnitId, pid, tag);

        // 每个类一次链式注册（方法逐个 onBehavior 链上）；watcher 常驻 matcher，对启动后才加载的类同样生效
        int registered = 0;
        for (Map.Entry<String, List<String>> entry : classMethods.entrySet()) {
            try {
                List<String> methodNames = entry.getValue();
                EventWatchBuilder.IBuildingForBehavior building = new EventWatchBuilder(moduleEventWatcher)
                        .onClass(entry.getKey())
                        .onBehavior(methodNames.get(0));
                for (int i = 1; i < methodNames.size(); i++) {
                    building = building.onBehavior(methodNames.get(i));
                }
                building.onWatching()
                        .withLine()
                        .onWatch(listener);
                registered++;
            } catch (Throwable t) {
                // 单类注册失败不影响其他类
                FaultLogger.error("register watch failed for class=" + entry.getKey(), t);
            }
        }
        injectedUnits.addAll(unitIds);
        FaultLogger.info("inject done: registered=" + registered + "/" + classMethods.size()
                + " classes, tag=" + tag + ", waiting for first line hit");
    }

    /** 无 tag 策略：记录表4 后 kill（写表失败仅留本地日志，kill 必达） */
    private void recordMissingTag(FaultConfig config, long pid, Map<String, String> injectParam) {
        try {
            JdbcHelper db = new JdbcHelper(config.jdbcUrl(), config.jdbcUsername(), config.jdbcPassword());
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
