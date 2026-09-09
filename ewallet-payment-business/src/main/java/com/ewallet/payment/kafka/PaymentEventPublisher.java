package com.ewallet.payment.kafka;

import com.ewallet.payment.entity.PaymentTransaction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Nơi duy nhất publish lên {@code ewallet.payment.events} (architecture.md §3).
 *
 * <p>R-EVENT-01: mỗi lần transaction vào trạng thái kết thúc phải có đúng 1 event.
 * R-EVENT-02: key = orderId để mọi event của một order về cùng partition, giữ đúng thứ tự.</p>
 */
@Component
public class PaymentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventPublisher.class);

    private static final String HEADER_EVENT_TYPE = "eventType";
    private static final String HEADER_ATTEMPT = "x-attempt";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public PaymentEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                 ObjectMapper objectMapper,
                                 @Value("${app.kafka.payment-events-topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    public void publishCompleted(PaymentTransaction txn) {
        publish(PaymentEvent.COMPLETED, "COMPLETED", txn);
    }

    public void publishFailed(PaymentTransaction txn) {
        publish(PaymentEvent.FAILED, "FAILED", txn);
    }

    public void publishHeld(PaymentTransaction txn) {
        publish(PaymentEvent.HELD, "HELD", txn);
    }

    public void publishRefunded(PaymentTransaction txn) {
        publish(PaymentEvent.REFUNDED, "REFUNDED", txn);
    }

    private void publish(String eventType, String status, PaymentTransaction txn) {
        PaymentEvent event = new PaymentEvent(
                UUID.randomUUID().toString(),
                eventType,
                Instant.now().toString(),
                txn.getOrderId().toString(),
                txn.getTxnId().toString(),
                txn.getCustomerId(),
                txn.getCounterpartyCustomerId(),
                txn.getPaymentType(),
                txn.getAmount(),
                txn.getFee(),
                txn.getCurrency(),
                txn.getAmountVnd(),
                status,
                txn.getReasonCode(),
                txn.getPartnerCode(),
                txn.getPartnerRef(),
                PaymentEvent.CURRENT_SCHEMA_VERSION);

        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.error("khong serialize duoc event orderId={} eventType={}", txn.getOrderId(), eventType, e);
            return;
        }

        ProducerRecord<String, String> record =
                new ProducerRecord<>(topic, txn.getOrderId().toString(), payload);
        record.headers().add(new RecordHeader(HEADER_EVENT_TYPE, eventType.getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(HEADER_ATTEMPT, "1".getBytes(StandardCharsets.UTF_8)));

        try {
            kafkaTemplate.send(record);
            log.info("publish {} orderId={} txnId={}", eventType, txn.getOrderId(), txn.getTxnId());
        } catch (Exception e) {
            // A10: Kafka chết không được làm hỏng giao dịch — tiền đã đúng, chỉ thông báo bị thiếu.
            log.error("publish that bai eventType={} orderId={}", eventType, txn.getOrderId(), e);
        }
    }
}
