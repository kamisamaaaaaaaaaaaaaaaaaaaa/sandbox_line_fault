package cn.chinaclear.fault.agent;

import cn.chinaclear.fault.common.FaultConfig;
import cn.chinaclear.fault.common.FaultLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 同步执行 sandbox.sh 挂载模块并阻塞等待；超时/失败抛硬保护 */
final class SandboxMountInvoker {

    private static final int MAX_OUTPUT = 64000;

    private SandboxMountInvoker() {
    }

    /** bash sandbox.sh -p <pid> -d "fault-module/inject?id=1,2,3"（必须以 sandbox/bin 为工作目录：SANDBOX_HOME_DIR=${PWD}/..） */
    static void mountSync(FaultConfig config, long pid, List<Long> unitIds, String bootJarPath, long deadline) {
        long remain = deadline - System.currentTimeMillis();
        if (remain <= 0) {
            throw HardProtectException.timeout("MOUNT", "no time left for mount", null, null, bootJarPath);
        }
        String home = config.sandboxHome();
        String script = home + "/bin/sandbox.sh";
        String ids = FaultAgent.joinIds(unitIds);
        List<String> command = Arrays.asList(
                "bash", script,
                "-p", String.valueOf(pid),
                "-d", "fault-module/inject?id=" + ids);
        FaultLogger.info("mount cmd: " + command);

        java.io.File workDir = new java.io.File(home, "bin");
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .directory(workDir)
                    .redirectErrorStream(true)
                    .start();
            final Process running = process;
            final StringBuilder out = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(running.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        synchronized (out) {
                            if (out.length() < MAX_OUTPUT) {
                                out.append(line).append('\n');
                            }
                        }
                    }
                } catch (IOException ignore) {
                    // process ended
                }
            });
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(remain, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                // 解析已成功（completed），挂载失败不动表1 状态，只写表4 + kill
                throw HardProtectException.timeout("MOUNT", "sandbox.sh wait timeout: " + command,
                        outputOf(out), null, bootJarPath);
            }
            int code = process.exitValue();
            FaultLogger.info("sandbox.sh exit=" + code + " output:\n" + outputOf(out));
            if (code != 0) {
                throw HardProtectException.exception("MOUNT", "sandbox.sh exit code=" + code,
                        outputOf(out), null, bootJarPath);
            }
        } catch (IOException e) {
            throw HardProtectException.exception("MOUNT", "sandbox.sh exec failed: " + e.getMessage(),
                    FaultAgent.stackOf(e), null, bootJarPath);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw HardProtectException.exception("MOUNT", "sandbox.sh wait interrupted",
                    FaultAgent.stackOf(e), null, bootJarPath);
        }
    }

    private static String outputOf(StringBuilder out) {
        synchronized (out) {
            return out.toString();
        }
    }
}
