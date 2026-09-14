package com.ewallet.notification.service;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Giữ các kết nối SSE đang mở, gom theo khách hàng.
 *
 * <p>R-NOTIF-06: mỗi client chỉ nhận thông báo của đúng khách hàng mình đăng ký.
 * Đây là loại kết nối dài thứ hai của hệ (cùng với WebSocket ở third-party) — span của nó
 * kéo dài nhiều phút nên không được tính vào latency của flow nghiệp vụ.</p>
 */
@Component
public class SseHub {

    private static final Logger log = LoggerFactory.getLogger(SseHub.class);

    private static final long HEARTBEAT_SECONDS = 15L;

    private final Map<String, List<SseEmitter>> byCustomer = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

    public SseHub() {
        scheduler.scheduleWithFixedDelay(this::heartbeat,
                HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    public SseEmitter subscribe(String customerId) {
        SseEmitter emitter = new SseEmitter(0L);   // không tự hết hạn
        byCustomer.computeIfAbsent(customerId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> remove(customerId, emitter));
        emitter.onTimeout(() -> remove(customerId, emitter));
        emitter.onError(e -> remove(customerId, emitter));

        try {
            emitter.send(SseEmitter.event().name("connected")
                    .data("{\"type\":\"CONNECTED\",\"customerId\":\"" + customerId + "\"}"));
        } catch (IOException e) {
            remove(customerId, emitter);
        }
        log.info("SSE mo cho customerId={} tong ket noi={}", customerId, count());
        return emitter;
    }

    /** Đẩy một thông báo tới mọi kết nối đang mở của khách đó. Không có ai nghe thì bỏ qua (D1). */
    public void push(String customerId, String json) {
        List<SseEmitter> emitters = byCustomer.get(customerId);
        if (emitters == null || emitters.isEmpty()) {
            log.debug("khong co client SSE nao cua {} — thong bao van nam trong outbox", customerId);
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("payment").data(json));
            } catch (Exception e) {
                remove(customerId, emitter);
            }
        }
    }

    public int count() {
        return byCustomer.values().stream().mapToInt(List::size).sum();
    }

    private void heartbeat() {
        byCustomer.forEach((customerId, emitters) -> {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                } catch (Exception e) {
                    remove(customerId, emitter);
                }
            }
        });
    }

    private void remove(String customerId, SseEmitter emitter) {
        List<SseEmitter> emitters = byCustomer.get(customerId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                byCustomer.remove(customerId);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
        byCustomer.values().forEach(list -> list.forEach(SseEmitter::complete));
        byCustomer.clear();
    }
}
