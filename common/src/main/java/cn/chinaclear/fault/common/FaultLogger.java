package cn.chinaclear.fault.common;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 轻量日志：写文件 + stdout，按级别阈值过滤（低于阈值的 stdout 与文件都不输出）。
 * 不引入 slf4j/logback：agent fat jar 会被加进目标应用 system classpath，
 * 引入日志门面/实现可能与应用自身依赖冲突。
 *
 * 级别序数 DEBUG(0) < INFO(1) < WARN(2) < ERROR(3)；阈值由配置 log.level 决定（init 传入，默认 INFO），
 * init 之前（或 init 未带级别）取 INFO 兜底——保证配置读出来之前的日志不会误吞。
 */
public final class FaultLogger {

    private static final Object LOCK = new Object();
    private static final SimpleDateFormat TS = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    private static volatile Path logFile;

    private static final int ORDER_DEBUG = 0;
    private static final int ORDER_INFO = 1;
    private static final int ORDER_WARN = 2;
    private static final int ORDER_ERROR = 3;
    /** 级别阈值（序数）：低于它的日志不输出。volatile 单写多读；声明在常量之后，避免前向引用 */
    private static volatile int threshold = ORDER_INFO;

    private FaultLogger() {
    }

    /** 指定日志目录与文件名（目录不存在自动创建；不可写时降级为仅 stdout），agent 与 module 各自初始化一次 */
    public static void init(String dirName, String fileName) {
        init(dirName, fileName, "INFO");
    }

    /**
     * 同上，并设置级别阈值（DEBUG / INFO / WARN / ERROR，忽略大小写，默认 INFO）。
     * 非法值由 FaultConfig.logLevel() 校验（配置非法硬保护），此处兜底取 INFO，不自行抛错。
     */
    public static void init(String dirName, String fileName, String level) {
        threshold = levelOf(level);
        try {
            Path dir = Paths.get(dirName);
            Files.createDirectories(dir);
            logFile = dir.toAbsolutePath().resolve(fileName);
            log("INFO", "log file: " + logFile + " (level=" + level + ")", null);
        } catch (Throwable t) {
            logFile = null;
            log("WARN", "log dir not writable (" + dirName + "), fallback to stdout only: " + t.getMessage(), null);
        }
    }

    public static void debug(String msg) {
        log("DEBUG", msg, null);
    }

    public static void info(String msg) {
        log("INFO", msg, null);
    }

    public static void warn(String msg) {
        log("WARN", msg, null);
    }

    public static void error(String msg) {
        log("ERROR", msg, null);
    }

    public static void error(String msg, Throwable t) {
        log("ERROR", msg, t);
    }

    private static int levelOf(String level) {
        if ("DEBUG".equalsIgnoreCase(level)) {
            return ORDER_DEBUG;
        }
        if ("WARN".equalsIgnoreCase(level)) {
            return ORDER_WARN;
        }
        if ("ERROR".equalsIgnoreCase(level)) {
            return ORDER_ERROR;
        }
        // INFO 与未知值（正常情况下被配置校验拦截）一律取 INFO，兜底安全
        return ORDER_INFO;
    }

    private static int orderOf(String level) {
        switch (level) {
            case "DEBUG":
                return ORDER_DEBUG;
            case "WARN":
                return ORDER_WARN;
            case "ERROR":
                return ORDER_ERROR;
            default:
                return ORDER_INFO;
        }
    }

    private static void log(String level, String msg, Throwable t) {
        // 阈值过滤放在格式化之前：低于阈值的 stdout 与文件都不输出
        if (orderOf(level) < threshold) {
            return;
        }
        StringBuilder sb = new StringBuilder(128);
        synchronized (TS) {
            sb.append(TS.format(new Date()));
        }
        sb.append(' ').append(level).append(" [").append(Thread.currentThread().getName()).append("] ").append(msg);
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            sb.append('\n').append(sw);
        }
        String line = sb.toString();
        synchronized (LOCK) {
            System.out.println(line);
            Path file = logFile;
            if (file != null) {
                try (OutputStream out = new FileOutputStream(file.toFile(), true)) {
                    out.write(line.getBytes("UTF-8"));
                    out.write('\n');
                } catch (IOException ignore) {
                    // 日志失败不影响主流程
                }
            }
        }
    }
}
