package cn.chinaclear.fault.testlib;

/** 白名单验证 lib：id 生成器（多方法多行） */
public class IdGenerator {

    private long sequence = 0;

    public synchronized String nextOrderId() {
        sequence = sequence + 1;
        long ts = System.currentTimeMillis();
        String prefix = "ORD-";
        String body = String.format("%d%04d", ts, sequence);
        return prefix + body;
    }

    public synchronized String nextPayId() {
        sequence = sequence + 2;
        long ts = System.currentTimeMillis();
        String prefix = "PAY-";
        String body = String.format("%d%04d", ts, sequence);
        return prefix + body;
    }
}
