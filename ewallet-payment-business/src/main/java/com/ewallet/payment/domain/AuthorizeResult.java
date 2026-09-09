package com.ewallet.payment.domain;

import java.util.UUID;

/** Kết quả authorize trả về cho payment-order. status = AUTHORIZED | HELD | REJECTED. */
public record AuthorizeResult(
        String status,
        String reasonCode,
        long fee,
        UUID txnId,
        long amountVnd) {

    public static final String AUTHORIZED = "AUTHORIZED";
    public static final String HELD = "HELD";
    public static final String REJECTED = "REJECTED";

    public static AuthorizeResult rejected(String reasonCode, UUID txnId, long amountVnd) {
        return new AuthorizeResult(REJECTED, reasonCode, 0L, txnId, amountVnd);
    }
}
