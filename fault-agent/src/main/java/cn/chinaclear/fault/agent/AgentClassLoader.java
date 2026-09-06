package cn.chinaclear.fault.agent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;

/**
 * 隔离 ClassLoader：把外层 jar 内嵌套的 {@link #NESTED_JAR_PATH}（lib/agent-all.jar）全量读入内存后
 * 独立加载。AppClassLoader 看到的外层 jar 根上只有壳类（FaultAgent / 本类），全部依赖与业务类都在
 * 嵌套 jar 这个 entry 里——jar 套 jar 不是标准 classpath 形态，AppClassLoader 不递归进嵌套 entry，
 * 因此嵌套内的类对应用侧完全不可见，本 loader 是唯一读取者（隔离的物理基础）。
 *
 * 为什么 parent 取 platform/ext loader 而不是 AppClassLoader：
 * -javaagent 会把外层 jar 加入 AppClassLoader 的搜索路径，而 -jar 启动时应用自己的 bootJar 也在
 * AppClassLoader 的搜索路径上。若 parent=AppClassLoader，本 loader 解析依赖时第一站就是应用
 * classpath，隔离的成立与否就押在"应用恰好没有同名类"的偶然条件上。取 system loader 的父加载器
 * （JDK9+ platform / JDK8 ext）后，委派链为：嵌套 jar 自己 → platform/ext → bootstrap——三层都
 * 只有 JDK 模块类，应用 classpath 完全不参与 agent 依赖的解析。（不能取 null：JDK9+ 的
 * java.sql / java.management 等非 base 模块类由 platform loader 加载，parent=null 拿不到。）
 *
 * 内存驻留换取零临时文件：嵌套 jar 全量（约数 MB，含 mysql/asm/snakeyaml 等）读入内存，
 * 不落临时文件、不需要清理钩子（解压临时文件的方案要处理 agent kill -9 时的清理路径，不取）。
 *
 * 委派采用默认的 parent-first：platform/ext 里只有 JDK 模块类，与嵌套 jar 的类名零交集，
 * parent-first 即语义正确（JDK 类本就只能由 platform 定义）。约束：嵌套 core 不允许引入
 * 与 JDK 模块同包名的类库——那会被 platform 抢先命中，隔离与行为都会出错。
 */
public final class AgentClassLoader extends ClassLoader {

    /** 嵌套 core jar 在外层 jar 内的 entry 路径（由 fault-agent/build.gradle 的组装 task 写入） */
    public static final String NESTED_JAR_PATH = "lib/agent-all.jar";

    /** 嵌套 jar 内 entry name -> 内容字节 */
    private final Map<String, byte[]> entries;
    /** 外层 jar 的 URL（资源 URL 构造的公共前缀） */
    private final URL outerJarUrl;
    /** defineClass 用的 CodeSource：指向外层 jar!/lib/agent-all.jar，日志排查时可打印类来源 */
    private final CodeSource codeSource;

    public AgentClassLoader() {
        // parent 取 system loader 的父加载器：JDK9+ 为 platform loader，JDK8 为 ext loader。
        // 两者只装 JDK 模块类（java.base 之外的 java.sql / java.management 等也在其中），
        // 不含任何应用或 agent 的类——应用 classpath 挂在 system（App）loader 上，被整体跳过。
        // 不能直接 parent=null：那只会委派到 bootstrap，JDK9+ 的非 base 模块类
        // （如 java.sql.SQLException）拿不到，core 内一引用就 NoClassDefFoundError。
        super(ClassLoader.getSystemClassLoader().getParent());
        this.outerJarUrl = locateOuterJar();
        this.entries = readNestedEntries(outerJarUrl);
        this.codeSource = new CodeSource(nestedUrl(""), (Certificate[]) null);
        System.out.println("[fault-agent] isolated classloader ready: " + entries.size()
                + " entries from " + outerJarUrl + "!/" + NESTED_JAR_PATH);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytes = entries.get(classEntryName(name));
        if (bytes == null) {
            throw new ClassNotFoundException(name + " (not in " + NESTED_JAR_PATH + ")");
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            try {
                if (getPackage(name.substring(0, dot)) == null) {
                    definePackage(name.substring(0, dot), null, null, null, null, null, null, null);
                }
            } catch (IllegalArgumentException ignored) {
                // 并发 definePackage 竞争：包已定义即达目的
            }
        }
        return defineClass(name, bytes, 0, bytes.length, new ProtectionDomain(codeSource, null));
    }

