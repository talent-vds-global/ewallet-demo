package com.ewallet.thirdparty.web;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stage B: smoke test + query để db-quality có SQL pattern.
 * POST /api/thirdparty/execute (chọn adapter theo partner_code) thêm ở Stage C.
 */
@RestController
@RequestMapping("/api/thirdparty")
public class ThirdPartyPingController {

    private final JdbcTemplate jdbc;

    public ThirdPartyPingController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM partner_config", Integer.class);
        return Map.of("service", "ewallet-third-party", "status", "UP", "partnerConfigRows", n);
    }
}
