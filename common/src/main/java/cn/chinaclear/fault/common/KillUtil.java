package cn.chinaclear.fault.common;

import java.util.concurrent.TimeUnit;

/** 强杀当前进程：Linux kill -9 优先，失败依次兜底 taskkill（Windows）/ Runtime.halt；每个分支均有日志 */
public final class KillUtil {

    private KillUtil() {
    }

    public static void killCurrentProcess(long pid) {
        FaultLogger.error("KILL current process, pid=" + pid);

        Integer code = tryRun(new String[]{"kill", "-9", String.valueOf(pid)});
        if (code != null && code == 0) {
            FaultLogger.error("kill -9 issued (exit=0), pid=" + pid);
            return;
        }
        FaultLogger.error("kill -9 not effective (exit=" + code + "), fallback to taskkill, pid=" + pid);

        code = tryRun(new String[]{"taskkill", "/F", "/PID", String.valueOf(pid)});
        if (code != null && code == 0) {
            FaultLogger.error("taskkill issued (exit=0), pid=" + pid);
            return;
        }
        FaultLogger.error("taskkill not effective (exit=" + code + "), fallback to halt(137), pid=" + pid);

        // 最后兜底：不可恢复地退出 JVM（exit code 137 语义对齐 SIGKILL）
        Runtime.getRuntime().halt(137);
    }

    /** 返回进程 exit code；启动失败/超时/中断返回 null */
    private static Integer tryRun(String[] command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            p.waitFor(10, TimeUnit.SECONDS);
            return p.exitValue();
        } catch (Throwable t) {
            FaultLogger.error("kill command exec failed: " + String.join(" ", command) + " - " + t.getMessage(), t);
            return null;
        }
    }
}
