package cn.chinaclear.fault.agent;

import cn.chinaclear.fault.common.FaultLogger;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 临时副本清理守护（"临终托孤"）：
 * 挂载完成后，先【在 JVM 内同步】快照本进程当前打开的 sandbox 模块临时副本清单，
 * 再 spawn 一个脱离会话的后台 shell，把清单作为 argv 传给它——
 * shell 轮询 /proc/<pid>，本进程消亡（kill -9 / 正常退出）后删除清单中的文件并自杀。
 *
 * 为什么需要：sandbox 每次挂载把模块 jar 复制一份到临时目录（File.createTempFile），
 * 卸载/正常退出时会清理，但 kill -9 走不到任何清理路径，副本必然泄漏（每个约 4.8M，
 * 覆盖演练进程反复重启即可写满磁盘）。
 *
 * 设计要点：
 * 1. 快照必须由 JVM 自己完成，不能交给 shell——shell 启动（fork+exec+bash 初始化，约 10~50ms）
 *    与"进程挂载完就很快被注入 kill"存在竞态：若 JVM 先死，shell 读 /proc/<pid>/fd 得到空列表，
 *    清理就被跳过。而快照发生在 premain 内、premain 返回前，注入 kill 不可能早于它——
 *    因此 JVM 内快照 + argv 传递是无竞态的：该删什么在 spawn 那一刻就已固定。
 * 2. 清理精确性：只删快照中的具体路径，不扫描临时目录，不碰任何其他进程的文件。
 * 3. pid 复用免疫：比对 /proc/<pid>/stat 的 starttime（进程启动时刻），
 *    pid 被内核复用给新进程时 starttime 必然变化，立即判定原进程已死。
 * 4. setsid 脱离会话，避免被父进程组连带清理；spawn 失败仅记日志——
 *    清理属卫生措施，与故障注入的覆盖完整性无关，不适用硬保护。
 */
final class TmpReaper {

    /** 副本文件名特征：sandbox 的 ModuleJarClassLoader.copyToTempFile 使用的前缀 */
    private static final String TEMP_JAR_MARK = "sandbox_module_jar_";

    /**
     * 清理脚本：$1=本进程 pid，其后为待清理文件清单（argv）。
     * 脚本本身不再做任何快照——只负责"等 pid 消亡 + 删参数里的文件"。
     */
    private static final String REAPER_SCRIPT =
            "pid=$1\n"
            + "shift\n"
            + "startts=$(awk '{print $22}' /proc/$pid/stat 2>/dev/null)\n"
            + "while :; do\n"
            + "  [ -d /proc/$pid ] || break\n"
            + "  cur=$(awk '{print $22}' /proc/$pid/stat 2>/dev/null)\n"
            + "  [ -z \"$cur\" ] && break\n"
            + "  [ \"$cur\" != \"$startts\" ] && break\n"
            + "  sleep 2\n"
            + "done\n"
            + "for f in \"$@\"; do rm -f \"$f\"; done\n";

    private TmpReaper() {
    }

    /**
     * 挂载完成后调用（成功/失败均应调用）。仅 Linux（依赖 /proc）；
     * 失败仅记日志，属卫生措施不适用硬保护。
     */
    static void spawnAfterMount(long pid) {
        try {
            if (pid <= 0 || !"/".equals(File.separator)) {
                return;
            }
            List<String> tempJars = snapshotSelfTempJars();
            if (tempJars.isEmpty()) {
                FaultLogger.info("tmp reaper skipped: no sandbox module temp copy held by pid=" + pid);
                return;
            }
            List<String> command = new ArrayList<>();
            command.add("setsid");
            command.add("bash");
            command.add("-c");
            command.add(REAPER_SCRIPT);
            command.add("fault-tmp-reaper");
            command.add(String.valueOf(pid));
            command.addAll(tempJars);
            new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(new File("/dev/null"))
                    .start();
            FaultLogger.info("tmp reaper spawned: watching pid=" + pid + ", files=" + tempJars);
        } catch (Throwable t) {
            FaultLogger.error("tmp reaper spawn failed (best effort, cleanup hygiene only)", t);
        }
    }

    /**
     * 从 /proc/self/fd 快照本进程当前持有的 sandbox 模块临时副本（去重、保持顺序）。
     * 挂载完成后调用：副本集合此刻已封闭，且模块 jar 被 URLClassLoader 持续持有句柄，快照不会遗漏。
     */
    private static List<String> snapshotSelfTempJars() {
        Set<String> found = new LinkedHashSet<>();
        File[] fds = new File("/proc/self/fd").listFiles();
        if (fds == null) {
            return new ArrayList<>();
        }
        for (File fd : fds) {
            try {
                Path target = Files.readSymbolicLink(fd.toPath());
                if (target == null) {
                    continue;
                }
                String path = target.toString();
                if (path.contains(TEMP_JAR_MARK) && path.endsWith(".jar")) {
                    found.add(path);
                }
            } catch (Throwable ignore) {
                // fd 可能在枚举过程中瞬时关闭/复用，跳过即可
            }
        }
        return new ArrayList<>(found);
    }
}
