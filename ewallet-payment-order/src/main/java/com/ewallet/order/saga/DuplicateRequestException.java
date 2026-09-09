package com.ewallet.order.saga;

/** R-IDEM-03: trùng X-Idempotency-Key nhưng payload khác. */
public class DuplicateRequestException extends RuntimeException {

    public DuplicateRequestException(String idempotencyKey) {
        super("Idempotency key da duoc dung cho mot payload khac: " + idempotencyKey);
    }
}
