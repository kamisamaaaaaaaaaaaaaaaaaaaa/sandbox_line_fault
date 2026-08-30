package cn.chinaclear.fault.testapp.controller;

import cn.chinaclear.fault.testapp.service.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/** 订单接口：触发 Service 行执行，验证行级命中与表3 抢占 */
@RestController
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/order/create")
    public Map<String, Object> create(@RequestParam BigDecimal amount) {
        Map<String, Object> result = new HashMap<>();
        String orderId = orderService.create(amount);
        result.put("orderId", orderId);
        result.put("amount", amount);
        return result;
    }

    @PostMapping("/order/pay")
    public Map<String, Object> pay(@RequestParam String orderId) {
        Map<String, Object> result = new HashMap<>();
        String payId = orderService.pay(orderId);
        result.put("orderId", orderId);
        result.put("payId", payId);
        return result;
    }

    @GetMapping("/order/count")
    public Map<String, Object> count() {
        Map<String, Object> result = new HashMap<>();
        int count = orderService.count();
        result.put("count", count);
        return result;
    }

    @GetMapping("/order/audit")
    public Map<String, Object> audit() {
        Map<String, Object> result = new HashMap<>();
        orderService.auditAll();
        result.put("audited", true);
        return result;
    }
}
