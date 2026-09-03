package cn.chinaclear.fault.module;

import cn.chinaclear.fault.common.JarHashUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 触发故障时的调用栈快照：取栈 → 裁剪工具自身帧 → 格式化 → 摘要。
 *
 * 文本与摘要由同一次取栈产生，日志打印、入库、判重三处使用同一份内容，
 * 因此可用库里的 stack_text 复算核对 stack_hash。
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

    private final String text;
    private final String hash;
    private final int frames;

    private CallStack(String text, int frames) {
        this.text = text;
        this.frames = frames;
        this.hash = md5Hex32(text);
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
        StringBuilder sb = new StringBuilder((elements.length - start) * 64 + 64);
        int frames = 0;
        for (int i = start; i < elements.length; i++) {
            if (frames > 0) {
                sb.append('\n');
            }
            sb.append("  ").append(format(elements[i]));
            frames++;
        }
        return new CallStack(sb.toString(), frames);
    }

    /** 参与判重的帧数 */
    public int frames() {
        return frames;
    }

    /** 调用栈文本：帧间以换行分隔，每帧前缀两个空格；不含末尾换行 */
    public String text() {
        return text;
    }

    /** 调用栈摘要：{@link #text()} 的 MD5 32 位小写 hex，表3 唯一键组成部分 */
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
