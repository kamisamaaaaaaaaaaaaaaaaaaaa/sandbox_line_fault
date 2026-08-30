package cn.chinaclear.fault.common;

import cn.chinaclear.fault.common.model.ClassMethodInfo;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * bootJar 解析器：
 * 1) BOOT-INF/classes/ 整体一个解析单元（hash = 条目聚合）；
 * 2) BOOT-INF/lib/ 中命中白名单的每个 jar 各一个单元（hash = 字节流）；
 * 每个单元输出类-方法明细（方法名 + 描述符），排除 &lt;clinit&gt; 与 synthetic。
 */
public final class BootJarParser {

    public static final String BOOT_CLASSES_PREFIX = "BOOT-INF/classes/";
    public static final String BOOT_LIB_PREFIX = "BOOT-INF/lib/";

    public static final String UNIT_CLASSES = "CLASSES";
    public static final String UNIT_LIB_JAR = "LIB_JAR";

    private BootJarParser() {
    }

    /** 单元解析结果 */
    public static final class ParseUnit {
        public final String unitType;
        public final String sourceJar;
        public final String sha256;
        public final List<ClassMethodInfo> methods;

        ParseUnit(String unitType, String sourceJar, String sha256, List<ClassMethodInfo> methods) {
            this.unitType = unitType;
            this.sourceJar = sourceJar;
            this.sha256 = sha256;
            this.methods = methods;
        }
    }

    /** 解析 bootJar：返回 classes 单元 + 全部白名单命中的 lib 单元 */
    public static List<ParseUnit> parse(Path bootJar, List<String> whitelist) {
        List<ParseUnit> out = new ArrayList<>();
        try (ZipFile zip = new ZipFile(bootJar.toFile())) {
            // 1) BOOT-INF/classes 整体一个单元
            List<String> classEntries = JarHashUtil.listClassesEntries(zip);
            List<ClassMethodInfo> classMethods = new ArrayList<>();
            for (String name : classEntries) {
                try (InputStream in = zip.getInputStream(zip.getEntry(name))) {
                    parseClass(in, classMethods);
                }
            }
            out.add(new ParseUnit(UNIT_CLASSES, bootJar.getFileName().toString(),
                    JarHashUtil.sha256OfClassesDir(bootJar), classMethods));

            // 2) BOOT-INF/lib 白名单单元
            for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ) {
                ZipEntry entry = en.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(BOOT_LIB_PREFIX)
                        || !entry.getName().endsWith(".jar")) {
                    continue;
                }
                String jarName = entry.getName().substring(BOOT_LIB_PREFIX.length());
                if (!matchesWhitelist(jarName, whitelist)) {
                    continue;
                }
                byte[] jarBytes;
                try (InputStream in = zip.getInputStream(entry)) {
                    jarBytes = JarHashUtil.readAll(in);
                }
                List<ClassMethodInfo> libMethods = new ArrayList<>();
                try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
                    ZipEntry classEntry;
                    while ((classEntry = zin.getNextEntry()) != null) {
                        if (!classEntry.isDirectory() && classEntry.getName().endsWith(".class")) {
                            parseClass(zin, libMethods);
                        }
                    }
                }
                String libHash = JarHashUtil.sha256OfStream(new ByteArrayInputStream(jarBytes));
                out.add(new ParseUnit(UNIT_LIB_JAR, jarName, libHash, libMethods));
            }
        } catch (IOException e) {
            throw new IllegalStateException("parse bootJar failed: " + bootJar + " - " + e.getMessage(), e);
        }
        return out;
    }

    /**
     * ASM 解析单个 class：收集全部可注入方法。
     * 排除 &lt;clinit&gt;；synthetic 方法仅纳入 lambda（lambda$ 前缀，其体内为用户逻辑），
     * 其余 synthetic（bridge/access$ 转发）排除以避免重复命中。
     */
    private static void parseClass(InputStream in, final List<ClassMethodInfo> out) {
        try {
            final ClassReader reader = new ClassReader(in);
            final String className = reader.getClassName().replace('/', '.');
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String signature, String[] exceptions) {
                    boolean synthetic = (access & Opcodes.ACC_SYNTHETIC) != 0;
                    boolean isLambda = synthetic && name.startsWith("lambda$");
                    if ("<clinit>".equals(name)) {
                        return null;
                    }
                    if (synthetic && !isLambda) {
                        return null;
                    }
                    out.add(new ClassMethodInfo(0L, className, name, desc));
                    return null;
                }
            }, ClassReader.SKIP_CODE);
        } catch (Exception e) {
            // 单个 class 解析失败只跳过该类，不中断整体解析
            FaultLogger.warn("parse class bytes failed, skipped: " + e.getMessage());
        }
    }

    /** 白名单匹配：精确文件名或前缀匹配 */
    static boolean matchesWhitelist(String jarName, List<String> whitelist) {
        if (whitelist == null) {
            return false;
        }
        for (String w : whitelist) {
            String t = w.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (jarName.equals(t) || jarName.startsWith(t)) {
                return true;
            }
        }
        return false;
    }
}
