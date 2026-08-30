package cn.chinaclear.fault.agent;

import cn.chinaclear.fault.common.FaultConfig;
import cn.chinaclear.fault.common.FaultLogger;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * bootJar 定位兜底链（应用必须以 -jar 方式启动）：
 * 1) sun.java.command 首个 token（JDK9+ 的 -jar 旗标不出现在 inputArguments 中）；
 * 2) /proc/self/cmdline（Linux，null 分隔的完整命令行，找 -jar 后继参数）；
 * 3) RuntimeMXBean inputArguments（JDK 8 行为：包含 -jar 及路径）。
 */
final class BootJarLocator {

    private BootJarLocator() {
    }

    /** 返回 bootJar 绝对路径；定位失败返回 null（调用方走硬保护） */
    static String locate() {
        // 1) sun.java.command
        try {
            String command = System.getProperty("sun.java.command");
            if (command != null && !command.trim().isEmpty()) {
                String first = command.trim().split("\\s+")[0];
                if (first.endsWith(".jar")) {
                    String found = absolutizeExisting(first);
                    if (found != null) {
                        return found;
                    }
                }
            }
        } catch (Throwable t) {
            FaultLogger.warn("read sun.java.command failed: " + t.getMessage());
        }

        // 2) /proc/self/cmdline（Linux）
        try {
            Path proc = Paths.get("/proc/self/cmdline");
            if (Files.exists(proc)) {
                List<String> tokens = new ArrayList<>();
                for (String t : new String(Files.readAllBytes(proc), "UTF-8").split("\0")) {
                    if (!t.isEmpty()) {
                        tokens.add(t.trim());
                    }
                }
                for (int i = 0; i < tokens.size(); i++) {
                    if (tokens.get(i).equals("-jar") && i + 1 < tokens.size()) {
                        String found = absolutizeExisting(tokens.get(i + 1));
                        if (found != null) {
                            return found;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            FaultLogger.warn("read /proc/self/cmdline failed: " + t.getMessage());
        }

        // 3) inputArguments（JDK 8 包含 -jar；JDK9+ 不含，跳过）
        try {
            List<String> args = ManagementFactory.getRuntimeMXBean().getInputArguments();
            for (int i = 0; i < args.size(); i++) {
                String a = args.get(i).trim();
                if (a.equals("-jar") && i + 1 < args.size()) {
                    String found = absolutizeExisting(args.get(i + 1).trim());
                    if (found != null) {
                        return found;
                    }
                }
                if (a.startsWith("-jar=")) {
                    String found = absolutizeExisting(a.substring(5).trim());
                    if (found != null) {
                        return found;
                    }
                }
            }
        } catch (Throwable t) {
            FaultLogger.warn("read jvm input arguments failed: " + t.getMessage());
        }

        FaultLogger.warn("bootJar not located: application must be started with -jar");
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
