package cn.chinaclear.fault.agent;

import cn.chinaclear.fault.common.FaultConfig;
import cn.chinaclear.fault.common.FaultLogger;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * bootJar 定位兜底链：
 * 1) JVM 启动参数 -jar xxx.jar（相对路径按 user.dir 归一）；
 * 2) agentArgs / config.properties 的 bootJar.path。
 */
final class BootJarLocator {

    private BootJarLocator() {
    }

    /** 返回 bootJar 绝对路径；定位失败返回 null（调用方走硬保护） */
    static String locate(FaultConfig config) {
        try {
            List<String> args = ManagementFactory.getRuntimeMXBean().getInputArguments();
            for (int i = 0; i < args.size(); i++) {
                String a = args.get(i).trim();
                if (a.equals("-jar") && i + 1 < args.size()) {
                    return absolutizeExisting(args.get(i + 1).trim());
                }
                if (a.startsWith("-jar=")) {
                    return absolutizeExisting(a.substring(5).trim());
                }
            }
        } catch (Throwable t) {
            FaultLogger.warn("read jvm input arguments failed: " + t.getMessage());
        }
        String fallback = config.bootJarPath();
        if (!fallback.isEmpty()) {
            return absolutizeExisting(fallback);
        }
        return null;
    }

    private static String absolutizeExisting(String path) {
        Path p = Paths.get(path);
        if (!p.isAbsolute()) {
            p = Paths.get(System.getProperty("user.dir"), path);
        }
        p = p.toAbsolutePath().normalize();
        if (Files.exists(p)) {
            FaultLogger.info("bootJar located: " + p);
            return p.toString();
        }
        FaultLogger.warn("bootJar path not exists: " + p);
        return null;
    }
}
