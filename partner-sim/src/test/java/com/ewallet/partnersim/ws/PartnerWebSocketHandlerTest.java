package com.ewallet.partnersim.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Kênh WebSocket bền của đối tác giả lập (docs/specs/01-api-contracts.md §8.3).
 *
 * <p>Điểm nghiệp vụ: HTTP chỉ trả "đối tác đã nhận lệnh", còn xác nhận quyết toán
 * đến sau vài trăm ms qua kênh này.</p>
 */
class PartnerWebSocketHandlerTest {

    private static final String ORDER_ID = "33333333-3333-4333-8333-333333333333";

    private PartnerWebSocketHandler handler;
    private RecordingSession session;

    /** Session ghi lại mọi frame gửi ra, thay cho kết nối WebSocket thật. */
    private static final class RecordingSession extends StubWebSocketSession {
        private final List<String> sent = new CopyOnWriteArrayList<>();
        private boolean open = true;

        @Override
        public void sendMessage(WebSocketMessage<?> message) {
            sent.add(String.valueOf(message.getPayload()));
        }

        @Override
        public boolean isOpen() {
            return open;
        }
    }

    @BeforeEach
    void setUp() {
        handler = new PartnerWebSocketHandler();
        session = new RecordingSession();
    }

    @AfterEach
    void tearDown() {
        handler.shutdown();
    }

    private void receive(String json) {
        handler.handleTextMessage(session, new TextMessage(json));
    }

    @Test
    @DisplayName("vừa kết nối thì đối tác chào ngay để bên kia biết kênh đã mở")
    void chaoKhiKetNoi() throws Exception {
        handler.afterConnectionEstablished(session);

        assertThat(session.sent).hasSize(1);
        assertThat(session.sent.get(0)).contains("WELCOME").contains("partner-sim");
    }

    @Test
    @DisplayName("SUBSCRIBE được xác nhận lại")
    void xacNhanDangKy() {
        receive("{\"type\":\"SUBSCRIBE\",\"node\":\"ewallet-third-party\"}");

        assertThat(session.sent).containsExactly("{\"type\":\"SUBSCRIBED\"}");
    }

    @Test
    @DisplayName("PING được trả lời PONG để giữ kênh sống")
    void traLoiPing() {
        receive("{\"type\":\"PING\"}");

        assertThat(session.sent).containsExactly("{\"type\":\"PONG\"}");
    }

    @Test
    @DisplayName("WATCH thì sau một lúc đối tác đẩy xác nhận quyết toán")
    void daySettlementSauWatch() {
        receive("{\"type\":\"WATCH\",\"orderId\":\"" + ORDER_ID + "\",\"partnerRef\":\"PS-abc123\"}");

        // Không trả lời ngay — đó chính là điểm bất đồng bộ cần mô phỏng.
        assertThat(session.sent).isEmpty();

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(session.sent).hasSize(1);
            assertThat(session.sent.get(0))
                    .contains("SETTLEMENT")
                    .contains(ORDER_ID)
                    .contains("PS-abc123")
                    .contains("SETTLED")
                    .contains("settledAt");
        });
    }

    @Test
    @DisplayName("WATCH thiếu orderId thì bỏ qua, không hẹn quyết toán")
    void watchThieuOrderId() {
        receive("{\"type\":\"WATCH\",\"partnerRef\":\"PS-abc123\"}");

        assertThat(session.sent).isEmpty();
    }

    @Test
    @DisplayName("frame không đọc được thì bỏ qua chứ không làm đứt kênh")
    void frameHong() {
        receive("{khong-phai-json");

        assertThat(session.sent).isEmpty();
    }

    @Test
    @DisplayName("frame lạ thì bỏ qua, không trả lời gì")
    void frameLa() {
        receive("{\"type\":\"KHONG_BIET\"}");

        assertThat(session.sent).isEmpty();
    }

    @Test
    @DisplayName("frame không có trường type cũng không làm đứt kênh")
    void frameThieuType() {
        receive("{\"orderId\":\"" + ORDER_ID + "\"}");

        assertThat(session.sent).isEmpty();
    }

    @Test
    @DisplayName("kênh đã đóng thì không cố gửi nữa")
    void kenhDaDong() {
        session.open = false;

        receive("{\"type\":\"PING\"}");

        assertThat(session.sent).isEmpty();
    }

    @Test
    @DisplayName("đóng kênh không ném lỗi")
    void dongKenh() {
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(session.sent).isEmpty();
    }

    /** Chỉ cần vài phương thức của WebSocketSession; phần còn lại để mặc định. */
    private abstract static class StubWebSocketSession implements WebSocketSession {

        @Override
        public String getId() {
            return "test-session";
        }

        @Override
        public java.net.URI getUri() {
            return java.net.URI.create("ws://partner-sim/ws/partner");
        }

        @Override
        public org.springframework.http.HttpHeaders getHandshakeHeaders() {
            return org.springframework.http.HttpHeaders.EMPTY;
        }

        @Override
        public java.util.Map<String, Object> getAttributes() {
            return java.util.Map.of();
        }

        @Override
        public java.security.Principal getPrincipal() {
            return null;
        }

        @Override
        public java.net.InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public java.net.InetSocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public String getAcceptedProtocol() {
            return null;
        }

        @Override
        public void setTextMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getTextMessageSizeLimit() {
            return 0;
        }

        @Override
        public void setBinaryMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getBinaryMessageSizeLimit() {
            return 0;
        }

        @Override
        public List<org.springframework.web.socket.WebSocketExtension> getExtensions() {
            return List.of();
        }

        @Override
        public void close() {
        }

        @Override
        public void close(CloseStatus status) {
        }
    }
}
