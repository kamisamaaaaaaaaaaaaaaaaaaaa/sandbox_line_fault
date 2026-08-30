package cn.chinaclear.fault.common;

import java.util.concurrent.TimeUnit;

/** 强杀当前进程：Linux kill -9 优先，失败依次兜底 taskkill（Windows）/ Runtime.halt */
public final class KillUtil {

    private KillUtil() {
    }

    public static void killCurrentProcess(long pid) {
        FaultLogger.error("KILL current process, pid=" + pid, null);
        try {
            Process p = new ProcessBuilder("kill", "-9", String.valueOf(pid)).start();
            p.waitFor(10, TimeUnit.SECONDS);
            if (p.exitValue() == 0) {
                return;
            }
        } catch (Throwable ignore) {
            // fallback below
        }
        try {
            Process p = new ProcessBuilder("taskkill", "/F", "/PID", String.valueOf(pid)).start();
            p.waitFor(10, TimeUnit.SECONDS);
            if (p.exitValue() == 0) {
                return;
            }
        } catch (Throwable ignore) {
            // fallback below
        }
        // 最后兜底：不可恢复地退出 JVM（exit code 137 语义对齐 SIGKILL）
        Runtime.getRuntime().halt(137);
    }
}
