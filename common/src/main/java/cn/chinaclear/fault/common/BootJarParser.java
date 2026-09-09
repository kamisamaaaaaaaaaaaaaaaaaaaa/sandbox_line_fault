package cn.chinaclear.fault.common;

import cn.chinaclear.fault.common.model.ClassMethodInfo;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * bootJar 解析器（流式，不把全量方法驻留内存）：
 * 1) BOOT-INF/classes/ 整体一个解析单元（hash = 条目聚合；可由 parse.classes.enabled=false 关闭）；
 * 2) BOOT-INF/lib/ 中命中白名单的每个 jar 各一个单元（hash = 字节流）；
 *
 * 每解析到一个单元先回调 {@link UnitHandler#beginUnit}：
 * 返回 sink 则开始流式解析该类内容（方法逐条 push，由调用方按批写库）；
 * 返回 null 则跳过该单元的内容解析（用于"已解析过"的快速跳过，省去 ASM 开销）。
 * 每个单元输出类-方法明细（方法名 + 描述符），
 * 排除 &lt;clinit&gt;、abstract 方法、native 方法与 synthetic（lambda 除外）。
 * 各排除原因见 {@link #parseClass}：收录后都得不到命中，只会虚增覆盖率分母。
 */
public final class BootJarParser {

    public static final String BOOT_CLASSES_PREFIX = "BOOT-INF/classes/";
    public static final String BOOT_LIB_PREFIX = "BOOT-INF/lib/";

    public static final String UNIT_CLASSES = "CLASSES";
    public static final String UNIT_LIB_JAR = "LIB_JAR";

    private BootJarParser() {
    }

    /** 单元方法接收器：由调用方实现分批写库，避免全量方法驻留内存 */
    public interface UnitSink {
        /** 解析出一条方法 */
        void accept(ClassMethodInfo method);

        /** 该单元内容解析结束：flush 剩余批次并收尾 */
        void finish();
    }

    /** 单元回调：返回 sink 表示该单元需要解析；返回 null 表示跳过该单元内容 */
    public interface UnitHandler {
        UnitSink beginUnit(String unitType, String sourceJar, String sha256);
    }

    /**
     * 解析 bootJar：classes 单元（可关闭）+ 全部白名单命中的 lib 单元（流式回调）。
     *
     * @param parseClasses 是否解析 BOOT-INF/classes（应用自身代码）；false 时连该单元的
     *                     哈希计算一并跳过（它要遍历整个 classes 目录，不跳过就白算），
     *                     应用代码既不解析也不注入故障
     * @param whitelist   BOOT-INF/lib 白名单正则（对 jar 文件名全串匹配）；非法即抛，
     *                    由上层 ParseOrchestrator 的 catch(RuntimeException) 转硬保护 PARSE
     */
    public static void parse(Path bootJar, boolean parseClasses, List<String> whitelist,
                             UnitHandler handler) {
        try (ZipFile zip = new ZipFile(bootJar.toFile())) {
            // 1) BOOT-INF/classes 整体一个单元
            if (parseClasses) {
                String classesHash = JarHashUtil.sha256OfClassesDir(bootJar);
                UnitSink classesSink =
                        handler.beginUnit(UNIT_CLASSES, bootJar.getFileName().toString(), classesHash);
                if (classesSink != null) {
                    for (String name : JarHashUtil.listClassesEntries(zip)) {
                        try (InputStream in = zip.getInputStream(zip.getEntry(name))) {
                            parseClass(in, name, classesSink);
                        }
                    }
                    classesSink.finish();
                }
            } else {
                FaultLogger.info("classes unit skipped: parse.classes.enabled=false");
            }

            // 2) BOOT-INF/lib 白名单单元（正则预编译一次，避免每个 jar 重复编译）
            Pattern[] whitelistPatterns = RegexPatterns.compile(whitelist, "lib.whitelist");
            for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ) {
                ZipEntry entry = en.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(BOOT_LIB_PREFIX)
                        || !entry.getName().endsWith(".jar")) {
                    continue;
                }
                String jarName = entry.getName().substring(BOOT_LIB_PREFIX.length());
                if (!RegexPatterns.matchesAny(whitelistPatterns, jarName)) {
                    continue;
                }
                // 先用流算 hash（不把整个 lib jar 读入内存）
                String jarHash;
                try (InputStream in = zip.getInputStream(entry)) {
                    jarHash = JarHashUtil.sha256OfStream(in);
                }
                UnitSink libSink = handler.beginUnit(UNIT_LIB_JAR, jarName, jarHash);
                if (libSink == null) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry);
                     ZipInputStream zin = new ZipInputStream(in)) {
                    ZipEntry classEntry;
                    while ((classEntry = zin.getNextEntry()) != null) {
                        if (!classEntry.isDirectory() && classEntry.getName().endsWith(".class")) {
                            parseClass(zin, jarName + "!" + classEntry.getName(), libSink);
                        }
                    }
                }
                libSink.finish();
            }
        } catch (IOException e) {
            throw new IllegalStateException("parse bootJar failed: " + bootJar + " - " + e.getMessage(), e);
        }
    }

    /**
     * ASM 解析单个 class：收集全部可注入方法，逐条 push 给 sink。
     * 排除 &lt;clinit&gt;（sandbox 在类结构收集阶段即硬编码排除它，收录也无法织入，
     * 只会制造永不命中的覆盖率盲区——已用独立应用实测确认）；
     * 排除 abstract 方法（无 Code 属性，织入器匹配得到却无处插桩，注册后永不回调）；
     * 排除 native 方法（织入时只产生 BEFORE/RETURN/THROWS 事件，不产生 LINE 事件，
     * 而本模块只监听 beforeLine，注册后同样永不回调）；
     * synthetic 方法仅纳入 lambda（lambda$ 前缀，其体内为用户逻辑），
     * 其余 synthetic（bridge/access$ 转发）排除以避免重复命中。
     */
    private static void parseClass(InputStream in, final String entryName, final UnitSink sink) {
        try {
            final ClassReader reader = new ClassReader(in);
            final String className = reader.getClassName().replace('/', '.');
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String signature, String[] exceptions) {
                    // <clinit> 排除（B 系列实测）：sandbox 在类结构收集阶段即硬编码排除它
                    //（ClassStructureImplByAsm$5$1.visitMethod：StringUtils.equals("<clinit>", name) 直接放行、
                    // 不收集为 BehaviorStructure）→ 它进不了 signCodes，织入器永不改写。验证期收录注册后
                    // 静态块执行过但零命中，收录只会让表2 多出一批永不命中的行，制造覆盖率盲区。
                    if ("<clinit>".equals(name)) {
                        return null;
                    }
                    // synthetic 排除（B 系列实测）：仅保留 lambda（lambda$ 前缀，体内为用户逻辑、行号指向
                    // lambda 体，正常命中）。其余 synthetic（bridge/access$ 转发）收录注册后确实被织入，
                    // 但其 LNT 行号指向类声明行——命中记录落在类声明行上，是语义无意义的垃圾行
                    //（不是与目标方法同行重复，修正早先"重复命中"的表述），排除。
                    boolean isLambda = name.startsWith("lambda$") && (access & Opcodes.ACC_SYNTHETIC) != 0;
                    if ((access & Opcodes.ACC_SYNTHETIC) != 0 && !isLambda) {
                        return null;
                    }
                    // abstract 排除：class 文件里没有 Code 属性。织入器能匹配到它（signCodes 命中），
                    // 但没有方法体可插桩，注册后永不产生回调。
                    if ((access & Opcodes.ACC_ABSTRACT) != 0) {
                        return null;
                    }
                    // native 排除（B 系列实测，真实 so 调用）：sandbox 的 rewriteNativeMethod 会去掉
                    // native 并生成代理方法，确实完成了织入，但只插 spyMethodOnBefore /
                    // spyMethodOnReturn / spyMethodOnThrows，不插 spyMethodOnLine——native 无 Code 属性
                    // 也就没有 LineNumberTable，无行号可报。本模块只监听 beforeLine，注册后永不回调
                    //（验证期收录注册，so 正常调用返回但零命中）。
                    if ((access & Opcodes.ACC_NATIVE) != 0) {
                        return null;
                    }
                    // 描述符完整入库（TEXT 列，不入索引）；区分重载由 descHash 承担。
                    // 列宽不做预检：超限由数据库判定，异常时 JdbcHelper 的行级上下文会带出完整方法信息
                    sink.accept(new ClassMethodInfo(0L, 0L, className, name, desc,
                            JarHashUtil.md5Hex16(desc)));
                    return null;
                }
            }, ClassReader.SKIP_CODE);
        } catch (Exception e) {
            // 单个 class 解析失败 = 解析结果不完整，按硬保护处理（宁可 kill 也不放过解析不完整的进程）
            throw new IllegalStateException("parse class failed: " + entryName + " - " + e.getMessage(), e);
        }
    }
}
