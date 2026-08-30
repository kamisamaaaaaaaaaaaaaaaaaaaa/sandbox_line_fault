package cn.chinaclear.fault.testapp.service;

import cn.chinaclear.fault.testlib.AmountChecker;
import cn.chinaclear.fault.testlib.IdGenerator;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 订单服务：多方法、方法间调用、每方法 10+ 行，用于验证行级 hook 与 kill 命中 */
@Service
public class OrderService {

    private final IdGenerator idGenerator;
    private final AmountChecker amountChecker;
    private final PayService payService;
    private final Map<String, BigDecimal> orders = new ConcurrentHashMap<>();

    public OrderService(IdGenerator idGenerator, AmountChecker amountChecker, PayService payService) {
        this.idGenerator = idGenerator;
        this.amountChecker = amountChecker;
        this.payService = payService;
    }

    public String create(BigDecimal amount) {
        boolean valid = amountChecker.isValid(amount);
        if (!valid) {
            throw new IllegalArgumentException("amount invalid: " + amount);
        }
        String orderId = idGenerator.nextOrderId();
        String traceId = UUID.randomUUID().toString();
        orders.put(orderId, amount);
        boolean large = amountChecker.isLarge(amount);
        if (large) {
            System.out.println("large order created: " + orderId + " trace=" + traceId);
        } else {
            System.out.println("normal order created: " + orderId + " trace=" + traceId);
        }
        return orderId;
    }

    public String pay(String orderId) {
        BigDecimal amount = orders.get(orderId);
        if (amount == null) {
            throw new IllegalArgumentException("order not found: " + orderId);
        }
        String payId = idGenerator.nextPayId();
        String channel = "MOCK-CHANNEL";
        boolean paid = payService.doPay(payId, amount, channel);
        if (paid) {
            String message = "order paid: " + orderId + " by " + payId;
            System.out.println(message);
            return payId;
        }
        String message = "order pay failed: " + orderId;
        System.out.println(message);
        return null;
    }

    public int count() {
        int size = orders.size();
        System.out.println("current order count = " + size);
        return size;
    }
}
