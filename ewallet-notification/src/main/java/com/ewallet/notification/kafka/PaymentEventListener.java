package com.ewallet.notification.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consumer group thứ nhất trên topic dùng chung. Stage B: chỉ log.
 * Stage C: ghi notification_outbox + đẩy SSE + phân loại retry / DLQ.
 */
@Component
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    @KafkaListener(
            topics = "${app.kafka.payment-events-topic:ewallet.payment.events}",
            groupId = "notification-cg")
    public void onPaymentEvent(@Payload String message) {
        log.info("notification-cg received: {}", message);
    }
}
