package cn.chinaclear.fault.common;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** 机器标识：hostname + ip（site-local IPv4 优先） */
public final class MachineInfo {

    private static volatile String[] cached;

    private MachineInfo() {
    }

    public static String hostname() {
        return get()[0];
    }

    public static String ip() {
        return get()[1];
    }

    private static String[] get() {
        String[] c = cached;
        if (c != null) {
            return c;
        }
        synchronized (MachineInfo.class) {
            c = cached;
            if (c != null) {
                return c;
            }
            String host = "unknown";
            String ip = "127.0.0.1";
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (Throwable ignore) {
                // keep default
            }
            try {
                String siteLocal = null;
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                        continue;
                    }
                    for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && ip.equals("127.0.0.1")) {
                            ip = addr.getHostAddress();
                        }
                        if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                            siteLocal = addr.getHostAddress();
                            break;
                        }
                    }
                    if (siteLocal != null) {
                        break;
                    }
                }
                if (siteLocal != null) {
                    ip = siteLocal;
                }
            } catch (Throwable ignore) {
                // keep default
            }
            cached = new String[]{host, ip};
            return cached;
        }
    }
}
