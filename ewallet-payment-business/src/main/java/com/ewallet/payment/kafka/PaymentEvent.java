package com.ewallet.payment.kafka;

/**
 * Event trên topic ewallet.payment.events.
 * Schema: docs/specs/00-domain-and-conventions.md §12. Cả notification-cg và order-status-cg đều đọc.
 * Đổi schema ở đây ảnh hưởng CẢ HAI consumer group theo cách khác nhau.
 */
public record PaymentEvent(
        String eventId,
        String eventType,        // PaymentCompleted | PaymentFailed | PaymentHeld | PaymentRefunded
        String occurredAt,
        String orderId,
        String txnId,
        String customerId,
        String counterpartyCustomerId,
        String paymentType,
        long amount,
        long fee,
        String currency,
        long amountVnd,
        String status,           // COMPLETED | FAILED | HELD | REFUNDED
        String reasonCode,
        String partnerCode,
        String partnerRef,
        int schemaVersion) {

    public static final String COMPLETED = "PaymentCompleted";
    public static final String FAILED = "PaymentFailed";
    public static final String HELD = "PaymentHeld";
    public static final String REFUNDED = "PaymentRefunded";

    public static final int CURRENT_SCHEMA_VERSION = 1;
}
