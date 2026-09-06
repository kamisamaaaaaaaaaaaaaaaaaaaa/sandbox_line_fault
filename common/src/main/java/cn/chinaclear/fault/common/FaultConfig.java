package cn.chinaclear.fault.common;

import cn.chinaclear.fault.common.model.InjectFilter;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 配置加载：classpath 下的 config.yml（YAML，支持嵌套分组）是配置的唯一来源，展平为点分键。
 * -javaagent 的 agentArgs 覆盖写法已不再支持：传入什么都不会生效（见 load）。
 * 「写了键却没值」（冒号后无值、空字符串、列表项留空、空块）一律判定配置非法（见 flatten/get/getList）。
 */
public final class FaultConfig {

    public static final String RESOURCE_NAME = "config.yml";

    private final Properties props = new Properties();

    private FaultConfig() {
    }

    public static FaultConfig load(String agentArgs) {
        // agentArgs（-javaagent:fault-agent.jar=k=v）已不再支持：传入什么都不会生效——
        // 不解析、不覆盖、也不报错，配置的唯一来源是 classpath 下的 config.yml。
        // 该形参是 JVM -javaagent 规范要求的，签名不可省略。
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
        return c;
    }

    /** 嵌套 Map 展平为点分键：jdbc.host=...；List 展平为 key.0/key.1/...（供 getList 读取） */
    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<String, Object> src, Properties out) {
        for (Map.Entry<String, Object> e : src.entrySet()) {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            Object value = e.getValue();
            if (value == null) {
                // 冒号后无值（YAML 解析为 null）：写了键却没值，判定配置非法。
                // 必须在这里拦——若静默跳过，它与「整行不写」在 Properties 里无法区分，
                // 读取阶段就只能取默认值，写了键的意图被静默吞掉。
                throw new IllegalStateException("config \"" + key + "\" is present but has no value"
                        + "（要么填值，要么删除该行）");
            }
            if (value instanceof Map) {
                flatten(key, (Map<String, Object>) value, out);
            } else if (value instanceof List) {
                List<Object> list = (List<Object>) value;
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    if (item == null) {
                        // "- " 后无内容：列表项留空，判定配置非法
                        throw new IllegalStateException("config \"" + key + "\" item " + i
                                + " is empty（要么填值，要么删除该行）");
                    }
                    if (item instanceof Map) {
                        if (((Map<String, Object>) item).isEmpty()) {
                            // "- {}"：写了块但块内无任何字段，判定配置非法
                            throw new IllegalStateException("config \"" + key + "\" item " + i
                                    + " is an empty block（要么填字段，要么删除该块）");
                        }
                        // 列表元素本身是 Map（如 inject.filters 下的每个过滤块）：必须递归展平，
                        // 否则整块会被 String.valueOf 压成一个字符串，块内字段全部读不到
                        flatten(key + "." + i, (Map<String, Object>) item, out);
                    } else {
                        out.setProperty(key + "." + i, String.valueOf(item));
                    }
                }
            } else {
                out.setProperty(key, String.valueOf(value));
            }
        }
    }

    /**
     * 列表配置：YAML 列表形式（key.0、key.1...，对应 yml 里一行一个 "- " 条目）。
     * key.N 不存在 = 该键未配置，返回空集合（合法语义，如不解析任何 lib）；
     * key.N 存在但为空串/纯空白 = 列表项留空，判定配置非法（此前是静默跳过，名单部分失效却无人知晓）。
     */
    public List<String> getList(String key) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (true) {
            String v = props.getProperty(key + "." + i);
            if (v == null) {
                break;
            }
            if (v.trim().isEmpty()) {
                throw new IllegalStateException("config \"" + key + "\" item " + i
                        + " is present but empty（要么填值，要么删除该行）");
            }
            out.add(v.trim());
            i++;
        }
        return out;
    }

    /**
     * 标量读取：键不存在 = 未配置（合法，返回默认值）；
     * 键存在但值为空串/纯空白 = 留空，判定配置非法（抛异常）。
     */
    public String get(String key, String def) {
        String v = props.getProperty(key);
        if (v == null) {
            return def;
        }
        if (v.trim().isEmpty()) {
            throw new IllegalStateException("config \"" + key + "\" is present but empty"
                    + "（要么填值，要么删除该行）");
        }
        return v.trim();
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

    /**
     * 故障库 JDBC 驱动类名（必填）：换数据库时改为目标库驱动（如 org.postgresql.Driver），
     * 加载失败直接硬保护，无内置驱动兜底。完整换库步骤见 README 的"更换数据库"章节。
     */
    public String jdbcDriver() {
        return require("jdbc.driver");
    }

    /**
     * 日志级别阈值：DEBUG / INFO / WARN / ERROR（忽略大小写，返回归一化大写），默认 INFO。
     * 低于阈值的日志（stdout 与文件）都不输出；非法值按配置非法硬保护（与正则非法同一约定）。
     */
    public String logLevel() {
        String v = get("log.level", "INFO").toUpperCase();
        if (!v.equals("DEBUG") && !v.equals("INFO") && !v.equals("WARN") && !v.equals("ERROR")) {
            throw new IllegalStateException("invalid log.level: \"" + v
                    + "\"（允许值：DEBUG / INFO / WARN / ERROR）");
        }
        return v;
    }

    /** BOOT-INF/lib 白名单：正则表达式（对 jar 文件名全串匹配），YAML 列表一行一个（兼容逗号分隔） */
    public List<String> libWhitelist() {
        return getList("lib.whitelist");
    }

    /** 是否挂载故障模块：false = 纯解析模式（只落库不注入故障，应用正常启动） */
    public boolean mountEnabled() {
        return getBoolean("mount.enabled", true);
    }

    /** sandbox 工具安装目录（挂载脚本位于其 bin/sandbox.sh），必填（缺失即硬保护） */
    public String sandboxHome() {
        return require("sandbox.home");
    }

    /** 解析阶段超时（定位后的解析+落库，含异机 pending 等待），默认 15 分钟 */
    public long parseTimeoutMs() {
        return getLong("parse.timeout.ms", 900000L);
    }

    /** 挂载阶段超时（sandbox.sh attach + 模块 inject + watch 注册），默认 20 分钟 */
    public long mountTimeoutMs() {
        return getLong("mount.timeout.ms", 1200000L);
    }

    /** 解析结果分批写库的批大小（流式解析，不驻留全量方法清单），默认 2000 */
    public int parseBatchSize() {
        return getInt("parse.batch.size", 2000);
    }

    /**
     * 是否解析 BOOT-INF/classes（应用自身代码），默认 true。
     * false = 只解析 lib.whitelist 命中的第三方 jar，应用代码既不解析也不注入故障
     * （对应「只对第三方组件做故障演练」的场景）。
     * 若此时白名单也未匹配到任何 jar，本轮解析单元为空 → 硬保护 PARSE。
     */
    public boolean parseClassesEnabled() {
        return getBoolean("parse.classes.enabled", true);
    }

    /**
     * 注入过滤块：按配置顺序串行作用。每个块带一个作用范围（global / class / lib），
     * 范围覆盖的方法才由该块处理，块内先 include 选入（空 = 全选）再 exclude 过滤，
     * 任一块把方法拦下即不注入。未配置 = 全部注入（等价于空 global 块）。
     *
     * 键形态：inject.filters.N.scope / .libs / .include / .exclude（三处名单均为直接列表）。
     * 配置非法（scope 缺失或非三类之一、正则非法）立即抛异常，由调用方上层转硬保护。
     */
    public List<InjectFilter> injectFilters() {
        List<InjectFilter> out = new ArrayList<>();
        if (get("inject.filters", null) != null) {
            // filters 被配成了标量而非块列表：按块列表去读会全部落空、静默当成"未配置"，
            // 名单失效却无人知晓，因此直接判定配置非法
            throw new IllegalStateException("inject.filters must be a YAML list of blocks"
                    + "（每个块形如 \"- scope: global\"）");
        }
        for (int i = 0; ; i++) {
            String prefix = "inject.filters." + i + ".";
            String scope = get(prefix + "scope", null);
            List<String> libs = getList(prefix + "libs");
            List<String> include = getList(prefix + "include");
            List<String> exclude = getList(prefix + "exclude");
            if (scope == null && libs.isEmpty() && include.isEmpty() && exclude.isEmpty()) {
                break;    // 该块不存在，已读完
            }
            out.add(new InjectFilter(scope, libs, include, exclude, i));
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

    /**
     * 每行每线程故障次数：同一行在同一线程下本轮最多发生故障的次数（>=1）。
     * 该行被 T 个线程执行时，本轮最多发生 T × N 次故障。
     * 未配置时默认值 1——即每行每线程本轮只发生一次故障。填入小于 1 的值即硬保护。
     */
    public int faultTimes() {
        int v = getInt("inject.fault.times", 1);
        if (v < 1) {
            throw new IllegalStateException("invalid config: inject.fault.times must be >= 1, actual: "
                    + get("inject.fault.times", ""));
        }
        return v;
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
