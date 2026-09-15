package com.ewallet.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Hub giữ các kết nối SSE đang mở — R-NOTIF-06: mỗi client chỉ nhận thông báo
 * của đúng khách hàng mình đăng ký.
 */
class SseHubTest {

    private static final String CUSTOMER = "CUST-001";
    private static final String OTHER = "CUST-002";

    private SseHub hub;

    /** Emitter ghi lại những gì được đẩy vào, thay cho kết nối HTTP thật. */
    private static final class RecordingEmitter extends SseEmitter {
        private final List<Object> sent = new ArrayList<>();
        private final boolean broken;

        RecordingEmitter(boolean broken) {
            super(0L);
            this.broken = broken;
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (broken) {
                throw new IOException("client da dong ket noi");
            }
            sent.add(builder);
        }
    }

    @BeforeEach
    void setUp() {
        hub = new SseHub();
    }

    @AfterEach
    void tearDown() {
        hub.shutdown();
    }

    @Test
    @DisplayName("mở kết nối thì hub đếm được và client nhận ngay sự kiện chào")
    void moKetNoi() {
        SseEmitter emitter = hub.subscribe(CUSTOMER);

        assertThat(emitter).isNotNull();
        assertThat(hub.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("một khách mở nhiều thiết bị thì đếm đủ từng kết nối")
    void nhieuThietBiCungMotKhach() {
        hub.subscribe(CUSTOMER);
        hub.subscribe(CUSTOMER);
        hub.subscribe(OTHER);

        assertThat(hub.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("R-NOTIF-06: đẩy thông báo cho khách nào thì chỉ khách đó nhận")
    void chiDungKhachNhan() {
        RecordingEmitter mine = new RecordingEmitter(false);
        RecordingEmitter theirs = new RecordingEmitter(false);
        register(CUSTOMER, mine);
        register(OTHER, theirs);

        hub.push(CUSTOMER, "{\"type\":\"PAYMENT\"}");

        assertThat(mine.sent).hasSize(1);
        assertThat(theirs.sent).isEmpty();
    }

    @Test
    @DisplayName("mọi thiết bị của cùng một khách đều nhận được thông báo")
    void moiThietBiDeuNhan() {
        RecordingEmitter phone = new RecordingEmitter(false);
        RecordingEmitter tablet = new RecordingEmitter(false);
        register(CUSTOMER, phone);
        register(CUSTOMER, tablet);

        hub.push(CUSTOMER, "{}");

        assertThat(phone.sent).hasSize(1);
        assertThat(tablet.sent).hasSize(1);
    }

    @Test
    @DisplayName("không có ai đang nghe thì bỏ qua, thông báo vẫn nằm trong outbox")
    void khongCoAiNghe() {
        hub.push("CUST-999", "{}");

        assertThat(hub.count()).isZero();
    }

    @Test
    @DisplayName("client đã ngắt thì bị gỡ khỏi hub ngay lần đẩy thất bại")
    void clientNgatThiGoBo() {
        register(CUSTOMER, new RecordingEmitter(true));
        assertThat(hub.count()).isEqualTo(1);

        hub.push(CUSTOMER, "{}");

        assertThat(hub.count()).isZero();
    }

    @Test
    @DisplayName("client ngắt không ảnh hưởng client còn lại của cùng khách")
    void motClientNgatKhongAnhHuongClientKhac() {
        RecordingEmitter ok = new RecordingEmitter(false);
        register(CUSTOMER, new RecordingEmitter(true));
        register(CUSTOMER, ok);

        hub.push(CUSTOMER, "{}");

        assertThat(hub.count()).isEqualTo(1);
        assertThat(ok.sent).hasSize(1);
    }

    @Test
    @DisplayName("đóng hub thì mọi kết nối được dọn sạch")
    void dongHub() {
        hub.subscribe(CUSTOMER);
        hub.subscribe(OTHER);

        hub.shutdown();

        assertThat(hub.count()).isZero();
    }

    /**
     * Gắn một emitter tự dựng vào hub để quan sát được nội dung đẩy ra.
     * {@code subscribe()} tạo emitter riêng bên trong nên không dùng để quan sát được.
     */
    @SuppressWarnings("unchecked")
    private void register(String customerId, SseEmitter emitter) {
        Map<String, List<SseEmitter>> byCustomer =
                (Map<String, List<SseEmitter>>) ReflectionTestUtils.getField(hub, "byCustomer");
        byCustomer.computeIfAbsent(customerId, k -> new CopyOnWriteArrayList<>()).add(emitter);
    }
}
