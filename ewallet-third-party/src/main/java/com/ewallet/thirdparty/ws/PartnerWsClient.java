package com.ewallet.thirdparty.ws;

import com.ewallet.thirdparty.entity.PartnerTransaction;
import com.ewallet.thirdparty.repo.PartnerTransactionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import jakarta.annotation.PreDestroy;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Kênh WebSocket bền tới partner-sim.
 *
 * <p>Mở một lần khi service khởi động và giữ suốt vòng đời. Việc thật của kênh này là nhận
 * <b>xác nhận quyết toán</b> — thứ đến sau khi HTTP đã trả — rồi điền
 * {@code partner_transactions.settled_at}.</p>
 *
 * <p>Với Trace Analyzer, đây là span kết nối dài: kéo dài hàng giờ, không được tính vào
 * latency của flow nghiệp vụ.</p>
 */
@Component
public class PartnerWsClient extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(PartnerWsClient.class);

    private static final long[] RECONNECT_BACKOFF_MS = {1_000L, 2_000L, 5_000L};
    private static final long HEARTBEAT_SECONDS = 30L;
    private static final String PEER = "partner-sim";

    private final WebSocketClient webSocketClient;
    private final PartnerTransactionRepository transactionRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String wsUrl;

    private final AtomicReference<WebSocketSession> session = new AtomicReference<>();
    private final AtomicInteger reconnectAttempt = new AtomicInteger(0);
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "partner-ws");
                t.setDaemon(true);
                return t;
            });

    public PartnerWsClient(WebSocketClient webSocketClient,
                           PartnerTransactionRepository transactionRepository,
                           @Value("${partner-sim.ws-url:ws://partner-sim:8090/ws/partner}") String wsUrl) {
        this.webSocketClient = webSocketClient;
        this.transactionRepository = transactionRepository;
        this.wsUrl = wsUrl;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        connect();
        scheduler.scheduleWithFixedDelay(this::heartbeat,
                HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    private void connect() {
        try {
            webSocketClient.execute(this, wsUrl);
        } catch (Exception e) {
            log.warn("khong mo duoc WebSocket toi {} — se thu lai: {}", wsUrl, e.toString());
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        int attempt = reconnectAttempt.getAndIncrement();
        long delay = RECONNECT_BACKOFF_MS[Math.min(attempt, RECONNECT_BACKOFF_MS.length - 1)];
        scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }

    // ---------------------------------------------------------------- vòng đời kết nối

    @Override
    public void afterConnectionEstablished(WebSocketSession newSession) {
        session.set(newSession);
        reconnectAttempt.set(0);
        log.info("da mo WebSocket toi partner-sim id={}", newSession.getId());
        send("{\"type\":\"SUBSCRIBE\",\"node\":\"ewallet-third-party\"}");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession closed, CloseStatus status) {
        session.set(null);
        log.warn("WebSocket toi partner-sim dong status={} — ket noi lai", status);
        scheduleReconnect();
    }

    // ---------------------------------------------------------------- nhận frame

    @Override
    protected void handleTextMessage(WebSocketSession ignored, TextMessage message) {
        JsonNode node;
        try {
            node = objectMapper.readTree(message.getPayload());
        } catch (Exception e) {
            log.warn("frame khong doc duoc: {}", message.getPayload());
            return;
        }

        String type = node.path("type").asText("");
        if (!"SETTLEMENT".equals(type)) {
            log.debug("bo qua frame type={}", type);
            return;
        }

        String partnerRef = node.path("partnerRef").asText("");
        String orderId = node.path("orderId").asText("");
        Span span = WsTracing.startReceive("SETTLEMENT", PEER,
                node.path(WsTracing.FIELD).asText(null));
        try (Scope ignoredScope = span.makeCurrent()) {
            applySettlement(orderId, partnerRef);
        } finally {
            span.end();
        }
    }

    /** Điền thời điểm quyết toán cho giao dịch tương ứng. */
    private void applySettlement(String orderId, String partnerRef) {
        Optional<PartnerTransaction> found = partnerRef.isBlank()
                ? Optional.empty()
                : transactionRepository.findFirstByPartnerRef(partnerRef);

        if (found.isEmpty()) {
            log.warn("nhan quyet toan nhung khong tim thay giao dich orderId={} partnerRef={}",
                    orderId, partnerRef);
            return;
        }

        PartnerTransaction txn = found.get();
        txn.setSettledAt(OffsetDateTime.now());
        transactionRepository.save(txn);
        log.info("da ghi nhan quyet toan orderId={} partnerRef={}", orderId, partnerRef);
    }

    // ---------------------------------------------------------------- gửi frame

    /** Gọi ngay sau khi đối tác nhận lệnh, để đối tác biết cần báo quyết toán về đâu. */
    public void watch(String orderId, String partnerRef) {
        Span span = WsTracing.startSend("WATCH", PEER);
        try (Scope ignoredScope = span.makeCurrent()) {
            boolean sent = send(WsTracing.withTraceparent(String.format(
                    "{\"type\":\"WATCH\",\"orderId\":\"%s\",\"partnerRef\":\"%s\"}", orderId, partnerRef)));
            if (!sent) {
                span.setStatus(StatusCode.ERROR, "websocket not connected or send failed");
            }
        } finally {
            span.end();
        }
    }

    private void heartbeat() {
        WebSocketSession current = session.get();
        if (current == null || !current.isOpen()) {
            return;
        }
        send("{\"type\":\"PING\"}");
    }

    private boolean send(String payload) {
        WebSocketSession current = session.get();
        if (current == null || !current.isOpen()) {
            log.debug("chua co WebSocket, bo qua frame: {}", payload);
            return false;
        }
        try {
            synchronized (current) {
                current.sendMessage(new TextMessage(payload));
            }
            return true;
        } catch (Exception e) {
            log.warn("gui frame that bai: {}", e.toString());
            return false;
        }
    }

    public boolean isConnected() {
        WebSocketSession current = session.get();
        return current != null && current.isOpen();
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
        WebSocketSession current = session.getAndSet(null);
        if (current != null && current.isOpen()) {
            try {
                current.close(CloseStatus.GOING_AWAY);
            } catch (Exception e) {
                log.debug("dong WebSocket loi: {}", e.toString());
            }
        }
    }
}
