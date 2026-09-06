package cn.chinaclear.fault.common.model;

import cn.chinaclear.fault.common.BootJarParser;
import cn.chinaclear.fault.common.FaultLogger;
import cn.chinaclear.fault.common.RegexPatterns;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 注入过滤块：作用范围 + 范围限定（libs）+ 选入/排除名单。
 *
 * 多个块按配置顺序串行作用：范围不覆盖该方法的块不表态；范围覆盖的块内
 * 先按 include 选入（空 = 全选）、再按 exclude 过滤，任一块把方法拦下即不注入。
 *
 * 正则在构造时一次性预编译，非法即抛 IllegalStateException
 * （module 侧由 FaultKillModule.inject 的 catch(Throwable) 落表4 后 kill）。
 */
public final class InjectFilter {

    public enum Scope {
        /** 全局：覆盖所有已解析方法 */
        GLOBAL,
        /** 应用自身代码：BOOT-INF/classes 单元 */
        CLASS,
        /** 第三方 jar：BOOT-INF/lib 白名单单元 */
        LIB
    }

    private final Scope scope;
    /** 仅 LIB 使用：jar 文件名正则，空 = 覆盖全部 lib 单元 */
    private final Pattern[] libs;
    /** 空 = 全选 */
    private final Pattern[] include;
    /** 空 = 不排除 */
    private final Pattern[] exclude;

    public InjectFilter(String scope, List<String> libs, List<String> include, List<String> exclude,
                        int index) {
        this.scope = parseScope(scope, index);
        if (this.scope != Scope.LIB && !libs.isEmpty()) {
            FaultLogger.warn(key(index, "libs") + " is only used by scope=lib, ignored");
        }
        this.libs = RegexPatterns.compile(libs, key(index, "libs"));
        this.include = RegexPatterns.compile(include, key(index, "include"));
        this.exclude = RegexPatterns.compile(exclude, key(index, "exclude"));
    }

    /** 该块某个字段的配置键，用于错误与告警消息定位到具体配置项 */
    private static String key(int index, String field) {
        return "inject.filters." + index + "." + field;
    }

    private static Scope parseScope(String scope, int index) {
        if (scope == null || scope.trim().isEmpty()) {
            throw new IllegalStateException(key(index, "scope") + " is required（global / class / lib）");
        }
        try {
            return Scope.valueOf(scope.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("invalid " + key(index, "scope") + " \"" + scope
                    + "\"（global / class / lib）", e);
        }
    }

    /** 本块的作用范围是否覆盖该方法所属的解析单元 */
    public boolean covers(String unitType, String sourceJar) {
        switch (scope) {
            case GLOBAL:
                return true;
            case CLASS:
                return BootJarParser.UNIT_CLASSES.equals(unitType);
            case LIB:
                return BootJarParser.UNIT_LIB_JAR.equals(unitType)
                        && (libs.length == 0
                        || RegexPatterns.matchesAny(libs, sourceJar == null ? "" : sourceJar));
            default:
                return false;
        }
    }

    /** 串行过滤的一步：false = 本块把该方法拦下（include 未命中，或 exclude 命中） */
    public boolean passes(String qualifiedName) {
        if (include.length > 0 && !RegexPatterns.matchesAny(include, qualifiedName)) {
            return false;
        }
        return !RegexPatterns.matchesAny(exclude, qualifiedName);
    }

    /** 过滤概要日志：作用范围与三处正则条数 */
    @Override
    public String toString() {
        return "scope=" + scope.name().toLowerCase() + " libs=" + libs.length
                + " include=" + include.length + " exclude=" + exclude.length;
    }
}
