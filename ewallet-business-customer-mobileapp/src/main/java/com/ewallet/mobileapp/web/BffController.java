package com.ewallet.mobileapp.web;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Stage B: chỉ smoke test. Endpoint nghiệp vụ (topup / bill / p2p / refund) thêm ở Stage C.
 */
@RestController
@RequestMapping("/api")
public class BffController {

    private static final Logger log = LoggerFactory.getLogger(BffController.class);
    private final RestClient orderRestClient;

    public BffController(RestClient orderRestClient) {
        this.orderRestClient = orderRestClient;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of("service", "ewallet-business-customer-mobileapp", "status", "UP");
    }

    /** Hop 2 tầng để kiểm chứng trace gateway -> mobileapp -> order. */
    @GetMapping("/trace-test")
    public Map<String, Object> traceTest() {
        String downstream;
        try {
            downstream = orderRestClient.get().uri("/actuator/health").retrieve().body(String.class);
        } catch (Exception e) {
            log.warn("downstream order not ready: {}", e.toString());
            downstream = "unreachable";
        }
        return Map.of("hop", "mobileapp -> order", "downstream", downstream);
    }
}
