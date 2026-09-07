package com.ewallet.payment.web;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stage B: smoke test + query để db-quality có SQL pattern.
 * Endpoint admin thật (/admin/limits ...) thêm ở Stage C.
 */
@RestController
@RequestMapping("/admin")
public class PaymentPingController {

    private final JdbcTemplate jdbc;

    public PaymentPingController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM limit_config", Integer.class);
        return Map.of("service", "ewallet-payment-business", "status", "UP", "limitConfigRows", n);
    }
}
