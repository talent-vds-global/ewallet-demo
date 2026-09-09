package com.ewallet.partnersim.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Kênh WebSocket bền với ewallet-third-party.
 *
 * <p>Mục đích nghiệp vụ thật, không phải gắn cho có: HTTP chỉ trả "đối tác đã nhận lệnh",
 * còn <b>xác nhận quyết toán</b> đến sau vài trăm ms và được đẩy ngược qua kênh này.
 * Nhờ vậy trace có một span kết nối dài, tách khỏi span request/response.</p>
 *
 * <p>Khung frame: docs/specs/01-api-contracts.md §8.3.</p>
 */
@Component
public class PartnerWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(PartnerWebSocketHandler.class);

    /** Độ trễ quyết toán — đủ lâu để HTTP đã trả xong trước khi frame này tới. */
    private static final long SETTLEMENT_DELAY_MS = 300L;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "partner-settlement");
                t.setDaemon(true);
                return t;
            });

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("partner-sim ws ket noi id={}", session.getId());
        send(session, "{\"type\":\"WELCOME\",\"node\":\"partner-sim\"}");
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        String type;
        JsonNode node;
        try {
            node = objectMapper.readTree(payload);
            type = node.path("type").asText("");
        } catch (Exception e) {
            log.warn("partner-sim ws frame khong doc duoc: {}", payload);
            return;
        }

        switch (type) {
            case "SUBSCRIBE" -> {
                log.info("partner-sim ws SUBSCRIBE tu node={}", node.path("node").asText("?"));
                send(session, "{\"type\":\"SUBSCRIBED\"}");
            }
            case "WATCH" -> scheduleSettlement(session,
                    node.path("orderId").asText(""), node.path("partnerRef").asText(""));
            case "PING" -> send(session, "{\"type\":\"PONG\"}");
            default -> log.debug("partner-sim ws bo qua frame type={}", type);
        }
    }

    /** Sau ~300ms thì báo đã quyết toán — đây là phần bất đồng bộ thật của đối tác. */
    private void scheduleSettlement(WebSocketSession session, String orderId, String partnerRef) {
        if (orderId.isBlank()) {
            return;
        }
        log.info("partner-sim ws se bao quyet toan orderId={} sau {}ms", orderId, SETTLEMENT_DELAY_MS);
        scheduler.schedule(() -> {
            String frame = String.format(
                    "{\"type\":\"SETTLEMENT\",\"orderId\":\"%s\",\"partnerRef\":\"%s\","
                            + "\"status\":\"SETTLED\",\"settledAt\":\"%s\"}",
                    orderId, partnerRef, Instant.now());
            send(session, frame);
        }, SETTLEMENT_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void send(WebSocketSession session, String payload) {
        try {
            if (session.isOpen()) {
                synchronized (session) {
                    session.sendMessage(new TextMessage(payload));
                }
            }
        } catch (Exception e) {
            log.warn("partner-sim ws gui that bai id={}", session.getId(), e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("partner-sim ws dong id={} status={}", session.getId(), status);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
