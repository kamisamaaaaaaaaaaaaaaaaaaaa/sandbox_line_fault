package cn.chinaclear.fault.agent;

import cn.chinaclear.fault.common.FaultConfig;
import cn.chinaclear.fault.common.FaultLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 同步执行 sandbox.sh 挂载模块并阻塞等待；超时/失败抛硬保护 */
final class SandboxMountInvoker {

    private static final int MAX_OUTPUT = 64000;

    /** sandbox.sh 内嵌 Jetty 返回错误页时的固定标识（HTTP 5xx 但脚本仍 exit 0） */
    private static final String SERVER_ERROR_MARKER = "Problem accessing";

    /**
     * 同机多进程 attach 的串行化锁。
     * 原因：sandbox.sh 用 token="$(date | head | cksum)" 生成路由 token（精度到秒），attach 后把
     * "namespace;token;ip;port" 追加进 ${HOME}/.sandbox.token，client 再 grep token | tail -1 取端口。
     * 并发时两个进程可能生成相同 token，且对方的 append 可能插在自己 append 与 grep 之间——
     * 此时 tail -1 取到的是别人的行，命令被发到错误 JVM，表现为 watch 静默丢失（命令返回成功
     * 但目标进程永远不被注入）。
     * 串行化后，自己 append 的行必然是 grep 时刻的最后一行，tail -1 必定命中自己——
     * 即使两次 attach 落在同一秒、token 完全相同也无妨，无需额外做跨秒间隔。
     * 锁文件置于 user.home 下：token 文件同样在 user.home 下，冲突只可能发生在同用户进程之间，
     * 同时避免多用户共享 /tmp 带来的权限问题。
     */
    private static final String ATTACH_LOCK =
            System.getProperty("user.home", "/tmp") + "/.fault-sandbox-attach.lock";

    private SandboxMountInvoker() {
    }

    /**
     * flock -w <等待秒> <锁文件> bash <sandbox.sh> -p <pid> -d <模块参数>
     * —— 整段 attach+inject 在锁内串行执行（同一时刻仅一个进程 attach）。
     * 工作目录必须是 sandbox/bin（SANDBOX_HOME_DIR=${PWD}/..）。
     * 依赖 util-linux 的 flock；缺失时 flock 非 0 退出 → 硬保护 MOUNT（不放行）。
     */
    static void mountSync(FaultConfig config, long pid, List<Long> unitIds, String bootJarPath, long deadline) {
        long remain = deadline - System.currentTimeMillis();
        if (remain <= 0) {
            throw HardProtectException.timeout("MOUNT", "no time left for mount", null, null, bootJarPath);
        }
        String home = config.sandboxHome();
        String script = home + "/bin/sandbox.sh";
        String ids = AgentBootstrap.joinIds(unitIds);
        String bootJarEncoded;
        try {
            bootJarEncoded = java.net.URLEncoder.encode(bootJarPath, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw HardProtectException.exception("MOUNT", "encode bootJar failed", null, null, bootJarPath);
        }
        int waitSec = (int) Math.max(1, remain / 1000);
        List<String> command = Arrays.asList(
                "flock", "-w", String.valueOf(waitSec), ATTACH_LOCK,
                "bash", script,
                "-p", String.valueOf(pid),
                "-d", "fault-module/inject?id=" + ids + "&bootJar=" + bootJarEncoded);
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
                throw HardProtectException.timeout("MOUNT", "mount wait timeout" +
                        "（含 flock 串行化锁等待）: " + command, outputOf(out), null, bootJarPath);
            }
            int code = process.exitValue();
            String output = outputOf(out);
            FaultLogger.info("sandbox.sh exit=" + code + " output:\n" + output);
            if (code != 0) {
                throw HardProtectException.exception("MOUNT",
                        "mount failed（flock -w " + waitSec + "s 等待串行化锁超时/脚本非 0 退出）exit code=" + code
                                + " lock=" + ATTACH_LOCK,
                        output, null, bootJarPath);
            }
            // sandbox.sh 内部用 curl -N -s 发命令，不校验 HTTP 状态码：模块命令返回 4xx/5xx 时脚本仍 exit 0。
            // 典型场景：模块 jar 在 /tmp 的副本被清理 → 模块报 config.yml not found → inject 实际失败，
            // 但进程"挂载成功"地继续运行、watch 根本没注册（永不注入，且无任何报错）。
            // 这属于"挂了却没覆盖"的绝不放行场景，必须硬保护。
            if (output.contains(SERVER_ERROR_MARKER)) {
                throw HardProtectException.exception("MOUNT",
                        "mount reported exit 0 but sandbox server returned an error page（watch 未注册，"
                                + "典型原因：模块 jar 的 /tmp 副本被清理）",
                        output, null, bootJarPath);
            }
            // 观测性警告：同一版本 sandbox 下，成功挂载的 stdout 恒为空（core 日志走 logback 文件、
            // inject 响应体为空）。输出非空且无错误页 = sandbox 行为发生变化（如环境变量注入的 JVM
            // 警告、版本升级后的输出变化）——不构成失败（无错误页锚点），不改变保护语义，仅提示人工
            // 关注上方记录的完整输出
            if (!output.isEmpty()) {
                FaultLogger.warn("sandbox.sh exit=0 with unexpected non-empty output (no error page marker)"
                        + " —— sandbox behavior may have changed, inspect the output logged above");
            }
        } catch (IOException e) {
            throw HardProtectException.exception("MOUNT", "sandbox.sh exec failed: " + e.getMessage(),
                    AgentBootstrap.stackOf(e), null, bootJarPath);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw HardProtectException.exception("MOUNT", "sandbox.sh wait interrupted",
                    AgentBootstrap.stackOf(e), null, bootJarPath);
        }
    }

    private static String outputOf(StringBuilder out) {
        synchronized (out) {
            return out.toString();
        }
    }
}
