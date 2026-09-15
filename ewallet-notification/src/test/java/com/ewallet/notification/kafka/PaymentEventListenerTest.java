package com.ewallet.notification.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ewallet.notification.entity.NotificationOutbox;
import com.ewallet.notification.repo.NotificationOutboxRepository;
import com.ewallet.notification.repo.NotificationSentLogRepository;
import com.ewallet.notification.service.NotificationRouter;
import com.ewallet.notification.service.NotificationSender;
import com.ewallet.notification.service.SseHub;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Consumer group {@code notification-cg} — sinh thông báo, thử lại, rồi đẩy sang DLT (F5).
 *
 * <p>Kiểm tra R-NOTIF-03/04 (thử lại tối đa 3 lần rồi mới sang DLT),
 * R-NOTIF-05 (một event + kênh + khách chỉ một bản ghi outbox) và cách phân loại
 * lỗi vĩnh viễn (JSON hỏng) với lỗi tạm thời (nhà cung cấp từ chối).</p>
 */
@ExtendWith(MockitoExtension.class)
class PaymentEventListenerTest {

    private static final String TOPIC = "ewallet.payment.events";
    private static final String DLT = TOPIC + ".DLT";
    private static final String CUSTOMER = "CUST-001";

    @Mock private NotificationOutboxRepository outboxRepository;
    @Mock private NotificationSentLogRepository sentLogRepository;
    @Mock private NotificationSender sender;
    @Mock private SseHub sseHub;
    @Mock private KafkaTemplate<String, String> kafkaTemplate;

    private PaymentEventListener listener;
    private UUID eventId;
    private UUID orderId;

