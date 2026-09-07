package com.ewallet.order.web;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stage B: smoke test + 1 query để db-quality có SQL pattern để thu.
 * Endpoint nghiệp vụ (POST /api/orders, GET /api/orders/history ...) thêm ở Stage C.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderPingController {

    private final JdbcTemplate jdbc;

    public OrderPingController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM payment_orders", Integer.class);
        return Map.of("service", "ewallet-payment-order", "status", "UP", "orderCount", n);
    }
}
