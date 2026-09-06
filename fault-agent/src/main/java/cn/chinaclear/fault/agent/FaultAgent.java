package cn.chinaclear.fault.agent;

import java.lang.instrument.Instrumentation;

/**
 * premain 壳：AppClassLoader 加载的唯一入口类（-javaagent 规范要求 premain 类对 system loader 可见）。
 * 职责只有一件事：构造隔离 loader（{@link AgentClassLoader}，从外层 jar 内嵌套的 lib/agent-all.jar
 * 加载全部依赖与业务类）并反射调用 core 入口 {@link AgentBootstrap#run(String)}。
 *
 * 为什么壳要极薄：外层 jar 根上的类对 AppClassLoader（进而对应用类加载器）全部可见，壳类越少、
 * 越不依赖第三方库，同名冲突面越小——因此壳内不做任何业务判断，跨 loader 只传 JDK 类型（String）。
 *
 * 壳阶段失败（loader 初始化/反射调用失败）：core 尚未跑起、config 不可用，无法写表4 或落日志文件，
 * 降级为 System.err 留痕后立即 halt(137)——绝不放行"隔离未建立却继续启动"的进程
 * （与 AgentBootstrap 内 config==null 时的硬保护语义一致）。
 */
public final class FaultAgent {

    private FaultAgent() {
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        try {
            ClassLoader core = new AgentClassLoader();
            Class<?> bootstrap = core.loadClass("cn.chinaclear.fault.agent.AgentBootstrap");
            bootstrap.getMethod("run", String.class).invoke(null, agentArgs);
        } catch (Throwable t) {
            System.err.println("[fault-agent] HARD PROTECT: agent bootstrap failed: " + t);
            t.printStackTrace(System.err);
            Runtime.getRuntime().halt(137);
        }
    }
}
