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
 * 轻量日志：写文件 + stdout。
 * 不引入 slf4j/logback：agent fat jar 会被加进目标应用 system classpath，
 * 引入日志门面/实现可能与应用自身依赖冲突。
 */
public final class FaultLogger {

    private static final Object LOCK = new Object();
    private static final SimpleDateFormat TS = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    private static volatile Path logFile;

    private FaultLogger() {
    }

    /** 指定日志文件名（写入工作目录 logs/ 下），agent 与 module 各自初始化一次 */
    public static void init(String fileName) {
        try {
            Path dir = Paths.get("logs");
            Files.createDirectories(dir);
            logFile = dir.toAbsolutePath().resolve(fileName);
        } catch (Throwable t) {
            logFile = null;
        }
    }

    public static void info(String msg) {
        log("INFO", msg, null);
    }

    public static void warn(String msg) {
        log("WARN", msg, null);
    }

    public static void error(String msg, Throwable t) {
        log("ERROR", msg, t);
    }

    private static void log(String level, String msg, Throwable t) {
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
