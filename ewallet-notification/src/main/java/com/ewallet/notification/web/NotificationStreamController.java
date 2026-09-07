package com.ewallet.notification.web;

import java.io.IOException;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Stage B: SSE mở kết nối dài + gửi 1 event chào. Stage C: đẩy notification thật khi có event Kafka.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationStreamController {

    private final JdbcTemplate jdbc;

    public NotificationStreamController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM notification_outbox", Integer.class);
        return Map.of("service", "ewallet-notification", "status", "UP", "outboxRows", n);
    }

    @GetMapping(value = "/stream", produces = "text/event-stream")
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(0L); // không timeout
        try {
            emitter.send(SseEmitter.event().name("hello").data("{\"type\":\"CONNECTED\"}"));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }
}
