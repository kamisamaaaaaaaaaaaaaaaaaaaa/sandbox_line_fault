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
                // 资源缺失 = 配置不可用（无库地址/账号），必须硬保护，不做"内置默认值"静默降级
                throw new IllegalStateException(RESOURCE_NAME + " not found in classpath");
            }
        } catch (IOException e) {
            throw new IllegalStateException("load " + RESOURCE_NAME + " failed: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // YAML 语法错误等（如双引号内写单个 \.）：配置非法，同样硬保护
            throw new IllegalStateException("invalid " + RESOURCE_NAME + ": " + e.getMessage(), e);
        }
        if (agentArgs != null && !agentArgs.trim().isEmpty()) {
            for (String pair : agentArgs.split(",")) {
                pair = pair.trim();
                int i = pair.indexOf('=');
                if (i > 0) {
                    String k = pair.substring(0, i).trim();
                    String value = pair.substring(i + 1).trim();
                    // 列表键覆盖：清除原条目（key.0/key.1/...）后把覆盖值写入首条目，保证整体替换语义
                    boolean wasList = false;
                    for (int j = 0; c.props.containsKey(k + "." + j); j++) {
                        c.props.remove(k + "." + j);
                        wasList = true;
                    }
                    if (wasList) {
                        c.props.setProperty(k + ".0", value);
                    } else {
                        c.props.setProperty(k, value);
                    }
                }
            }
        }
        return c;
    }

    /** 嵌套 Map 展平为点分键：jdbc.host=...；List 展平为 key.0/key.1/...（供 getList 读取） */
    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<String, Object> src, Properties out) {
        for (Map.Entry<String, Object> e : src.entrySet()) {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            Object value = e.getValue();
            if (value instanceof Map) {
                flatten(key, (Map<String, Object>) value, out);
            } else if (value instanceof List) {
                List<Object> list = (List<Object>) value;
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    if (item != null) {
                        out.setProperty(key + "." + i, String.valueOf(item));
                    }
                }
            } else if (value != null) {
                out.setProperty(key, String.valueOf(value));
            }
        }
    }

    /**
     * 列表配置：只支持 YAML 列表形式（key.0、key.1...，对应 yml 里一行一个 "- " 条目）；
     * 未配置该参数 = 空列表（不解析任何 lib / 不排除任何方法）。
     */
    public List<String> getList(String key) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (true) {
            String v = props.getProperty(key + "." + i);
            if (v == null) {
                break;
            }
            if (!v.trim().isEmpty()) {
                out.add(v.trim());
            }
            i++;
        }
        return out;
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

    /** BOOT-INF/lib 白名单：正则表达式（对 jar 文件名全串匹配），YAML 列表一行一个（兼容逗号分隔） */
    public List<String> libWhitelist() {
        return getList("lib.whitelist");
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
     * 注入排除：方法正则，对 "完全限定类名.方法名" 全串匹配，YAML 列表一行一个（兼容逗号分隔），命中的方法不注入。
     * 排除某个类的所有方法写 "全限定类名\..*"，例如 cn\.demo\.OrderService\..*
     */
    public List<String> excludeMethods() {
        return getList("exclude.methods");
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
