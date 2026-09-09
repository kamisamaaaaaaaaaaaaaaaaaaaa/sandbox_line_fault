package cn.chinaclear.fault.module;

import cn.chinaclear.fault.common.JarHashUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 触发故障时的调用栈快照：取栈 → 裁剪工具自身帧 → 格式化 → 摘要。
 *
 * 文本与摘要由同一次取栈产生：{@link #text()} 保留完整原文（含动态代理类的随机命名帧），
 * 供日志与表3 stack_text 排查；{@link #hash()} 基于**归一化帧序列**计算，供表3 判重。
 * 两者分离的原因（B 系列实测发现）：CGLIB 增强类的类名带跨 JVM 不稳定的随机段
 *（如 {@code $$SpringCGLIB$$0}、{@code $$EnhancerBySpringCGLIB$$<hex>}），若摘要直接取原文，
 * 同一业务调用路径在每次进程重启后 hash 都不同，判重永不命中，同一行会被反复 kill。
 * 归一化规则（不解析、不枚举任何生成类命名）：有行号的帧原样保留（带随机类名的帧全部无行号，
 * 生产栈实证）；无行号的帧只保留尾部固定标记——{@code (<generated>)}、{@code (Unknown Source)}、
 * {@code (Native Method)} 均为 JVM 固定字面量，随机类名结构性不进摘要。
 *
 * 裁剪只丢弃栈顶**连续**的工具帧（JDK 取栈入口、JVM-Sandbox 事件分发、本模块监听回调），
 * 直到遇到第一个业务帧为止，其后全部保留——业务代码不在这些包名下，不会误伤。
 *
 * 帧格式为「类名.方法名(文件名:行号)」，可区分同一方法内的不同调用点。
 * 摘要取 MD5 是因为调用栈原文长度不定且远超索引字节预算，只能以摘要入表3 唯一索引。
 */
public final class CallStack {

    /** 栈顶需丢弃的取栈入口类（精确匹配：避免误匹配 java.lang.ThreadLocal / ThreadGroup 等） */
    private static final String THREAD_CLASS = "java.lang.Thread";

    /** 栈顶需丢弃的包前缀（织入探针入口、沙箱事件分发链路、本模块监听回调） */
    private static final String[] TRIM_PREFIXES = {
            // sandbox 把织入探针类 Spy 置于带 java. 前缀的包（使其可被 bootstrap 加载、业务类可引用），
            // 因此行探针的直接入口帧类名为 java.com.alibaba.jvm.sandbox.spy.Spy
            "java.com.alibaba.jvm.sandbox.spy.",
            "com.alibaba.jvm.sandbox.",
            "cn.chinaclear.fault.module."
    };

    /** 无行号帧在摘要中的固定标记（与 {@link StackTraceElement} 的 toString 惯例一致） */
    private static final String TOKEN_UNKNOWN_SOURCE = "(Unknown Source)";
    private static final String TOKEN_NATIVE_METHOD = "(Native Method)";

    /**
     * {@link StackTraceElement#getLineNumber()} 的 native 方法约定值（JDK 9 起有同名常量
     * NATIVE_METHOD_LINE；本模块按 release 8 编译，只能用字面量）
     */
    private static final int NATIVE_METHOD_LINE = -2;

    private final String text;
    private final String hash;
    private final int frames;

    private CallStack(String text, String hash, int frames) {
        this.text = text;
        this.hash = hash;
        this.frames = frames;
    }

    /**
     * 抓取当前线程调用栈。每次调用都会触发一次完整栈遍历，
     * 调用方负责控制调用频次（行级回调是热路径）。
     */
    public static CallStack capture() {
        StackTraceElement[] elements = Thread.currentThread().getStackTrace();
        int start = 0;
        while (start < elements.length && isToolFrame(elements[start].getClassName())) {
            start++;
        }
        StringBuilder textSb = new StringBuilder((elements.length - start) * 64 + 64);
        StringBuilder hashSb = new StringBuilder((elements.length - start) * 64 + 64);
        int frames = 0;
        for (int i = start; i < elements.length; i++) {
            if (frames > 0) {
                textSb.append('\n');
                hashSb.append('\n');
            }
            StackTraceElement e = elements[i];
            textSb.append("  ").append(format(e));
            hashSb.append(normalize(e));
            frames++;
        }
        return new CallStack(textSb.toString(), md5Hex32(hashSb.toString()), frames);
    }

    /** 参与判重的帧数 */
    public int frames() {
        return frames;
    }

    /** 调用栈文本：帧间以换行分隔，每帧前缀两个空格；不含末尾换行 */
    public String text() {
        return text;
    }

    /**
     * 调用栈摘要：归一化帧序列的 MD5 32 位小写 hex，表3 唯一键组成部分。
     * 归一化规则见类注释——有行号帧原样、无行号帧取固定标记，
     * 动态代理类的随机命名不影响判重（同一业务路径跨进程重启 hash 稳定）。
     */
    public String hash() {
        return hash;
    }

    private static String format(StackTraceElement e) {
        String file = e.getFileName();
        int line = e.getLineNumber();
        StringBuilder sb = new StringBuilder(64);
        sb.append(e.getClassName()).append('.').append(e.getMethodName()).append('(');
        if (file == null) {
            sb.append("Unknown Source");
        } else if (line < 0) {
            sb.append(file);    // native 等无行号的方法
        } else {
            sb.append(file).append(':').append(line);
        }
        return sb.append(')').toString();
    }

    /**
     * 判重用的归一化帧：有行号（含行号表的真实帧）原样保留；无行号帧类名可能带动态生成的
     * 随机段（CGLIB 增强类），只保留尾部固定标记——标记是 JVM 固定字面量，跨进程重启稳定。
     */
    private static String normalize(StackTraceElement e) {
        int line = e.getLineNumber();
        if (line >= 0) {
            return format(e);
        }
        if (line == NATIVE_METHOD_LINE) {
            return TOKEN_NATIVE_METHOD;
        }
        String file = e.getFileName();
        return file == null ? TOKEN_UNKNOWN_SOURCE : "(" + file + ")";
    }

    private static boolean isToolFrame(String className) {
        if (THREAD_CLASS.equals(className)) {
            return true;
        }
        for (String prefix : TRIM_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String md5Hex32(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return JarHashUtil.toHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }
}
