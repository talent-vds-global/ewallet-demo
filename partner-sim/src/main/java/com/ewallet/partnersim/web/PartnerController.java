package com.ewallet.partnersim.web;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stage B: chỉ trả kết quả canned. Latency / fail-rate cấu hình được thêm ở Stage C.
 */
@RestController
@RequestMapping("/partner")
public class PartnerController {

    private static final Logger log = LoggerFactory.getLogger(PartnerController.class);

    @PostMapping("/{code}/execute")
    public Map<String, Object> execute(@PathVariable String code,
                                       @RequestBody(required = false) Map<String, Object> body) {
        log.info("partner-sim execute code={} body={}", code, body);
        return Map.of(
                "status", "SUCCESS",
                "partnerCode", code,
                "partnerRef", UUID.randomUUID().toString(),
                "settledAt", Instant.now().toString()
        );
    }
}