    @Override
    protected URL findResource(String name) {
        return entries.containsKey(name) ? nestedUrl(name) : null;
    }

    @Override
    protected Enumeration<URL> findResources(String name) {
        return entries.containsKey(name)
                ? Collections.enumeration(Collections.singletonList(nestedUrl(name)))
                : Collections.<URL>emptyEnumeration();
    }

    private static String classEntryName(String className) {
        return className.replace('.', '/').concat(".class");
    }

    /** 定位外层 jar：本类由 AppClassLoader 从外层 jar 加载，codeSource 即其文件位置 */
    private static URL locateOuterJar() {
        CodeSource cs = AgentClassLoader.class.getProtectionDomain().getCodeSource();
        URL location = cs == null ? null : cs.getLocation();
        if (location == null || !"file".equals(location.getProtocol())) {
            throw new IllegalStateException("cannot locate agent jar (code source: " + location + ")");
        }
        return location;
    }

    /** 把嵌套 jar entry 全量读入内存，并展开为 entry 名 -> 字节的 Map */
    private static Map<String, byte[]> readNestedEntries(URL outerLocation) {
        Map<String, byte[]> map = new HashMap<String, byte[]>();
        try {
            File outerFile = new File(URI.create(outerLocation.toString()));
            try (JarFile outerJar = new JarFile(outerFile)) {
                JarEntry nested = outerJar.getJarEntry(NESTED_JAR_PATH);
                if (nested == null) {
                    throw new IllegalStateException("nested core jar missing in agent jar: " + NESTED_JAR_PATH);
                }
                byte[] nestedBytes = readFully(outerJar.getInputStream(nested));
                try (JarInputStream in = new JarInputStream(new ByteArrayInputStream(nestedBytes))) {
                    JarEntry e;
                    while ((e = in.getNextJarEntry()) != null) {
                        if (!e.isDirectory()) {
                            map.put(e.getName(), readFully(in));
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("read " + NESTED_JAR_PATH + " failed: " + e.getMessage(), e);
        }
        if (map.isEmpty()) {
            throw new IllegalStateException("nested core jar is empty: " + NESTED_JAR_PATH);
        }
        return map;
    }

    /**
     * 构造指向嵌套 jar 内某 entry 的 URL：自定义协议 agentjar + 显式 handler（不经
     * URLStreamHandlerFactory 全局注册，不与应用争抢 factory），openStream 直接返回内存字节。
     * FaultConfig 用 getResourceAsStream 读 config.yml，依赖 findResource 返回可打开流的 URL。
     */
    private URL nestedUrl(String entryName) {
        String spec = "agentjar:" + outerJarUrl + "!/" + NESTED_JAR_PATH + "!/" + entryName;
        try {
            return new URL(null, spec, new MemoryHandler(entryName));
        } catch (MalformedURLException e) {
            // spec 由本类拼接（location 已校验 file:），不会发生；防御性包装
            throw new IllegalStateException("build nested url failed: " + spec, e);
        }
    }

    /** 读满整个流：不关闭传入的流（嵌套 jar 的遍历流由调用方 try-with-resources 管理） */
    private static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** 从内存 Map 提供 entry 字节流的 URL handler */
    private final class MemoryHandler extends URLStreamHandler {

        private final String entryName;

        MemoryHandler(String entryName) {
            this.entryName = entryName;
        }

        @Override
        protected URLConnection openConnection(URL u) {
            return new URLConnection(u) {
                @Override
                public void connect() {
                    // 无外部连接，字节已在内存
                }

                @Override
                public InputStream getInputStream() throws IOException {
                    byte[] bytes = entries.get(entryName);
                    if (bytes == null) {
                        throw new IOException("entry not found: " + entryName);
                    }
                    return new ByteArrayInputStream(bytes);
                }
            };
        }
    }
}
