package cn.chinaclear.fault.common.model;

import cn.chinaclear.fault.common.RegexPatterns;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 线程名过滤：运行期排除名字不稳定的噪音线程（监控/心跳/定时任务等）。
 *
 * 语义与 {@link InjectFilter} 的单块一致：先按 include 选入（空 = 全选）、
 * 再按 exclude 过滤，命中排除名单即拦下。被拦下的线程执行到注入行时
 * **静默放行**——不写表3、不 kill、不打日志；过滤是运行期行为，不影响
 * 解析收录与注册数，故也不触发零覆盖硬保护。
 *
 * 正则在构造时一次性预编译，非法即抛 IllegalStateException
 * （module 侧由 FaultKillModule.inject 的 catch(Throwable) 落表4 后 kill）。
 *
 * 每次回调对当前线程名重新匹配、不做结果缓存：线程名可被运行时修改
 * （Thread.setName），缓存会让判定结果陈旧失真；预编译 Pattern 的匹配
 * 开销远小于一次取栈，可接受。
 */
public final class ThreadFilter {

    /** 空 = 全部线程选入 */
    private final Pattern[] include;
    /** 空 = 不排除任何线程 */
    private final Pattern[] exclude;

    public ThreadFilter(List<String> include, List<String> exclude) {
        this.include = RegexPatterns.compile(include, "thread.include");
        this.exclude = RegexPatterns.compile(exclude, "thread.exclude");
    }

    /** 该线程是否参与故障：false = 被过滤（静默放行） */
    public boolean passes(String threadName) {
        if (include.length > 0 && !RegexPatterns.matchesAny(include, threadName)) {
            return false;
        }
        return !RegexPatterns.matchesAny(exclude, threadName);
    }

    /** 是否配置了过滤规则（未配置 = 全部线程参与，判定短路跳过） */
    public boolean enabled() {
        return include.length > 0 || exclude.length > 0;
    }

    /** 过滤概要日志：两处正则条数 */
    @Override
    public String toString() {
        return "include=" + include.length + " exclude=" + exclude.length;
    }
}
