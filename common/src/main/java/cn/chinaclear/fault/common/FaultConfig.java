package cn.chinaclear.fault.common;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 配置加载：classpath 下 config.yml（YAML，支持嵌套分组）为基线，展平为点分键，
 * agentArgs 键值对覆盖（仅 agent 侧使用，形如 -javaagent:fault-agent.jar=k1=v1,k2=v2）。
 */
public final class FaultConfig {

    public static final String RESOURCE_NAME = "config.yml";

    private final Properties props = new Properties();

    private FaultConfig() {
    }

    public static FaultConfig load(String agentArgs) {
        FaultConfig c = new FaultConfig();
        try (InputStream in = FaultConfig.class.getClassLoader().getResourceAsStream(RESOURCE_NAME)) {
            if (in != null) {
                Yaml yaml = new Yaml();
                Object root = yaml.load(in);
                if (root instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> rootMap = (Map<String, Object>) root;
                    flatten("", rootMap, c.props);
                }
            } else {
                FaultLogger.warn(RESOURCE_NAME + " not found in classpath, use built-in defaults");
            }
        } catch (IOException e) {
            FaultLogger.error("load " + RESOURCE_NAME + " failed", e);
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

    /** 嵌套 Map 展平为点分键：jdbc.host=... */
    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<String, Object> src, Properties out) {
        for (Map.Entry<String, Object> e : src.entrySet()) {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            Object value = e.getValue();
            if (value instanceof Map) {
                flatten(key, (Map<String, Object>) value, out);
            } else if (value != null) {
                out.setProperty(key, String.valueOf(value));
            }
        }
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

    /** 故障库连接（必填） */
    public String jdbcUrl() {
        return require("jdbc.url");
    }

    public String jdbcUsername() {
        return require("jdbc.username");
    }

    public String jdbcPassword() {
        return require("jdbc.password");
    }

    /** BOOT-INF/lib 白名单：正则表达式（对 jar 文件名全串匹配），逗号分隔 */
    public List<String> libWhitelist() {
        List<String> out = new ArrayList<>();
        for (String s : get("lib.whitelist", "").split(",")) {
            if (!s.trim().isEmpty()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    /** 是否挂载故障模块：false = 纯解析模式（只落库不注入故障，应用正常启动） */
    public boolean mountEnabled() {
        return getBoolean("mount.enabled", true);
    }

    /** sandbox 工具安装目录（挂载脚本位于其 bin/sandbox.sh） */
    public String sandboxHome() {
        return get("sandbox.home", "/home/lys2/sandbox");
    }

    /** 解析阶段超时（定位后的解析+落库，含异机 pending 等待），默认 15 分钟 */
    public long parseTimeoutMs() {
        return getLong("parse.timeout.ms", 900000L);
    }

    /** 挂载阶段超时（sandbox.sh attach + 模块 inject + watch 注册），默认 20 分钟 */
    public long mountTimeoutMs() {
        return getLong("mount.timeout.ms", 1200000L);
    }

    /** 异机 pending 孤儿判定阈值（分钟） */
    public int orphanThresholdMinutes() {
        return getInt("orphan.threshold.minutes", 10);
    }

    /**
     * 注入排除：方法正则，对 "完全限定类名.方法名" 全串匹配，逗号分隔，命中的方法不注入。
     * 排除某个类的所有方法写 "全限定类名\..*"，例如 cn\.demo\.OrderService\..*
     */
    public List<String> excludeMethods() {
        return splitList("exclude.methods");
    }

    private List<String> splitList(String key) {
        List<String> out = new ArrayList<>();
        for (String s : get(key, "").split(",")) {
            if (!s.trim().isEmpty()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    /** 日志目录（相对路径基于目标进程工作目录） */
    public String logDir() {
        return get("log.dir", "logs");
    }

    /** 模块侧分批读取方法清单的批大小（防大项目一次性读入打爆内存） */
    public int injectBatchSize() {
        return getInt("inject.batch.size", 5000);
    }

    /** 必填配置缺失即抛错（走硬保护，避免带错误配置运行） */
    private String require(String key) {
        String v = get(key, "");
        if (v.isEmpty()) {
            throw new IllegalStateException("required config missing: " + key);
        }
        return v;
    }
}
