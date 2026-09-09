package com.ewallet.payment.domain;

import java.util.UUID;

/** Lệnh authorize do payment-order gửi sang qua gRPC (bước saga S2). */
public record AuthorizeCommand(
        UUID orderId,
        String customerId,
        PaymentType paymentType,
        long amount,
        String currency,
        String destCustomerId,
        String partnerCode,
        String billCode,
        String idempotencyKey) { }
