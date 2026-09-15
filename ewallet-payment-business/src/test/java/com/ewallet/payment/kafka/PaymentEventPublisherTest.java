package com.ewallet.payment.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ewallet.payment.domain.PaymentType;
import com.ewallet.payment.entity.PaymentTransaction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Nơi duy nhất publish lên {@code ewallet.payment.events}.
 *
 * <p>R-EVENT-02: key = orderId để mọi event của một order về cùng partition và giữ đúng thứ tự.
 * A10: Kafka chết không được làm hỏng giao dịch — tiền đã ghi đúng, chỉ thông báo bị thiếu.</p>
 */
@ExtendWith(MockitoExtension.class)
class PaymentEventPublisherTest {

    private static final String TOPIC = "ewallet.payment.events";

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private PaymentEventPublisher publisher;
    private PaymentTransaction txn;

    @BeforeEach
    void setUp() {
        publisher = new PaymentEventPublisher(kafkaTemplate, new ObjectMapper(), TOPIC);

        txn = new PaymentTransaction();
        txn.setTxnId(UUID.randomUUID());
        txn.setOrderId(UUID.randomUUID());
        txn.setCustomerId("CUST-001");
        txn.setCounterpartyCustomerId("CUST-002");
        txn.setPaymentType(PaymentType.P2P.name());
        txn.setAmount(3_000_000L);
        txn.setFee(2_200L);
        txn.setCurrency("VND");
        txn.setAmountVnd(3_000_000L);
        txn.setStatus(PaymentTransaction.CAPTURED);
        txn.setReasonCode("OK");
        txn.setPartnerCode("EVN");
        txn.setPartnerRef("PRT-9911");
        txn.setCreatedAt(OffsetDateTime.now());
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, String> captured() {
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass((Class<ProducerRecord<String, String>>) (Class<?>) ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        return captor.getValue();
    }

    private JsonNode payload(ProducerRecord<String, String> record) throws Exception {
        return new ObjectMapper().readTree(record.value());
    }

    private String header(ProducerRecord<String, String> record, String key) {
        var h = record.headers().lastHeader(key);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("R-EVENT-02: event đi đúng topic với key là orderId")
    void dungTopicVaKey() {
        publisher.publishCompleted(txn);

        ProducerRecord<String, String> record = captured();
        assertThat(record.topic()).isEqualTo(TOPIC);
        assertThat(record.key()).isEqualTo(txn.getOrderId().toString());
    }

    @Test
    @DisplayName("payload mang đủ thông tin cả hai consumer group cần")
    void payloadDayDu() throws Exception {
        publisher.publishCompleted(txn);

        JsonNode json = payload(captured());
        assertThat(json.get("eventType").asText()).isEqualTo(PaymentEvent.COMPLETED);
        assertThat(json.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(json.get("orderId").asText()).isEqualTo(txn.getOrderId().toString());
        assertThat(json.get("txnId").asText()).isEqualTo(txn.getTxnId().toString());
        assertThat(json.get("customerId").asText()).isEqualTo("CUST-001");
        assertThat(json.get("counterpartyCustomerId").asText()).isEqualTo("CUST-002");
        assertThat(json.get("amount").asLong()).isEqualTo(3_000_000L);
        assertThat(json.get("fee").asLong()).isEqualTo(2_200L);
        assertThat(json.get("amountVnd").asLong()).isEqualTo(3_000_000L);
        assertThat(json.get("partnerRef").asText()).isEqualTo("PRT-9911");
        assertThat(json.get("schemaVersion").asInt()).isEqualTo(PaymentEvent.CURRENT_SCHEMA_VERSION);
        assertThat(json.get("eventId").asText()).isNotBlank();
        assertThat(json.get("occurredAt").asText()).isNotBlank();
    }

    @Test
    @DisplayName("mỗi event có eventId riêng để consumer khử trùng lặp")
    void moiEventMotId() throws Exception {
        publisher.publishCompleted(txn);
        publisher.publishCompleted(txn);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass((Class<ProducerRecord<String, String>>) (Class<?>) ProducerRecord.class);
        verify(kafkaTemplate, org.mockito.Mockito.times(2)).send(captor.capture());

        ObjectMapper mapper = new ObjectMapper();
        String first = mapper.readTree(captor.getAllValues().get(0).value()).get("eventId").asText();
        String second = mapper.readTree(captor.getAllValues().get(1).value()).get("eventId").asText();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("header eventType và x-attempt được gắn cho consumer lọc và đếm lần thử")
    void ganHeader() {
        publisher.publishFailed(txn);

        ProducerRecord<String, String> record = captured();
        assertThat(header(record, "eventType")).isEqualTo(PaymentEvent.FAILED);
        assertThat(header(record, "x-attempt")).isEqualTo("1");
    }

    @Test
    @DisplayName("giao dịch treo chờ duyệt phát PaymentHeld")
    void phatEventTreo() throws Exception {
        publisher.publishHeld(txn);

        JsonNode json = payload(captured());
        assertThat(json.get("eventType").asText()).isEqualTo(PaymentEvent.HELD);
        assertThat(json.get("status").asText()).isEqualTo("HELD");
    }

    @Test
    @DisplayName("hoàn tiền phát PaymentRefunded")
    void phatEventHoanTien() throws Exception {
        publisher.publishRefunded(txn);

        JsonNode json = payload(captured());
        assertThat(json.get("eventType").asText()).isEqualTo(PaymentEvent.REFUNDED);
        assertThat(json.get("status").asText()).isEqualTo("REFUNDED");
    }

    @Test
    @DisplayName("giao dịch không có đối tác thì các trường đối tác để trống, không ném lỗi")
    void giaoDichKhongCoDoiTac() throws Exception {
        txn.setPartnerCode(null);
        txn.setPartnerRef(null);
        txn.setCounterpartyCustomerId(null);

        publisher.publishCompleted(txn);

        JsonNode json = payload(captured());
        assertThat(json.get("partnerCode").isNull()).isTrue();
        assertThat(json.get("counterpartyCustomerId").isNull()).isTrue();
    }

    @Test
    @DisplayName("A10: Kafka chết thì nuốt lỗi — tiền đã ghi đúng, không được làm hỏng giao dịch")
    void kafkaChetKhongLamHongGiaoDich() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenThrow(new IllegalStateException("khong ket noi duoc broker"));

        assertThatCode(() -> publisher.publishCompleted(txn)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("không serialize được thì bỏ qua, không gửi message hỏng lên topic")
    void khongSerializeDuoc() throws Exception {
        ObjectMapper broken = mock(ObjectMapper.class);
        when(broken.writeValueAsString(any())).thenThrow(new JsonProcessingException("hong") { });
        PaymentEventPublisher brokenPublisher = new PaymentEventPublisher(kafkaTemplate, broken, TOPIC);

        assertThatCode(() -> brokenPublisher.publishCompleted(txn)).doesNotThrowAnyException();
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }
}
