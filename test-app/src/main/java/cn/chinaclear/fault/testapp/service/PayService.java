package cn.chinaclear.fault.testapp.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/** 支付服务：多方法多行 */
@Service
public class PayService {

    public boolean doPay(String payId, BigDecimal amount, String channel) {
        System.out.println("pay begin: " + payId + " amount=" + amount + " channel=" + channel);
        boolean amountReady = amount != null;
        if (!amountReady) {
            return false;
        }
        boolean channelReady = channel != null && !channel.isEmpty();
        if (!channelReady) {
            return false;
        }
        long start = System.currentTimeMillis();
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long cost = System.currentTimeMillis() - start;
        System.out.println("pay finished: " + payId + " cost=" + cost + "ms");
        return true;
    }

    public boolean refund(String payId) {
        System.out.println("refund begin: " + payId);
        String reason = "MOCK-REFUND";
        boolean ok = payId != null;
        if (!ok) {
            return false;
        }
        System.out.println("refund reason: " + reason);
        System.out.println("refund finished: " + payId);
        return true;
    }
}
