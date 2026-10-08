package cn.chinaclear.fault.common.model;

import cn.chinaclear.fault.common.RegexPatterns;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 线程名过滤与归组：运行期行为。
 *
 * 过滤（include/exclude）：排除名字不稳定的噪音线程（监控/心跳/定时任务等）。
 * 语义与 {@link InjectFilter} 的单块一致：先按 include 选入（空 = 全选）、
 * 再按 exclude 过滤，命中排除名单即拦下。被拦下的线程执行到注入行时
 * **静默放行**——不写表3、不 kill、不打日志；过滤是运行期行为，不影响
 * 解析收录与注册数，故也不触发零覆盖硬保护。
 *
 * 归组（group）：每条正则定义一个独立组，命中的线程以组键
 * 「group:&lt;正则原文&gt;」作为故障计数身份（表3 thread_name 与内存计数键），
 * 同组线程共享「每行每身份每调用栈 N 次」的配额；一条线程命中 k 个组，
 * 则在 k 个组里各自独立计数；未命中任何组的线程身份 = 原始线程名（现状行为）。
 * 归组只改「身份怎么取」，判重机制（唯一索引 + 冲突抢占）不变。
 *
 * 正则在构造时一次性预编译，非法即抛 IllegalStateException
 * （module 侧由 FaultKillModule.inject 的 catch(Throwable) 落表4 后 kill）。
 *
 * 每次回调对当前线程名重新匹配、不做结果缓存：线程名可被运行时修改
 * （Thread.setName），缓存会让判定结果陈旧失真；预编译 Pattern 的匹配
 * 开销远小于一次取栈，可接受。
 */
public final class ThreadFilter {

    /** 组键前缀：与真实线程名一眼区分，避免组键与线程名混淆 */
    public static final String GROUP_KEY_PREFIX = "group:";

    /** 空 = 全部线程选入 */
    private final Pattern[] include;
    /** 空 = 不排除任何线程 */
    private final Pattern[] exclude;
    /** 空 = 不归组（身份 = 原始线程名）；每条正则一个独立组 */
    private final Pattern[] groups;
    /** 组键（与 groups 一一对应）：group:&lt;正则原文&gt; */
    private final String[] groupKeys;

    public ThreadFilter(List<String> include, List<String> exclude, List<String> groups) {
        this.include = RegexPatterns.compile(include, "thread.include");
        this.exclude = RegexPatterns.compile(exclude, "thread.exclude");
        this.groups = RegexPatterns.compile(groups, "thread.group");
        this.groupKeys = new String[this.groups.length];
        for (int i = 0; i < this.groups.length; i++) {
            this.groupKeys[i] = GROUP_KEY_PREFIX + groups.get(i);
        }
    }

    /** 该线程是否参与故障：false = 被过滤（静默放行） */
    public boolean passes(String threadName) {
        if (include.length > 0 && !RegexPatterns.matchesAny(include, threadName)) {
            return false;
        }
        return !RegexPatterns.matchesAny(exclude, threadName);
    }

    /**
     * 该线程的故障计数身份列表：命中的组键（group:&lt;正则原文&gt;，命中多条全部返回，
     * 组与组独立计数）；未命中任何组时返回空列表，调用方以原始线程名作为身份。
     */
    public List<String> groupKeysOf(String threadName) {
        if (groups.length == 0) {
            return Collections.emptyList();
        }
        List<String> keys = new ArrayList<>(groups.length);
        for (int i = 0; i < groups.length; i++) {
            if (groups[i].matcher(threadName).matches()) {
                keys.add(groupKeys[i]);
            }
        }
        return keys;
    }

    /** 是否配置了过滤规则（未配置 = 全部线程参与，判定短路跳过）；归组不影响该判定 */
    public boolean enabled() {
        return include.length > 0 || exclude.length > 0;
    }

    /** 归组正则条数（0 = 不归组，身份 = 原始线程名） */
    public int groupCount() {
        return groups.length;
    }

    /** 过滤/归组概要日志：三处正则条数 */
    @Override
    public String toString() {
        return "include=" + include.length + " exclude=" + exclude.length + " group=" + groups.length;
    }
}
