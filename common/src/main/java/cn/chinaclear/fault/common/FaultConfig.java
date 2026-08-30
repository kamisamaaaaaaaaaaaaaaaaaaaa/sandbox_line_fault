package cn.chinaclear.fault.common;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 配置加载：classpath 下 config.properties 为基线，agentArgs 键值对覆盖（仅 agent 侧使用）。
 * agentArgs 形如：-javaagent:fault-agent.jar=k1=v1,k2=v2
 */
public final class FaultConfig {

    public static final String RESOURCE_NAME = "config.properties";

    private final Properties props = new Properties();

    private FaultConfig() {
    }

    public static FaultConfig load(String agentArgs) {
        FaultConfig c = new FaultConfig();
        try (InputStream in = FaultConfig.class.getClassLoader().getResourceAsStream(RESOURCE_NAME)) {
            if (in != null) {
                c.props.load(in);
            } else {
                FaultLogger.warn("config.properties not found in classpath, use built-in defaults");
            }
        } catch (IOException e) {
            FaultLogger.error("load config.properties failed", e);
        }
        if (agentArgs != null && !agentArgs.trim().isEmpty()) {
            for (String pair : agentArgs.split(",")) {
                pair = pair.trim();
                int i = pair.indexOf('=');
                if (i > 0) {
                    c.props.setProperty(pair.substring(0, i).trim(), pair.substring(i + 1).trim());
                }
            }
        }
        return c;
    }

    public String get(String key, String def) {
        String v = props.getProperty(key);
        return v == null || v.trim().isEmpty() ? def : v.trim();
    }

    public boolean getBoolean(String key, boolean def) {
        return Boolean.parseBoolean(get(key, String.valueOf(def)));
    }

    public long getLong(String key, long def) {
        try {
            return Long.parseLong(get(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public int getInt(String key, int def) {
        try {
            return Integer.parseInt(get(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean enabled() {
        return getBoolean("agent.enabled", true);
    }

    public String jdbcUrl() {
        return get("jdbc.url",
                "jdbc:mysql://127.0.0.1:3306/fault_sandbox?useUnicode=true&characterEncoding=utf8"
                        + "&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
                        + "&connectTimeout=5000&socketTimeout=10000");
    }

    public String jdbcUsername() {
        return get("jdbc.username", "root");
    }

    public String jdbcPassword() {
        return get("jdbc.password", "root123");
    }

    /** BOOT-INF/lib 白名单：jar 文件名（精确或前缀匹配），逗号分隔 */
    public List<String> libWhitelist() {
        List<String> out = new ArrayList<>();
        for (String s : get("lib.whitelist", "").split(",")) {
            if (!s.trim().isEmpty()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    public String sandboxShPath() {
        return get("sandbox.sh.path", "/home/lys2/sandbox/bin/sandbox.sh");
    }

    /** premain 全程总预算：解析 + 落库 + 挂载 */
    public long premainTimeoutMs() {
        return getLong("premain.timeout.ms", 600000L);
    }

    /** 非 -jar 方式启动时的 bootJar 兜底路径 */
    public String bootJarPath() {
        return get("bootJar.path", "");
    }

    /** 异机 pending 孤儿判定阈值（分钟） */
    public int orphanThresholdMinutes() {
        return getInt("orphan.threshold.minutes", 10);
    }
}
