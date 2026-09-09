package com.ewallet.order.kafka;

import com.ewallet.order.entity.OrderStep;
import com.ewallet.order.entity.PaymentOrder;
import com.ewallet.order.repo.OrderStepRepository;
import com.ewallet.order.repo.PaymentOrderRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumer group thứ hai trên {@code ewallet.payment.events} — chốt trạng thái đơn bất đồng bộ.
 *
 * <p>Đây là nửa còn lại của F5: cùng một event, {@code notification-cg} sinh thông báo cho khách,
 * còn group này cập nhật trạng thái đơn. Hai group độc lập offset, một bên lỗi không chặn bên kia.</p>
 *
 * <p>Spec: docs/specs/F5-async-notification.md — R-ORDST-01, R-ORDST-02, R-ORDST-03.</p>
 */
@Component
public class OrderStatusListener {

    private static final Logger log = LoggerFactory.getLogger(OrderStatusListener.class);

    private final PaymentOrderRepository orderRepository;
    private final OrderStepRepository stepRepository;
    private final ObjectMapper objectMapper;

    public OrderStatusListener(PaymentOrderRepository orderRepository,
                               OrderStepRepository stepRepository,
                               ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.stepRepository = stepRepository;
        this.objectMapper = objectMapper;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PaymentEventView(String eventId, String eventType, String orderId,
                            String txnId, String status, String reasonCode) { }

    @KafkaListener(
            topics = "${app.kafka.payment-events-topic}",
            groupId = "order-status-cg",
            containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onPaymentEvent(ConsumerRecord<String, String> record) {
        PaymentEventView event;
        try {
            event = objectMapper.readValue(record.value(), PaymentEventView.class);
        } catch (Exception e) {
            // D6: JSON hỏng là lỗi vĩnh viễn, retry vô ích.
            log.error("bo qua message khong doc duoc offset={} key={}", record.offset(), record.key(), e);
            return;
        }

        UUID orderId;
        try {
            orderId = UUID.fromString(event.orderId());
        } catch (RuntimeException e) {
            log.error("orderId khong hop le trong event: {}", event.orderId());
            return;
        }

        // R-ORDST-03: cùng eventId chỉ ghi một lần.
        if (stepRepository.existsByOrderIdAndStepNameAndDetail(
                orderId, OrderStep.EVENT_APPLIED, event.eventId())) {
            log.debug("event {} da xu ly truoc do, bo qua", event.eventId());
            return;
        }

        Optional<PaymentOrder> found = orderRepository.findById(orderId);
        if (found.isEmpty()) {
            log.warn("nhan event cho don khong ton tai orderId={}", orderId);
            return;
        }
        PaymentOrder order = found.get();

        String targetStatus = mapStatus(event.eventType());     // R-ORDST-01
        if (targetStatus == null) {
            log.warn("eventType la khong biet: {}", event.eventType());
            return;
        }

        // R-ORDST-02: đã ở trạng thái kết thúc khác thì không ghi đè, chỉ ghi dấu vết.
        if (order.isTerminal() && !targetStatus.equals(order.getStatus())) {
            log.warn("bo qua ghi de orderId={} dang {} nhan event {}",
                    orderId, order.getStatus(), event.eventType());
        } else if (!targetStatus.equals(order.getStatus())) {
            order.setStatus(targetStatus);
            order.setReasonCode(event.reasonCode());
            order.setUpdatedAt(OffsetDateTime.now());
            orderRepository.save(order);
            log.info("cap nhat bat dong bo orderId={} -> {}", orderId, targetStatus);
        }

        stepRepository.save(OrderStep.of(orderId, OrderStep.EVENT_APPLIED, OrderStep.DONE,
                event.eventId(), attemptOf(record), null));
    }

    /** R-ORDST-01. */
    private String mapStatus(String eventType) {
        if (eventType == null) {
            return null;
        }
        return switch (eventType) {
            case "PaymentCompleted" -> PaymentOrder.COMPLETED;
            case "PaymentFailed" -> PaymentOrder.FAILED;
            case "PaymentHeld" -> PaymentOrder.HELD;
            case "PaymentRefunded" -> PaymentOrder.REFUNDED;
            default -> null;
        };
    }

    /** Header x-attempt do producer gắn, dùng để phân biệt lần đầu với retry. */
    private int attemptOf(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader("x-attempt");
        if (header == null) {
            return 1;
        }
        try {
            return Integer.parseInt(new String(header.value()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
