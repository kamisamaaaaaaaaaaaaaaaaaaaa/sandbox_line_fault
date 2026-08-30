package cn.chinaclear.fault.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** SHA-256 摘要：lib jar 字节流 / bootJar classes 目录聚合 */
public final class JarHashUtil {

    private JarHashUtil() {
    }

    /** 整个文件（如 bootJar 内嵌 lib jar）字节流 SHA-256 */
    public static String sha256OfStream(InputStream in) {
        MessageDigest md = newDigest();
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        } catch (IOException e) {
            throw new IllegalStateException("digest stream failed: " + e.getMessage(), e);
        }
        return toHex(md.digest());
    }

    /** bootJar 内 BOOT-INF/classes/ 目录聚合 SHA-256：.class 条目按路径排序后逐条「路径+内容」喂入 */
    public static String sha256OfClassesDir(Path bootJar) {
        try (ZipFile zip = new ZipFile(bootJar.toFile())) {
            List<String> names = listClassesEntries(zip);
            MessageDigest md = newDigest();
            for (String name : names) {
                md.update(name.getBytes("UTF-8"));
                try (InputStream in = zip.getInputStream(zip.getEntry(name))) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        md.update(buf, 0, n);
                    }
                }
            }
            return toHex(md.digest());
        } catch (IOException e) {
            throw new IllegalStateException("digest classes dir failed: " + bootJar + " - " + e.getMessage(), e);
        }
    }

    /** bootJar 内 BOOT-INF/classes/ 下全部 .class 条目名（排序） */
    public static List<String> listClassesEntries(ZipFile zip) {
        List<String> names = new ArrayList<>();
        for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ) {
            ZipEntry e = en.nextElement();
            if (!e.isDirectory() && e.getName().startsWith(BootJarParser.BOOT_CLASSES_PREFIX)
                    && e.getName().endsWith(".class")) {
                names.add(e.getName());
            }
        }
        Collections.sort(names);
        return names;
    }

    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    public static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    public static String sha256OfFile(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return sha256OfStream(in);
        } catch (IOException e) {
            throw new IllegalStateException("digest file failed: " + file, e);
        }
    }
}