    @BeforeEach
    void setUp() {
        eventId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        listener = newListener(3, 10L);
        lenient().when(outboxRepository.findByEventIdAndChannelAndCustomerId(any(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        lenient().when(outboxRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        listener.shutdown();
    }

    private PaymentEventListener newListener(int maxAttempts, long backoffMs) {
        return new PaymentEventListener(outboxRepository, sentLogRepository,
                new NotificationRouter(), sender, sseHub, kafkaTemplate, new ObjectMapper(),
                TOPIC, maxAttempts, backoffMs);
    }

    private String event(String eventType, String counterparty, long amount) {
        return """
                {"eventId":"%s","eventType":"%s","orderId":"%s","customerId":"%s",
                 "counterpartyCustomerId":%s,"amount":%d,"amountVnd":%d,"reasonCode":"OK"}
                """.formatted(eventId, eventType, orderId, CUSTOMER,
                counterparty == null ? "null" : "\"" + counterparty + "\"", amount, amount);
    }

    private ConsumerRecord<String, String> record(String json, Integer attempt) {
        ConsumerRecord<String, String> rec =
                new ConsumerRecord<>(TOPIC, 0, 0L, orderId.toString(), json);
        if (attempt != null) {
            rec.headers().add(new RecordHeader("x-attempt",
                    String.valueOf(attempt).getBytes(StandardCharsets.UTF_8)));
        }
        return rec;
    }

    private void senderFails() {
        doThrow(new NotificationSender.SendFailedException("nha cung cap tu choi"))
                .when(sender).send(anyString(), anyString(), anyString());
    }

    @Nested
    @DisplayName("gửi thành công")
    class GuiThanhCong {

        @Test
        @DisplayName("thông báo được gửi, ghi outbox SENT và đẩy sang kênh SSE")
        void guiDuocThiGhiSent() {
            listener.onPaymentEvent(record(event("PaymentCompleted", null, 500_000L), null));

            verify(sender).send(CUSTOMER, NotificationOutbox.PUSH, "Giao dich thanh cong 500.000d");
            verify(sseHub).push(anyString(), anyString());
            verify(kafkaTemplate, never()).send(any(ProducerRecord.class));

            ArgumentCaptor<NotificationOutbox> captor = ArgumentCaptor.forClass(NotificationOutbox.class);
            verify(outboxRepository, times(2)).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(NotificationOutbox.SENT);
            assertThat(captor.getValue().getEventId()).isEqualTo(eventId);
            assertThat(captor.getValue().getOrderId()).isEqualTo(orderId);
        }

        @Test
        @DisplayName("R-NOTIF-02: chuyển tiền sinh thông báo cho cả hai bên")
        void baoChoCaHaiBen() {
            listener.onPaymentEvent(record(event("PaymentCompleted", "CUST-002", 3_000_000L), null));

            verify(sender).send(CUSTOMER, NotificationOutbox.PUSH, "Giao dich thanh cong 3.000.000d");
            verify(sender).send("CUST-002", NotificationOutbox.PUSH,
                    "Ban da nhan 3.000.000d tu " + CUSTOMER);
            verify(sseHub, times(2)).push(anyString(), anyString());
        }

        @Test
        @DisplayName("R-NOTIF-05: bản ghi outbox đã SENT thì không gửi lại lần nữa")
        void daGuiThiKhongGuiLai() {
            NotificationOutbox sent = new NotificationOutbox();
            sent.setId(UUID.randomUUID());
            sent.setEventId(eventId);
            sent.setStatus(NotificationOutbox.SENT);
            when(outboxRepository.findByEventIdAndChannelAndCustomerId(
                    eventId, NotificationOutbox.PUSH, CUSTOMER)).thenReturn(Optional.of(sent));

            listener.onPaymentEvent(record(event("PaymentCompleted", null, 500_000L), null));

            verify(sender, never()).send(anyString(), anyString(), anyString());
            verify(outboxRepository, never()).save(any());
        }

        @Test
        @DisplayName("R-NOTIF-05: lần thử lại dùng lại chính bản ghi outbox cũ, không tạo bản ghi mới")
        void thuLaiDungLaiBanGhiCu() {
            UUID outboxId = UUID.randomUUID();
            NotificationOutbox retrying = new NotificationOutbox();
            retrying.setId(outboxId);
            retrying.setEventId(eventId);
            retrying.setStatus(NotificationOutbox.RETRYING);
            when(outboxRepository.findByEventIdAndChannelAndCustomerId(
                    eventId, NotificationOutbox.PUSH, CUSTOMER)).thenReturn(Optional.of(retrying));

            listener.onPaymentEvent(record(event("PaymentCompleted", null, 500_000L), 2));

            ArgumentCaptor<NotificationOutbox> captor = ArgumentCaptor.forClass(NotificationOutbox.class);
            verify(outboxRepository, times(2)).save(captor.capture());
            assertThat(captor.getValue().getId()).isEqualTo(outboxId);
            assertThat(captor.getValue().getAttempt()).isEqualTo(2);
            assertThat(captor.getValue().getStatus()).isEqualTo(NotificationOutbox.SENT);
        }

        @Test
        @DisplayName("eventType không có kênh nào thì bỏ qua, không ghi outbox")
        void eventTypeKhongCoKenh() {
            listener.onPaymentEvent(record(event("PaymentKhongBiet", null, 500_000L), null));

            verify(outboxRepository, never()).save(any());
            verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        }
    }

    @Nested
    @DisplayName("thử lại và dead letter")
    class ThuLaiVaDlt {

        @Test
        @DisplayName("R-NOTIF-03: gửi hỏng lần đầu thì republish lại topic gốc với x-attempt=2")
        void hongLanDauThiThuLai() {
            senderFails();

            listener.onPaymentEvent(record(event("PaymentCompleted", null, 500_000L), null));

            ArgumentCaptor<ProducerRecord<String, String>> captor = producerCaptor();
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> verify(kafkaTemplate).send(captor.capture()));

            ProducerRecord<String, String> retry = captor.getValue();
            assertThat(retry.topic()).isEqualTo(TOPIC);
            assertThat(retry.key()).isEqualTo(orderId.toString());
            assertThat(attemptHeader(retry)).isEqualTo("2");
        }

        @Test
        @DisplayName("gửi hỏng nhưng chưa hết lượt thì outbox ở trạng thái RETRYING")
        void hongThiGhiRetrying() {
            senderFails();

            listener.onPaymentEvent(record(event("PaymentCompleted", null, 500_000L), 1));

            ArgumentCaptor<NotificationOutbox> captor = ArgumentCaptor.forClass(NotificationOutbox.class);
            verify(outboxRepository, times(2)).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(NotificationOutbox.RETRYING);
        }

        @Test
        @DisplayName("R-NOTIF-04: hỏng ở lần thứ ba thì đẩy thẳng sang DLT, không thử lại nữa")
        void hetLuotThiSangDlt() {
            senderFails();

            listener.onPaymentEvent(record(event("PaymentCompleted", null, 500_000L), 3));

            ArgumentCaptor<ProducerRecord<String, String>> captor = producerCaptor();
            verify(kafkaTemplate).send(captor.capture());
            assertThat(captor.getValue().topic()).isEqualTo(DLT);
            assertThat(attemptHeader(captor.getValue())).isEqualTo("3");
        }

        @Test
        @DisplayName("lần thử cuối ghi outbox ở trạng thái DEAD_LETTER")
        void lanCuoiGhiDeadLetter() {
            senderFails();

            listener.onPaymentEvent(record(event("PaymentCompleted", null, 500_000L), 3));

            ArgumentCaptor<NotificationOutbox> captor = ArgumentCaptor.forClass(NotificationOutbox.class);
            verify(outboxRepository, times(2)).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(NotificationOutbox.DEAD_LETTER);
        }

        @Test
        @DisplayName("gửi hỏng thì không đẩy gì sang kênh SSE")
        void hongThiKhongDaySse() {
            senderFails();

            listener.onPaymentEvent(record(event("PaymentCompleted", null, 500_000L), 3));

            verify(sseHub, never()).push(anyString(), anyString());
        }

        @Test
        @DisplayName("cấu hình một lượt duy nhất thì hỏng lần đầu là vào thẳng DLT")
        void motLuotDuyNhat() {
            listener = newListener(1, 10L);
            senderFails();

            listener.onPaymentEvent(record(event("PaymentCompleted", null, 500_000L), null));

            ArgumentCaptor<ProducerRecord<String, String>> captor = producerCaptor();
            verify(kafkaTemplate).send(captor.capture());
            assertThat(captor.getValue().topic()).isEqualTo(DLT);
        }

        @Test
        @DisplayName("header lạ của message gốc được giữ lại khi thử lại")
        void giuHeaderGoc() {
            senderFails();
            ConsumerRecord<String, String> rec = record(event("PaymentCompleted", null, 500_000L), 3);
            rec.headers().add(new RecordHeader("eventType",
                    "PaymentCompleted".getBytes(StandardCharsets.UTF_8)));

            listener.onPaymentEvent(rec);

            ArgumentCaptor<ProducerRecord<String, String>> captor = producerCaptor();
            verify(kafkaTemplate).send(captor.capture());
            assertThat(captor.getValue().headers().lastHeader("eventType")).isNotNull();
        }
    }

    @Nested
    @DisplayName("message hỏng")
    class MessageHong {

        @Test
        @DisplayName("D6: JSON không đọc được là lỗi vĩnh viễn — vào thẳng DLT, không thử lại")
        void jsonHongVaoThangDlt() {
            listener.onPaymentEvent(record("{khong-phai-json", null));

            ArgumentCaptor<ProducerRecord<String, String>> captor = producerCaptor();
            verify(kafkaTemplate).send(captor.capture());
            assertThat(captor.getValue().topic()).isEqualTo(DLT);
            verify(sender, never()).send(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("eventId không phải UUID thì bỏ qua, không gửi và không đẩy DLT")
        void eventIdKhongHopLe() {
            String json = """
                    {"eventId":"khong-phai-uuid","eventType":"PaymentCompleted",
                     "customerId":"CUST-001","amount":500000,"amountVnd":500000}
                    """;

            listener.onPaymentEvent(record(json, null));

            verify(sender, never()).send(anyString(), anyString(), anyString());
            verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        }

        @Test
        @DisplayName("header x-attempt hỏng thì coi như lần thử đầu tiên")
        void headerAttemptHong() {
            senderFails();
            ConsumerRecord<String, String> rec = record(event("PaymentCompleted", null, 500_000L), null);
            rec.headers().add(new RecordHeader("x-attempt", "ba".getBytes(StandardCharsets.UTF_8)));

            listener.onPaymentEvent(rec);

            ArgumentCaptor<NotificationOutbox> captor = ArgumentCaptor.forClass(NotificationOutbox.class);
            verify(outboxRepository, times(2)).save(captor.capture());
            assertThat(captor.getValue().getAttempt()).isEqualTo(1);
        }

        @Test
        @DisplayName("orderId rỗng trong event thì outbox vẫn ghi được, chỉ là không có orderId")
        void thieuOrderId() {
            String json = """
                    {"eventId":"%s","eventType":"PaymentCompleted","orderId":"",
                     "customerId":"CUST-001","amount":500000,"amountVnd":500000}
                    """.formatted(eventId);

            listener.onPaymentEvent(record(json, null));

            ArgumentCaptor<NotificationOutbox> captor = ArgumentCaptor.forClass(NotificationOutbox.class);
            verify(outboxRepository, times(2)).save(captor.capture());
            assertThat(captor.getValue().getOrderId()).isNull();
            assertThat(captor.getValue().getStatus()).isEqualTo(NotificationOutbox.SENT);
        }
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<ProducerRecord<String, String>> producerCaptor() {
        return ArgumentCaptor.forClass((Class<ProducerRecord<String, String>>) (Class<?>) ProducerRecord.class);
    }

    private String attemptHeader(ProducerRecord<String, String> record) {
        var header = record.headers().lastHeader("x-attempt");
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
