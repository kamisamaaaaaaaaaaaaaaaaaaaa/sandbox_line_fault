package probe;

import java.lang.instrument.Instrumentation;

public class ProbeAgent {
    public static void premain(String args, Instrumentation inst) {
        System.out.println("SUN-JAVA-COMMAND=" + System.getProperty("sun.java.command"));
        System.exit(0);
    }

    public static void main(String[] a) {
    }
}
