package com.ewallet.notification.web;

import com.ewallet.notification.entity.NotificationOutbox;
import com.ewallet.notification.entity.NotificationSentLog;
import com.ewallet.notification.repo.NotificationOutboxRepository;
import com.ewallet.notification.repo.NotificationSentLogRepository;
import com.ewallet.notification.service.SseHub;
import io.swagger.v3.oas.annotations.Operation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Hợp đồng: docs/specs/01-api-contracts.md §10. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationStreamController {

    private static final int MAX_LIMIT = 100;

    private final NotificationOutboxRepository outboxRepository;
    private final NotificationSentLogRepository sentLogRepository;
    private final SseHub sseHub;

    public NotificationStreamController(NotificationOutboxRepository outboxRepository,
                                        NotificationSentLogRepository sentLogRepository,
                                        SseHub sseHub) {
        this.outboxRepository = outboxRepository;
        this.sentLogRepository = sentLogRepository;
        this.sseHub = sseHub;
    }

    @GetMapping("/ping")
    @Operation(summary = "Smoke test - service, notifdb va so ket noi SSE dang mo")
    public Map<String, Object> ping() {
        return Map.of("service", "ewallet-notification", "status", "UP",
                "outboxRows", outboxRepository.count(),
                "sseConnections", sseHub.count());
    }

    /**
     * Kết nối dài: giữ mở tới khi client đóng. Mỗi khi có event Kafka cho khách này,
     * thông báo được đẩy xuống ngay.
     */
    @GetMapping(value = "/stream", produces = "text/event-stream")
    @Operation(summary = "Nhan thong bao realtime qua SSE")
    public SseEmitter stream(@RequestParam(required = false, defaultValue = "") String customerId) {
        return sseHub.subscribe(customerId.isBlank() ? "ALL" : customerId);
    }

    @GetMapping
    @Operation(summary = "Danh sach thong bao da sinh cho mot khach")
    public Map<String, Object> list(@RequestParam String customerId,
                                    @RequestParam(required = false) Integer limit) {
        int size = (limit == null || limit <= 0) ? 20 : Math.min(limit, MAX_LIMIT);

        List<NotificationOutbox> rows = outboxRepository
                .findByCustomerIdOrderByCreatedAtDesc(customerId, PageRequest.of(0, size));

        List<UUID> ids = rows.stream().map(NotificationOutbox::getId).toList();
        Map<UUID, List<NotificationSentLog>> logsByOutbox = ids.isEmpty()
                ? Map.of()
                : sentLogRepository.findByOutboxIdInOrderByIdAsc(ids).stream()
                        .collect(Collectors.groupingBy(NotificationSentLog::getOutboxId));

        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (NotificationOutbox o : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", o.getId().toString());
            item.put("eventType", o.getEventType());
            item.put("channel", o.getChannel());
            item.put("status", o.getStatus());
            item.put("message", o.getPayload());
            item.put("orderId", o.getOrderId() == null ? null : o.getOrderId().toString());
            item.put("attempt", o.getAttempt());
            item.put("createdAt", String.valueOf(o.getCreatedAt()));
            item.put("attempts", logsByOutbox.getOrDefault(o.getId(), List.of()).stream()
                    .map(l -> Map.of("attempt", l.getAttempt(), "result", l.getResult()))
                    .toList());
            items.add(item);
        }

        return Map.of("customerId", customerId, "count", items.size(), "items", items);
    }
}
