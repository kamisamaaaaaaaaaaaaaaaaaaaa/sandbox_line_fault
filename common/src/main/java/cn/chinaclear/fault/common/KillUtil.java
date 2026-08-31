package cn.chinaclear.fault.common;

import java.util.concurrent.TimeUnit;

/**
 * 强杀当前进程：SIGKILL（kill -9，仅 Linux 部署环境）。
 * 返回 true  = SIGKILL 已生效，进程终止（本方法不会正常返回）；
 * 返回 false = 进程仍存活（kill 命令未生效），调用方需做善后（如回滚记录）并自行 halt 兜底。
 */
public final class KillUtil {

    private KillUtil() {
    }

    public static boolean killCurrentProcess(long pid) {
        FaultLogger.error("KILL current process, pid=" + pid);
        Integer code = tryRun(new String[]{"kill", "-9", String.valueOf(pid)});
        if (code != null && code == 0) {
            // SIGKILL 已发出：进程终止，本方法不会返回；短暂等待以覆盖命令生效延迟
            try {
                TimeUnit.MILLISECONDS.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // 能走到这里说明进程仍存活
            FaultLogger.error("kill -9 issued but process still alive, pid=" + pid);
            return false;
        }
        FaultLogger.error("kill -9 not effective (exit=" + code + "), pid=" + pid);
        return false;
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
