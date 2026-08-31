import cn.chinaclear.fault.common.FaultConfig;

public class ProbeConfig {
    public static void main(String[] args) {
        FaultConfig c = FaultConfig.load(null);
        System.out.println("host=" + c.get("jdbc.host", "?"));
        System.out.println("username=" + c.jdbcUsername());
        System.out.println("mountEnabled=" + c.mountEnabled());
        System.out.println("sandboxHome=" + c.sandboxHome());
        System.out.println("premainTimeoutMs=" + c.premainTimeoutMs());
        System.out.println("orphanMinutes=" + c.orphanThresholdMinutes());
        System.out.println("whitelist=" + c.libWhitelist());
        System.out.println("url=" + c.jdbcUrl());
    }
}
